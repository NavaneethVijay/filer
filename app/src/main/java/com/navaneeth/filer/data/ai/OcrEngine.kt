package com.navaneeth.filer.data.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads text in photos and scanned PDF pages with PP-OCRv6 small: the detector finds text lines,
 * the recogniser reads each one. Installed Indian-script readers (PP-OCRv5) also read each line,
 * and the most confident reading wins. CPU only, so it behaves the same on every phone.
 *
 * Not thread-safe: callers serialise through [AiFeatures].
 */
class OcrEngine(private val context: Context) {

    private class Reader(val session: OrtSession, val dict: List<String>)

    private var detector: OrtSession? = null
    private var main: Reader? = null
    private val scripts = HashMap<OcrScript, Reader>()

    private fun load() {
        if (detector == null) detector = OrtRuntime.session(PluginFiles.file(context, AiPlugin.PHOTO_TEXT, "det.onnx"))
        if (main == null) main = reader(PluginFiles.dir(context, AiPlugin.PHOTO_TEXT))
        val installed = AiPluginManager.getInstance(context).installedScripts().toSet()
        scripts.keys.filter { it !in installed }.forEach { scripts.remove(it)?.session?.close() }
        installed.filter { it !in scripts }.forEach { scripts[it] = reader(PluginFiles.dir(context, it.folder)) }
    }

    private fun reader(dir: File) = Reader(
        OrtRuntime.session(File(dir, "rec.onnx")),
        OcrMath.parseDict(File(dir, "rec.yml").readText())
    )

    fun close() {
        detector?.close(); detector = null
        main?.session?.close(); main = null
        scripts.values.forEach { it.session.close() }
        scripts.clear()
    }

    /** The text in a photo, line by line; empty when there is none. */
    fun readImage(file: File): String {
        val bitmap = decode(file) ?: return ""
        return try {
            read(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Text from the first pages of a PDF that has no text layer (a scan). */
    fun readPdf(file: File, maxPages: Int = MAX_PDF_PAGES): String {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                return (0 until min(renderer.pageCount, maxPages)).joinToString("\n\n") { index ->
                    renderer.openPage(index).use { page ->
                        val scale = WORK_SIDE.toFloat() / max(page.width, page.height)
                        val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt(), (page.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        try {
                            read(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }.trim()
            }
        }
    }

    private fun read(bitmap: Bitmap): String {
        load()
        val boxes = detect(bitmap)
        if (boxes.isEmpty()) return ""
        return OcrMath.readingOrder(boxes).mapNotNull { line ->
            line.mapNotNull { box -> recognize(bitmap, box) }.joinToString(" ").takeIf { it.isNotBlank() }
        }.joinToString("\n")
    }

    // region Detection

    private fun detect(bitmap: Bitmap): List<TextBox> {
        val session = detector ?: return emptyList()
        // Longest side at most DET_SIDE, both sides multiples of 32 (the network's stride).
        val scale = min(1f, DET_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(32, (bitmap.width * scale / 32).roundToInt() * 32)
        val h = max(32, (bitmap.height * scale / 32).roundToInt() * 32)
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val input = toTensor(resized, DET_MEAN, DET_STD)
        if (resized != bitmap) resized.recycle()
        val prob = OnnxTensor.createTensor(OrtRuntime.env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                (out[0] as OnnxTensor).floatBuffer.let { buf -> FloatArray(buf.remaining()).also(buf::get) }
            }
        }
        if (prob.size < w * h) return emptyList()
        val sx = bitmap.width.toFloat() / w
        val sy = bitmap.height.toFloat() / h
        return OcrMath.boxes(prob, w, h).map { b ->
            // Back to the photo's pixels; boxes are near-axis-aligned, so scaling the sides by the
            // axis they mostly follow is close enough.
            val cos = kotlin.math.abs(kotlin.math.cos(b.angle))
            val sin = kotlin.math.abs(kotlin.math.sin(b.angle))
            b.copy(
                cx = b.cx * sx,
                cy = b.cy * sy,
                width = b.width * (cos * sx + sin * sy),
                height = b.height * (sin * sx + cos * sy)
            )
        }
    }

    // endregion

    // region Recognition

    private fun recognize(bitmap: Bitmap, box: TextBox): String? {
        val crop = crop(bitmap, box) ?: return null
        try {
            var best = run(main ?: return null, crop)
            for (reader in scripts.values) {
                val other = run(reader, crop)
                if (other.second > best.second) best = other
            }
            return best.first.takeIf { best.second >= MIN_CONFIDENCE && it.isNotBlank() }
        } finally {
            crop.recycle()
        }
    }

    private fun run(reader: Reader, crop: Bitmap): Pair<String, Float> {
        val width = min(MAX_REC_WIDTH, max(REC_HEIGHT, ceil(REC_HEIGHT * crop.width.toFloat() / crop.height).toInt()))
        val resized = Bitmap.createScaledBitmap(crop, width, REC_HEIGHT, true)
        val input = toTensor(resized, REC_MEAN, REC_STD)
        if (resized != crop) resized.recycle()
        return OnnxTensor.createTensor(OrtRuntime.env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_HEIGHT.toLong(), width.toLong())).use { tensor ->
            reader.session.run(mapOf(reader.session.inputNames.first() to tensor)).use { out ->
                val result = out[0] as OnnxTensor
                val shape = result.info.shape
                val buf = result.floatBuffer
                val probs = FloatArray(buf.remaining()).also(buf::get)
                OcrMath.ctcDecode(probs, shape[1].toInt(), shape[2].toInt(), reader.dict)
            }
        }
    }

    /** The box cut out of the photo, rotated level; tall boxes (vertical text) are turned on their side. */
    private fun crop(bitmap: Bitmap, box: TextBox): Bitmap? {
        val w = box.width.roundToInt()
        val h = box.height.roundToInt()
        if (w < 4 || h < 4 || w > 8000 || h > 2000) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            postTranslate(-box.cx, -box.cy)
            postRotate(-Math.toDegrees(box.angle.toDouble()).toFloat())
            postTranslate(w / 2f, h / 2f)
        }
        Canvas(out).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // endregion

    /** NCHW floats in BGR order (as PaddleOCR trains), normalised per channel. */
    private fun toTensor(bitmap: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (b - mean[0]) / std[0]
            out[plane + i] = (g - mean[1]) / std[1]
            out[2 * plane + i] = (r - mean[2]) / std[2]
        }
        return out
    }

    /** Decodes upright (EXIF applied) and no bigger than needed to read small print. */
    private fun decode(file: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > WORK_SIDE) {
                val s = WORK_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }.getOrNull()

    companion object {
        private const val DET_SIDE = 960
        private const val WORK_SIDE = 2048
        private const val REC_HEIGHT = 48
        private const val MAX_REC_WIDTH = 3200
        private const val MIN_CONFIDENCE = 0.5f
        const val MAX_PDF_PAGES = 5
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}
