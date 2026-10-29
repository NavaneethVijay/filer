package com.navaneeth.filer.data.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/** One file a plugin downloads: where from, and its exact size (checked before it's used). */
data class ModelFile(val name: String, val url: String, val sizeBytes: Long)

private fun hf(repo: String, file: String) = "https://huggingface.co/$repo/resolve/main/$file"

/**
 * The optional AI plugins. Each downloads its own model files, never touches the smart-search
 * model, and works per file with or without smart search. All files are public (no login).
 */
enum class AiPlugin(val folder: String, val files: List<ModelFile>) {
    /** PP-OCRv6 small (Apache-2.0): finds text lines, then reads them. ONNX Runtime. */
    PHOTO_TEXT(
        "photo_text",
        listOf(
            ModelFile("det.onnx", hf("PaddlePaddle/PP-OCRv6_small_det_onnx", "inference.onnx"), 9_880_512),
            ModelFile("rec.onnx", hf("PaddlePaddle/PP-OCRv6_small_rec_onnx", "inference.onnx"), 21_159_378),
            ModelFile("rec.yml", hf("PaddlePaddle/PP-OCRv6_small_rec_onnx", "inference.yml"), 150_579)
        )
    ),

    /** Whisper tiny, multilingual, int8 (MIT). ONNX Runtime. */
    SPEECH(
        "speech",
        listOf(
            ModelFile("encoder.onnx", hf("onnx-community/whisper-tiny", "onnx/encoder_model_int8.onnx"), 10_124_977),
            ModelFile("decoder.onnx", hf("onnx-community/whisper-tiny", "onnx/decoder_model_int8.onnx"), 30_461_071),
            ModelFile("vocab.json", hf("onnx-community/whisper-tiny", "vocab.json"), 1_036_584),
            ModelFile("added_tokens.json", hf("onnx-community/whisper-tiny", "added_tokens.json"), 34_604),
            ModelFile("config.json", hf("onnx-community/whisper-tiny", "config.json"), 2_243)
        )
    );

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * Extra readers for Indian scripts (PP-OCRv5 mobile, Apache-2.0), used with the main text finder.
 * Each is optional and small.
 */
enum class OcrScript(val folder: String, val repo: String, val modelBytes: Long, val dictBytes: Long) {
    DEVANAGARI("ocr_devanagari", "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx", 7_912_311, 5_027),
    TAMIL("ocr_tamil", "PaddlePaddle/ta_PP-OCRv5_mobile_rec_onnx", 7_885_691, 4_579),
    TELUGU("ocr_telugu", "PaddlePaddle/te_PP-OCRv5_mobile_rec_onnx", 7_898_759, 4_795);

    val files: List<ModelFile>
        get() = listOf(
            ModelFile("rec.onnx", hf(repo, "inference.onnx"), modelBytes),
            ModelFile("rec.yml", hf(repo, "inference.yml"), dictBytes)
        )
}

/** Where each plugin's files live (app-private, removed with the plugin). */
object PluginFiles {
    fun dir(context: Context, folder: String) = File(context.filesDir, "plugins/$folder")
    fun dir(context: Context, plugin: AiPlugin) = dir(context, plugin.folder)
    fun file(context: Context, plugin: AiPlugin, name: String) = File(dir(context, plugin), name)

    fun isInstalled(context: Context, folder: String, files: List<ModelFile>): Boolean =
        files.all { File(dir(context, folder), it.name).length() == it.sizeBytes }

    fun isInstalled(context: Context, plugin: AiPlugin) = isInstalled(context, plugin.folder, plugin.files)
    fun isInstalled(context: Context, script: OcrScript) = isInstalled(context, script.folder, script.files)
}

/** Why a phone can't run a plugin. */
enum class Unsupported { NOT_64_BIT, TOO_LITTLE_MEMORY }

/**
 * What this phone can run. Thresholds are about total memory, not the model, so a plugin that
 * fits never pushes other apps out.
 */
object DeviceCapability {

    private const val GB = 1024L * 1024 * 1024

    fun totalMemory(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem
    }

    fun is64Bit(): Boolean = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    fun freeSpace(context: Context): Long = StatFs(context.filesDir.path).availableBytes

    /** Null when [plugin] can run here. */
    fun check(context: Context, plugin: AiPlugin): Unsupported? {
        val memory = totalMemory(context)
        return when (plugin) {
            AiPlugin.PHOTO_TEXT, AiPlugin.SPEECH -> if (memory < 2 * GB) Unsupported.TOO_LITTLE_MEMORY else null
        }
    }

    /** Free space needed to download [bytes]: the file plus a margin so the phone isn't left full. */
    fun hasRoomFor(context: Context, bytes: Long): Boolean = freeSpace(context) > bytes + 300L * 1024 * 1024

    /** Threads for CPU inference: most phones have 4 big-ish cores; never all of them. */
    fun inferenceThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
