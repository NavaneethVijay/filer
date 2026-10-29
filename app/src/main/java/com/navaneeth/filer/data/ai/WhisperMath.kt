package com.navaneeth.filer.data.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Whisper's audio front end and text decoding rules, free of Android so they can be unit-tested. */
object WhisperMath {

    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MELS = 80
    const val WINDOW_SAMPLES = 30 * SAMPLE_RATE
    const val FRAMES = WINDOW_SAMPLES / HOP // 3000
    private const val BINS = N_FFT / 2 + 1 // 201

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() } // periodic Hann
    private val cosTable = FloatArray(BINS * N_FFT) { cos(2 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() }
    private val sinTable = FloatArray(BINS * N_FFT) { sin(2 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() }
    val melFilters: Array<FloatArray> by lazy { melFilterBank() }

    /**
     * Log-mel spectrogram of up to 30 s of 16 kHz audio, as Whisper computes it: [N_MELS]×[FRAMES],
     * row-major. Shorter audio is padded with silence.
     */
    fun logMel(samples: FloatArray, count: Int = samples.size): FloatArray {
        val n = min(count, WINDOW_SAMPLES)
        // Centre padding (reflect) as torch.stft(center=True) does.
        val pad = N_FFT / 2
        fun sample(i: Int): Float {
            val j = when {
                i < 0 -> -i
                i >= WINDOW_SAMPLES -> 2 * (WINDOW_SAMPLES - 1) - i
                else -> i
            }
            return if (j in 0 until n) samples[j] else 0f
        }
        val mel = FloatArray(N_MELS * FRAMES)
        val power = FloatArray(BINS)
        val frame = FloatArray(N_FFT)
        // Frames past the end of the audio are silence: no need to transform them.
        val lastFrame = min(FRAMES, (n + pad) / HOP + 1)
        for (t in 0 until FRAMES) {
            if (t < lastFrame) {
                val start = t * HOP - pad
                for (k in 0 until N_FFT) frame[k] = sample(start + k) * window[k]
                for (b in 0 until BINS) {
                    var re = 0f
                    var im = 0f
                    val base = b * N_FFT
                    for (k in 0 until N_FFT) {
                        re += frame[k] * cosTable[base + k]
                        im -= frame[k] * sinTable[base + k]
                    }
                    power[b] = re * re + im * im
                }
            } else {
                power.fill(0f)
            }
            for (m in 0 until N_MELS) {
                val filter = melFilters[m]
                var sum = 0f
                for (b in 0 until BINS) sum += filter[b] * power[b]
                mel[m * FRAMES + t] = log10(max(sum, 1e-10f))
            }
        }
        val top = mel.max()
        for (i in mel.indices) mel[i] = (max(mel[i], top - 8f) + 4f) / 4f
        return mel
    }

    /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=80) with Slaney scale and norm, as Whisper uses. */
    fun melFilterBank(): Array<FloatArray> {
        fun hzToMel(f: Double): Double {
            val fSp = 200.0 / 3
            val minLogHz = 1000.0
            val minLogMel = minLogHz / fSp
            val logStep = ln(6.4) / 27.0
            return if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logStep else f / fSp
        }
        fun melToHz(m: Double): Double {
            val fSp = 200.0 / 3
            val minLogHz = 1000.0
            val minLogMel = minLogHz / fSp
            val logStep = ln(6.4) / 27.0
            return if (m >= minLogMel) minLogHz * exp(logStep * (m - minLogMel)) else fSp * m
        }
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(N_MELS + 2) { melToHz(maxMel * it / (N_MELS + 1)) }
        val freqs = DoubleArray(BINS) { it * (SAMPLE_RATE / 2.0) / (BINS - 1) }
        return Array(N_MELS) { i ->
            val lower = points[i]
            val centre = points[i + 1]
            val upper = points[i + 2]
            val norm = 2.0 / (upper - lower)
            FloatArray(BINS) { b ->
                val up = (freqs[b] - lower) / (centre - lower)
                val down = (upper - freqs[b]) / (upper - centre)
                (max(0.0, min(up, down)) * norm).toFloat()
            }
        }
    }

    /** GPT-2's byte ↔ printable-character map, used by Whisper's byte-level BPE vocabulary. */
    val unicodeToByte: Map<Char, Int> by lazy {
        val bs = ((33..126) + (161..172) + (174..255)).toMutableList()
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) if (b !in bs) {
            bs += b
            cs += 256 + n
            n++
        }
        bs.indices.associate { cs[it].toChar() to bs[it] }
    }

    /** Joins vocabulary pieces ("Ġhello") back into UTF-8 text. */
    fun decodePieces(pieces: List<String>): String {
        val bytes = ArrayList<Byte>()
        for (piece in pieces) for (c in piece) unicodeToByte[c]?.let { bytes += it.toByte() }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** Token ids Whisper uses around the text. */
    data class Specials(
        val endOfText: Int,
        val startOfTranscript: Int,
        val firstLanguage: Int,
        val lastLanguage: Int,
        val transcribe: Int,
        val noTimestamps: Int,
        val firstTimestamp: Int,
        val lastTimestamp: Int
    ) {
        fun isTimestamp(id: Int) = id in firstTimestamp..lastTimestamp
        fun seconds(id: Int): Float = (id - firstTimestamp) * 0.02f
    }

    /**
     * Whisper's timestamp rules applied to one step's [logits] (in place): [sampled] are the tokens
     * produced so far after the prompt. Timestamps come in pairs around text and never go back in
     * time; the first token is a timestamp within the first second. [suppress] ids are never produced.
     */
    fun applyRules(logits: FloatArray, sampled: List<Int>, sp: Specials, suppress: IntArray) {
        val neg = Float.NEGATIVE_INFINITY
        for (id in suppress) if (id < logits.size) logits[id] = neg
        // Specials other than end-of-text and timestamps are never part of a transcript.
        for (id in sp.startOfTranscript until sp.firstTimestamp) if (id < logits.size) logits[id] = neg
        if (sampled.isEmpty()) {
            for (id in 0 until sp.firstTimestamp) logits[id] = neg
            for (id in sp.firstTimestamp + 50 + 1..sp.lastTimestamp) logits[id] = neg // ≤ 1.0 s
            return
        }
        val last = sampled.last()
        val lastIsTs = sp.isTimestamp(last)
        val penultimateIsTs = sampled.size < 2 || sp.isTimestamp(sampled[sampled.size - 2])
        if (lastIsTs) {
            if (penultimateIsTs) {
                for (id in sp.firstTimestamp..sp.lastTimestamp) logits[id] = neg // after a pair: text
            } else {
                for (id in 0 until sp.endOfText) logits[id] = neg // a segment just closed: timestamp or end
            }
        }
        // Never back in time.
        sampled.lastOrNull { sp.isTimestamp(it) }?.let { lastTs ->
            val from = if (lastIsTs && !penultimateIsTs) lastTs else lastTs + 1
            for (id in sp.firstTimestamp until min(from, sp.lastTimestamp + 1)) logits[id] = neg
        }
        // If timestamps together are likelier than any single text token, take a timestamp.
        val tsLogSum = logSumExp(logits, sp.firstTimestamp, sp.lastTimestamp + 1)
        var maxText = neg
        for (id in 0 until sp.firstTimestamp) if (logits[id] > maxText) maxText = logits[id]
        if (tsLogSum > maxText) for (id in 0 until sp.firstTimestamp) logits[id] = neg
    }

    private fun logSumExp(values: FloatArray, from: Int, to: Int): Float {
        var top = Float.NEGATIVE_INFINITY
        for (i in from until to) if (values[i] > top) top = values[i]
        if (top == Float.NEGATIVE_INFINITY) return top
        var sum = 0.0
        for (i in from until to) sum += exp((values[i] - top).toDouble())
        return top + ln(sum).toFloat()
    }

    /** A piece of transcript with its time in the recording. */
    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    /**
     * Splits one window's tokens ("<|0.00|> Hello <|1.20|><|1.20|> world <|2.40|>") into segments,
     * offset by [offsetMs]. Text after an unclosed timestamp ends at [windowEndMs].
     */
    fun segments(tokens: List<Int>, sp: Specials, offsetMs: Long, windowEndMs: Long, text: (List<Int>) -> String): List<Segment> {
        val out = ArrayList<Segment>()
        var start: Float? = null
        val pending = ArrayList<Int>()
        for (id in tokens) {
            if (sp.isTimestamp(id)) {
                val s = start
                if (s == null) {
                    start = sp.seconds(id)
                } else {
                    val t = text(pending).trim()
                    if (t.isNotEmpty()) out += Segment(offsetMs + (s * 1000).toLong(), offsetMs + (sp.seconds(id) * 1000).toLong(), t)
                    pending.clear()
                    start = null
                }
            } else if (id < sp.endOfText) {
                pending += id
            }
        }
        val t = text(pending).trim()
        if (t.isNotEmpty()) out += Segment(offsetMs + ((start ?: 0f) * 1000).toLong(), windowEndMs, t)
        return out
    }
}
