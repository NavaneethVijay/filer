package com.navaneeth.filer.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class OcrMathTest {

    /** A probability map with filled rectangles (x0, y0, x1, y1 inclusive). */
    private fun map(w: Int, h: Int, vararg rects: IntArray): FloatArray {
        val prob = FloatArray(w * h) { 0.05f }
        for (r in rects) for (y in r[1]..r[3]) for (x in r[0]..r[2]) prob[y * w + x] = 0.9f
        return prob
    }

    @Test fun oneLineGivesOneGrownBox() {
        val boxes = OcrMath.boxes(map(100, 40, intArrayOf(10, 15, 89, 24)), 100, 40)
        assertEquals(1, boxes.size)
        val b = boxes.single()
        assertEquals(49.5f, b.cx, 1f)
        assertEquals(19.5f, b.cy, 1f)
        // 80×10 grown by area×1.4/perimeter ≈ 6.2 on each side.
        assertEquals(80f + 12.4f, b.width, 1.5f)
        assertEquals(10f + 12.4f, b.height, 1.5f)
        assertEquals(0f, b.angle, 0.05f)
    }

    @Test fun separateLinesAndNoiseAreHandled() {
        val prob = map(120, 60, intArrayOf(5, 5, 100, 14), intArrayOf(5, 35, 60, 44), intArrayOf(110, 50, 110, 50))
        val boxes = OcrMath.boxes(prob, 120, 60)
        assertEquals(2, boxes.size)
        val lines = OcrMath.readingOrder(boxes)
        assertEquals(2, lines.size)
        assertTrue(lines[0].single().cy < lines[1].single().cy)
    }

    @Test fun weakRegionsAreDropped() {
        val prob = FloatArray(50 * 20) { 0.05f }
        for (y in 5..10) for (x in 5..40) prob[y * 50 + x] = 0.3f // above threshold, below box threshold
        assertTrue(OcrMath.boxes(prob, 50, 20).isEmpty())
    }

    @Test fun rotatedRectangleKeepsItsAngle() {
        val angle = Math.toRadians(20.0)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (u in -40..40) for (v in -5..5) {
            xs += (100 + u * cos(angle) - v * sin(angle)).roundToInt()
            ys += (100 + u * sin(angle) + v * cos(angle)).roundToInt()
        }
        val box = OcrMath.minAreaRect(xs, ys)!!
        assertEquals(20.0, Math.toDegrees(box.angle.toDouble()), 3.0)
        assertTrue(box.width > box.height)
        assertEquals(81f, box.width, 4f)
    }

    @Test fun verticalBoxIsLaidFlat() {
        val box = OcrMath.normalize(TextBox(0f, 0f, 10f, 80f, 0f, 1f))
        assertEquals(80f, box.width)
        assertEquals(10f, box.height)
        assertTrue(abs(abs(box.angle) - Math.PI.toFloat() / 2) < 0.01f)
    }

    @Test fun ctcMergesRepeatsAndDropsBlanks() {
        val dict = listOf("a", "b", "c")
        // classes: 0 blank, 1 a, 2 b, 3 c, 4 space
        val steps = listOf(1, 1, 0, 1, 2, 4, 3, 3)
        val probs = FloatArray(steps.size * 5)
        steps.forEachIndexed { t, c -> probs[t * 5 + c] = 0.9f }
        val (text, confidence) = OcrMath.ctcDecode(probs, steps.size, 5, dict)
        assertEquals("aab c", text)
        assertEquals(0.9f, confidence, 0.001f)
    }

    @Test fun dictionaryEntriesAreUnquoted() {
        val yml = """
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - ''''
              - "é"
              - a
              - '₹'
            Other: x
        """.trimIndent()
        assertEquals(listOf("!", "'", "é", "a", "₹"), OcrMath.parseDict(yml))
    }
}
