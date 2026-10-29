package com.navaneeth.filer.ui.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** The words of a query worth highlighting (3+ letters, so "a"/"of" don't light up everything). */
fun highlightableTerms(query: String): List<String> =
    query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct()

/** Styles every occurrence of [terms] in [text], case-insensitively. */
fun highlightTerms(text: String, terms: List<String>, style: SpanStyle): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val ranges = terms.flatMap { term ->
        generateSequence(lower.indexOf(term)) { prev -> lower.indexOf(term, prev + term.length).takeIf { it >= 0 } }
            .takeWhile { it >= 0 }
            .map { it until it + term.length }
            .toList()
    }.sortedBy { it.first }
    return buildAnnotatedString {
        var cursor = 0
        for (range in ranges) {
            if (range.first < cursor) continue
            append(text.substring(cursor, range.first))
            withStyle(style) { append(text.substring(range.first, range.last + 1)) }
            cursor = range.last + 1
        }
        append(text.substring(cursor))
    }
}

/**
 * About [windowChars] of [text] centred on the first of [terms] found, with "…" where cut, so a
 * long passage opens on the part that matched. Whitespace runs are collapsed.
 */
fun excerptAround(text: String, terms: List<String>, windowChars: Int = 320): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.length <= windowChars) return clean
    val lower = clean.lowercase()
    val hit = terms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 } }.minOrNull() ?: 0
    val start = (hit - windowChars / 3).coerceIn(0, clean.length - windowChars)
    val end = start + windowChars
    // Cut on word boundaries.
    val from = if (start == 0) 0 else (clean.indexOf(' ', start) + 1).takeIf { it in 1..hit } ?: start
    val to = if (end >= clean.length) clean.length else clean.lastIndexOf(' ', end).takeIf { it > from } ?: end
    return (if (from > 0) "…" else "") + clean.substring(from, to) + (if (to < clean.length) "…" else "")
}
