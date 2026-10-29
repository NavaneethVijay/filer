package com.navaneeth.filer.data.extractor

data class TextChunk(
    val pageNumber: Int? = null,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
    val snippet: String,
    /** False for number-heavy text: findable by keyword, not worth a model run. */
    val embed: Boolean = true
)

class TextChunker(
    private val chunkSizeWords: Int = 200,   // ~256 tokens
    private val overlapWords: Int = 10,      // just enough to keep a phrase split across passages findable
    private val minTailWords: Int = 60       // a shorter last passage is folded into the previous one
) {
    fun chunk(fullText: String, pageNumber: Int? = null): List<TextChunk> {
        val cleaned = fullText.replace("\r\n", "\n").trim()
        if (cleaned.isBlank()) return emptyList()

        val words = cleaned.split(Regex("\\s+"))
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<TextChunk>()
        var startWordIdx = 0

        while (startWordIdx < words.size) {
            var endWordIdx = minOf(startWordIdx + chunkSizeWords, words.size)
            // Fold a short remainder into this passage rather than paying for a tiny extra one.
            if (words.size - endWordIdx in 1 until minTailWords) endWordIdx = words.size
            val chunkWords = words.subList(startWordIdx, endWordIdx)
            val chunkText = chunkWords.joinToString(" ")

            // Approximate character start and end
            val charStart = fullText.indexOf(chunkWords.first(), 0)
            val charEnd = if (charStart != -1) charStart + chunkText.length else chunkText.length

            // Format a clean snippet (first 140 chars)
            val snippet = if (chunkText.length > 140) {
                chunkText.substring(0, 137).trim() + "..."
            } else {
                chunkText
            }

            // Mostly numbers (statement tables, IDs) embed poorly and cost as much as prose, so they're
            // kept for exact keyword search only.
            chunks.add(
                TextChunk(
                    pageNumber = pageNumber,
                    charStart = maxOf(0, charStart),
                    charEnd = charEnd,
                    text = chunkText,
                    snippet = snippet,
                    embed = isMostlyText(chunkText)
                )
            )

            if (endWordIdx >= words.size) break
            startWordIdx += (chunkSizeWords - overlapWords)
        }

        return chunks
    }

    private fun isMostlyText(text: String): Boolean {
        var letters = 0
        var visible = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            visible++
            if (c.isLetter()) letters++
        }
        return visible == 0 || letters >= visible * MIN_LETTER_RATIO
    }

    private companion object {
        const val MIN_LETTER_RATIO = 0.35
    }
}
