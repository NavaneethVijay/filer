package com.navaneeth.filer.data.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Transcribes recordings with Whisper tiny (multilingual) on ONNX Runtime, CPU only. Audio is read
 * in 30-second windows; each window is encoded once and decoded greedily with Whisper's timestamp
 * rules, then the next window starts where the last complete segment ended.
 *
 * Not thread-safe: callers serialise through [AiFeatures].
 */
class WhisperEngine(private val context: Context) {

    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private lateinit var vocab: Array<String?>
    private lateinit var specials: WhisperMath.Specials
    private lateinit var suppress: IntArray
    private lateinit var languageIds: Map<String, Int>

    private fun load() {
        if (encoder != null) return
        val dir = PluginFiles.dir(context, AiPlugin.SPEECH)
        val added = JSONObject(File(dir, "added_tokens.json").readText())
        val pieces = JSONObject(File(dir, "vocab.json").readText())
        val config = JSONObject(File(dir, "config.json").readText())
        val size = config.optInt("vocab_size", 51865)
        vocab = arrayOfNulls(size)
        pieces.keys().forEach { key -> pieces.getInt(key).takeIf { it < size }?.let { vocab[it] = key } }
        added.keys().forEach { key -> added.getInt(key).takeIf { it < size }?.let { vocab[it] = key } }
        fun id(token: String) = added.optInt(token, -1).takeIf { it >= 0 } ?: pieces.getInt(token)
        val languages = added.keys().asSequence().filter { LANGUAGE.matches(it) }.associateWith { added.getInt(it) }
        languageIds = languages.mapKeys { it.key.removePrefix("<|").removeSuffix("|>") }
        specials = WhisperMath.Specials(
            endOfText = id("<|endoftext|>"),
            startOfTranscript = id("<|startoftranscript|>"),
            firstLanguage = languages.values.min(),
            lastLanguage = languages.values.max(),
            transcribe = id("<|transcribe|>"),
            noTimestamps = id("<|notimestamps|>"),
            firstTimestamp = id("<|0.00|>"),
            lastTimestamp = id("<|30.00|>")
        )
        suppress = config.optJSONArray("suppress_tokens")?.let { arr -> IntArray(arr.length()) { arr.getInt(it) } } ?: IntArray(0)
        encoder = OrtRuntime.session(File(dir, "encoder.onnx"))
        decoder = OrtRuntime.session(File(dir, "decoder.onnx"))
    }

    fun close() {
        encoder?.close(); encoder = null
        decoder?.close(); decoder = null
    }

    /** Language codes Whisper knows ("en", "hi", "ml"…). */
    fun languages(): List<String> {
        load()
        return languageIds.keys.sorted()
    }

