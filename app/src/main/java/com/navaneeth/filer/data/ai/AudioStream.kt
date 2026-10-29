package com.navaneeth.filer.data.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/**
 * A recording decoded on the fly to 16 kHz mono floats, a window at a time, so a long voice note
 * never has to fit in memory. Works with whatever the phone's codecs play (m4a, mp3, ogg/opus, wav…).
 */
class AudioStream(file: File) : Closeable {

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    // From the decoder's output format, which can differ from the file's (HE-AAC doubles the rate).
    private var sourceRate: Int
    private var channels: Int
    private var floatPcm = false
    /** Length of the recording, when the file says. */
    val durationMs: Long

    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()

    // Resampler state: position in source samples of the next output sample.
    private var sourcePos = 0.0
    private var previous = 0f
    private var sourceIndex = 0L

    /** Decoded 16 kHz samples not yet consumed. */
    private var buffer = FloatArray(WhisperMath.WINDOW_SAMPLES * 2)
    private var size = 0

    init {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IllegalArgumentException("No audio track")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Up to [n] samples from the current position (fewer at the end), without consuming them. */
    fun peek(n: Int): FloatArray {
        while (size < n && !outputDone) pump()
        return buffer.copyOf(minOf(n, size))
    }

    /** Moves the position forward by [n] samples. */
    fun skip(n: Int) {
        val k = minOf(n, size)
        System.arraycopy(buffer, k, buffer, 0, size - k)
        size -= k
    }

    val finished: Boolean get() = outputDone && size == 0

    private fun pump() {
        if (!inputDone) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = codec.getInputBuffer(index)!!
                val read = extractor.readSampleData(input, 0)
                if (read < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val format = codec.outputFormat
            sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
        } else if (index >= 0) {
            val out = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
            out.position(info.offset)
            out.limit(info.offset + info.size)
            if (floatPcm) {
                val pcm = out.asFloatBuffer()
                repeat(pcm.remaining() / channels) {
                    var sum = 0f
                    for (c in 0 until channels) sum += pcm.get()
                    push(sum / channels)
                }
            } else {
                val pcm = out.asShortBuffer()
                repeat(pcm.remaining() / channels) {
                    var sum = 0f
                    for (c in 0 until channels) sum += pcm.get()
                    push(sum / channels / 32768f)
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
        }
    }

    /** One source sample in; linear interpolation to 16 kHz out. */
    private fun push(sample: Float) {
        val step = sourceRate.toDouble() / WhisperMath.SAMPLE_RATE
        while (sourcePos <= sourceIndex) {
            val frac = (sourcePos - (sourceIndex - 1)).toFloat().coerceIn(0f, 1f)
            append(previous + (sample - previous) * frac)
            sourcePos += step
        }
        previous = sample
        sourceIndex++
    }

    private fun append(value: Float) {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = value
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
