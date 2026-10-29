package com.navaneeth.filer.data.ai

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A rotated rectangle around a text line: centre, size (width ≥ height) and angle in radians. */
data class TextBox(val cx: Float, val cy: Float, val width: Float, val height: Float, val angle: Float, val score: Float)

/**
 * The pure parts of reading text in photos (PP-OCR's DB post-processing and CTC decoding), kept
 * free of Android so they can be unit-tested.
 */
object OcrMath {

    /**
     * Text boxes from the detector's probability map ([w]×[h]): pixels above [threshold] are grouped
     * into connected regions; each region whose mean probability reaches [boxThreshold] becomes the
     * smallest rotated rectangle around it, grown by [unclipRatio] (the model predicts shrunk boxes).
     */
    fun boxes(
        prob: FloatArray,
        w: Int,
        h: Int,
        threshold: Float = 0.2f,
        boxThreshold: Float = 0.45f,
        unclipRatio: Float = 1.4f,
        minSide: Float = 3f,
        maxBoxes: Int = 1000
    ): List<TextBox> {
        val seen = BooleanArray(w * h)
        val result = ArrayList<TextBox>()
        val stack = IntArray(w * h)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (start in 0 until w * h) {
            if (seen[start] || prob[start] <= threshold) continue
            // Flood fill (8-connected), collecting the region's pixels and edge pixels.
            xs.clear(); ys.clear()
            var top = 0
            stack[top++] = start
            seen[start] = true
            var sum = 0f
            var count = 0
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                sum += prob[p]
                count++
                var edge = false
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) { edge = true; continue }
                    val n = ny * w + nx
                    if (prob[n] <= threshold) { edge = true; continue }
                    if (!seen[n]) {
                        seen[n] = true
                        stack[top++] = n
                    }
                }
                if (edge) { xs += px; ys += py }
            }
            if (count < 4 || sum / count < boxThreshold) continue
            val rect = minAreaRect(xs, ys) ?: continue
            val grown = unclip(rect, unclipRatio)
            if (min(grown.width, grown.height) < minSide) continue
            result += grown.copy(score = sum / count)
            if (result.size >= maxBoxes) break
        }
        return result
    }

    /** Smallest rotated rectangle around the points (rotating calipers over the convex hull). */
    fun minAreaRect(xs: List<Int>, ys: List<Int>): TextBox? {
        val hull = convexHull(xs, ys)
        if (hull.size < 2) return null
        if (hull.size == 2) {
            val (a, b) = hull
            val len = kotlin.math.hypot((b.first - a.first).toFloat(), (b.second - a.second).toFloat()) + 1f
            return TextBox((a.first + b.first) / 2f, (a.second + b.second) / 2f, len, 1f, atan2((b.second - a.second).toFloat(), (b.first - a.first).toFloat()), 0f)
        }
        var best: TextBox? = null
        var bestArea = Float.MAX_VALUE
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val angle = atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())
            val c = cos(angle)
            val s = sin(angle)
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for ((x, y) in hull) {
                val u = x * c + y * s
                val v = -x * s + y * c
                minU = min(minU, u); maxU = max(maxU, u)
                minV = min(minV, v); maxV = max(maxV, v)
            }
            // +1: pixels have size, so a one-pixel-wide line is 1 wide, not 0.
            val width = (maxU - minU + 1).toFloat()
            val height = (maxV - minV + 1).toFloat()
            val area = width * height
            if (area < bestArea) {
                bestArea = area
                val cu = (minU + maxU) / 2
                val cv = (minV + maxV) / 2
                best = TextBox((cu * c - cv * s).toFloat(), (cu * s + cv * c).toFloat(), width, height, angle.toFloat(), 0f)
            }
        }
        return best?.let(::normalize)
    }

    /** Width along the text, angle within ±45°, so crops come out upright. */
    fun normalize(box: TextBox): TextBox {
        var b = if (box.height > box.width) box.copy(width = box.height, height = box.width, angle = box.angle + (Math.PI / 2).toFloat()) else box
        val pi = Math.PI.toFloat()
        var a = b.angle
        while (a > pi / 2) a -= pi
        while (a < -pi / 2) a += pi
        b = b.copy(angle = a)
        return b
    }

    /** PP-OCR's unclip for a rectangle: grow each side by area × ratio / perimeter. */
    fun unclip(box: TextBox, ratio: Float): TextBox {
        val distance = box.width * box.height * ratio / (2 * (box.width + box.height))
        return box.copy(width = box.width + 2 * distance, height = box.height + 2 * distance)
    }

    /** Monotone chain convex hull, counter-clockwise, no duplicate end point. */
    fun convexHull(xs: List<Int>, ys: List<Int>): List<Pair<Int, Int>> {
        val pts = xs.indices.map { xs[it] to ys[it] }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (pts.size <= 2) return pts
        fun cross(o: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>): Long =
            (a.first - o.first).toLong() * (b.second - o.second) - (a.second - o.second).toLong() * (b.first - o.first)
        val lower = ArrayList<Pair<Int, Int>>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<Pair<Int, Int>>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Reading order: lines top to bottom (boxes overlapping vertically share a line), each left to right. */
    fun readingOrder(boxes: List<TextBox>): List<List<TextBox>> {
        val lines = ArrayList<MutableList<TextBox>>()
        for (box in boxes.sortedBy { it.cy }) {
            val line = lines.lastOrNull()
            val ref = line?.let { l -> l.sumOf { it.cy.toDouble() } / l.size }
            val halfHeight = box.height / 2
            if (line != null && ref != null && abs(box.cy - ref) < max(halfHeight, line.first().height / 2)) line += box
            else lines += mutableListOf(box)
        }
        return lines.map { line -> line.sortedBy { it.cx } }
    }

    /**
     * CTC greedy decoding of the recogniser's output ([steps] × [classes] probabilities): best class
     * per step, repeats merged, blank (0) dropped. Class i is `dict[i - 1]`; the class after the
     * dictionary is a space. Returns the text and its mean confidence.
     */
    fun ctcDecode(probs: FloatArray, steps: Int, classes: Int, dict: List<String>): Pair<String, Float> {
        val text = StringBuilder()
        var confidence = 0f
        var kept = 0
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            var bestP = probs[t * classes]
            for (c in 1 until classes) {
                val p = probs[t * classes + c]
                if (p > bestP) { bestP = p; best = c }
            }
            if (best != 0 && best != previous) {
                text.append(if (best - 1 < dict.size) dict[best - 1] else " ")
                confidence += bestP
                kept++
            }
            previous = best
        }
        return text.toString().trim() to (if (kept > 0) confidence / kept else 0f)
    }

    /**
     * The character list from PaddleOCR's `inference.yml` (`PostProcess.character_dict`). Entries are
     * YAML scalars: `- a`, `- 'a'` (with '' for a quote), `- "é"`, or `- ` for a space.
     */
    fun parseDict(yml: String): List<String> {
        val lines = yml.lines()
        val start = lines.indexOfFirst { it.trim() == "character_dict:" }
        if (start < 0) return emptyList()
        val dict = ArrayList<String>()
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("-")) break
            dict += unquote(trimmed.removePrefix("-").removePrefix(" "))
        }
        return dict
    }

    private fun unquote(value: String): String = when {
        value.isEmpty() -> " "
        value.length >= 2 && value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1).replace("''", "'")
        value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") -> unescape(value.substring(1, value.length - 1))
        else -> value
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> if (i + 5 < s.length) { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    'U' -> if (i + 9 < s.length) { out.appendCodePoint(s.substring(i + 2, i + 10).toInt(16)); i += 10; continue }
                    'x' -> if (i + 3 < s.length) { out.append(s.substring(i + 2, i + 4).toInt(16).toChar()); i += 4; continue }
                    'n' -> { out.append('\n'); i += 2; continue }
                    't' -> { out.append('\t'); i += 2; continue }
                    else -> { out.append(n); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