    /**
     * The whole recording as timed segments. [language] is a code like "hi", or null to detect it
     * from the first window. [onProgress] gets 0..1.
     */
    suspend fun transcribe(
        file: File,
        language: String?,
        exclusive: suspend (suspend () -> Unit) -> Unit,
        onProgress: (Float) -> Unit
    ): List<WhisperMath.Segment> {
        load()
        val segments = ArrayList<WhisperMath.Segment>()
        AudioStream(file).use { audio ->
            val total = audio.durationMs.coerceAtLeast(1)
            var offsetMs = 0L
            var languageId = language?.let(languageIds::get)
            while (!audio.finished) {
                coroutineContext.ensureActive()
                val samples = audio.peek(WhisperMath.WINDOW_SAMPLES)
                if (samples.isEmpty()) break
                val mel = WhisperMath.logMel(samples)
                var tokens: List<Int> = emptyList()
                // The model lock is held per window, so a search never waits for a whole recording.
                exclusive {
                    encode(mel).use { encoded ->
                        val lang = languageId ?: detectLanguage(encoded).also { languageId = it }
                        tokens = decode(encoded, lang)
                    }
                }
                run {
                    val windowMs = samples.size * 1000L / WhisperMath.SAMPLE_RATE
                    segments += WhisperMath.segments(tokens, specials, offsetMs, offsetMs + windowMs, ::text)
                    // Continue from the last closed segment unless the window was the end of the audio.
                    val lastTs = tokens.lastOrNull { specials.isTimestamp(it) }
                    val closed = tokens.size >= 2 && specials.isTimestamp(tokens.last()) && !specials.isTimestamp(tokens[tokens.size - 2])
                    val advance = if (samples.size == WhisperMath.WINDOW_SAMPLES && closed && lastTs != null && specials.seconds(lastTs) > 1f) {
                        (specials.seconds(lastTs) * WhisperMath.SAMPLE_RATE).toInt()
                    } else {
                        samples.size
                    }
                    if (advance < samples.size) {
                        // The open segment at the end is decoded again in the next window.
                        val cut = offsetMs + advance * 1000L / WhisperMath.SAMPLE_RATE
                        segments.removeAll { it.startMs >= cut }
                    }
                    audio.skip(advance)
                    offsetMs += advance * 1000L / WhisperMath.SAMPLE_RATE
                    onProgress((offsetMs.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        return segments
    }

    private fun encode(mel: FloatArray): OnnxTensor {
        val session = encoder!!
        OnnxTensor.createTensor(OrtRuntime.env, FloatBuffer.wrap(mel), longArrayOf(1, WhisperMath.N_MELS.toLong(), WhisperMath.FRAMES.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                val hidden = out[0] as OnnxTensor
                val buf = hidden.floatBuffer
                val copy = FloatArray(buf.remaining()).also(buf::get)
                return OnnxTensor.createTensor(OrtRuntime.env, FloatBuffer.wrap(copy), hidden.info.shape)
            }
        }
    }

    private fun logits(encoded: OnnxTensor, ids: List<Int>): FloatArray {
        val session = decoder!!
        val input = LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() })
        OnnxTensor.createTensor(OrtRuntime.env, input, longArrayOf(1, ids.size.toLong())).use { tokens ->
            val feeds = session.inputNames.associateWith { name -> if (name.contains("encoder")) encoded else tokens }
            session.run(feeds).use { out ->
                val logits = (out.get("logits").orElse(out[0]) as OnnxTensor)
                val vocabSize = logits.info.shape[2].toInt()
                val buf = logits.floatBuffer
                val last = FloatArray(vocabSize)
                buf.position((ids.size - 1) * vocabSize)
                buf.get(last)
                return last
            }
        }
    }

    private fun detectLanguage(encoded: OnnxTensor): Int {
        val logits = logits(encoded, listOf(specials.startOfTranscript))
        var best = specials.firstLanguage
        for (id in specials.firstLanguage..specials.lastLanguage) if (logits[id] > logits[best]) best = id
        return best
    }

    private suspend fun decode(encoded: OnnxTensor, language: Int): List<Int> {
        val prompt = listOf(specials.startOfTranscript, language, specials.transcribe)
        val sampled = ArrayList<Int>()
        while (sampled.size < MAX_TOKENS) {
            coroutineContext.ensureActive()
            val logits = logits(encoded, prompt + sampled)
            WhisperMath.applyRules(logits, sampled, specials, suppress)
            var best = 0
            for (i in logits.indices) if (logits[i] > logits[best]) best = i
            if (best == specials.endOfText) break
            sampled += best
            // A runaway loop of the same token is a hallucination: stop the window there.
            if (sampled.size > 12 && sampled.takeLast(12).distinct().size == 1) break
        }
        return sampled
    }

    private fun text(ids: List<Int>): String = WhisperMath.decodePieces(ids.mapNotNull { vocab.getOrNull(it) })

    private companion object {
        val LANGUAGE = Regex("<\\|[a-z]{2,3}(-[a-z]+)?\\|>")
        const val MAX_TOKENS = 220
    }
}
