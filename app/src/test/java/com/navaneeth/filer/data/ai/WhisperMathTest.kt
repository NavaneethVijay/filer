package com.navaneeth.filer.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WhisperMathTest {

    private val sp = WhisperMath.Specials(
        endOfText = 50257, startOfTranscript = 50258, firstLanguage = 50259, lastLanguage = 50357,
        transcribe = 50359, noTimestamps = 50363, firstTimestamp = 50364, lastTimestamp = 51864
    )

    @Test fun melFiltersMatchWhisperShape() {
        val filters = WhisperMath.melFilterBank()
        assertEquals(80, filters.size)
        assertEquals(201, filters[0].size)
        // Every filter covers some frequency, and the lowest one peaks at the start.
        filters.forEach { f -> assertTrue(f.any { it > 0f }) }
        assertTrue(filters[0][1] > 0f)
        assertEquals(0f, filters[0][200], 0f)
    }

    @Test fun silenceIsFlat() {
        val mel = WhisperMath.logMel(FloatArray(WhisperMath.SAMPLE_RATE))
        assertEquals(80 * 3000, mel.size)
        assertTrue(mel.all { kotlin.math.abs(it - mel[0]) < 1e-4f })
    }

    @Test fun aToneLightsUpItsBand() {
        // 1 kHz tone for a second.
        val samples = FloatArray(WhisperMath.SAMPLE_RATE) { (0.5 * sin(2 * PI * 1000 * it / WhisperMath.SAMPLE_RATE)).toFloat() }
        val mel = WhisperMath.logMel(samples)
        val frame = 50
        val column = (0 until 80).map { mel[it * 3000 + frame] }
        val peak = column.indices.maxBy { column[it] }
        // On Whisper's Slaney scale 1 kHz is 15 mel; 80 bands up to 8 kHz are ~0.56 mel apart: band ~26.
        assertTrue("peak at $peak", peak in 24..28)
        // After the tone ends the window is silent, so much lower.
        assertTrue(mel[peak * 3000 + 2000] < column[peak] - 1f)
    }

    @Test fun piecesBecomeText() {
        assertEquals(" hello world", WhisperMath.decodePieces(listOf("Ġhello", "Ġworld")))
        // "é" is two bytes, each mapped to a printable character.
        assertEquals("é", WhisperMath.decodePieces(listOf("Ã©")))
    }

    @Test fun firstTokenIsAnEarlyTimestamp() {
        val logits = FloatArray(51865) { 0f }
        logits[100] = 10f // a text token would win without the rules
        WhisperMath.applyRules(logits, emptyList(), sp, intArrayOf())
        val best = logits.indices.maxBy { logits[it] }
        assertTrue(sp.isTimestamp(best))
        assertTrue(sp.seconds(best) <= 1.0f)
    }

    @Test fun timestampsComeInPairsAndMoveForward() {
        val ts = { s: Float -> sp.firstTimestamp + (s / 0.02f).toInt() }
        // After an opening timestamp: text only.
        val afterOpen = FloatArray(51865) { 0f }.also { it[ts(2f)] = 5f; it[200] = 1f }
        WhisperMath.applyRules(afterOpen, listOf(ts(1f)), sp, intArrayOf())
        assertEquals(200, afterOpen.indices.maxBy { afterOpen[it] })
        // After text then a closing timestamp: a timestamp (not earlier) or the end.
        val afterClose = FloatArray(51865) { 0f }.also { it[300] = 9f; it[ts(0.5f)] = 8f; it[ts(3f)] = 1f }
        WhisperMath.applyRules(afterClose, listOf(ts(1f), 300, ts(2f)), sp, intArrayOf())
        val next = afterClose.indices.maxBy { afterClose[it] }
        assertTrue(next == sp.endOfText || (sp.isTimestamp(next) && sp.seconds(next) >= 2f))
    }

    @Test fun tokensBecomeTimedSegments() {
        val ts = { s: Float -> sp.firstTimestamp + (s / 0.02f).toInt() }
        val tokens = listOf(ts(0f), 1, 2, ts(1.5f), ts(1.5f), 3, ts(3f), ts(4f), 4)
        val segments = WhisperMath.segments(tokens, sp, offsetMs = 10_000, windowEndMs = 40_000) { ids -> ids.joinToString("") { "w$it" } }
        assertEquals(3, segments.size)
        assertEquals(WhisperMath.Segment(10_000, 11_500, "w1w2"), segments[0])
        assertEquals(WhisperMath.Segment(11_500, 13_000, "w3"), segments[1])
        // Text after an unclosed timestamp runs to the end of the window.
        assertEquals(WhisperMath.Segment(14_000, 40_000, "w4"), segments[2])
    }
}
