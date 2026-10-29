package com.navaneeth.filer.data.repository

import android.content.Context
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.db.entity.FileEntity
import com.navaneeth.filer.data.inference.EmbedderEngine
import com.navaneeth.filer.data.model.FileTypeFilter
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.data.model.SearchResultItem
import com.navaneeth.filer.data.vector.VectorIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.pow

/**
 * Hybrid search over smart-search folders:
 * - meaning: z-scored cosine against every vector, gated at [Z_GATE];
 * - words: per-term full-text (FTS4, Porter-stemmed) lookups over passages and file names, scored by
 *   how many query terms a file contains (coverage);
 * - score = meaningWeight × max(z, 0) + wordWeight × coverage^1.5 + kind-hint boost.
 * A file is a candidate if it clears the meaning gate or contains at least half the query terms.
 */
class SearchRepository(context: Context) {

    private val db = FilerDatabase.getInstance(context)
    private val fileDao = db.fileDao()
    private val chunkDao = db.chunkDao()
    private val keywordDao = db.keywordDao()
    private val embeddingDao = db.embeddingDao()
    private val embedder = EmbedderEngine(context)

    companion object {
        /** Words that describe structure or file types rather than content. */
        val STOPWORDS = setOf(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "with", "and", "or", "my", "me",
            "that", "this", "these", "those", "from", "about", "where", "what", "which", "who", "when",
            "is", "was", "are", "were", "be", "it", "its", "some", "any", "find", "show", "file", "files",
            "photo", "photos", "picture", "pictures", "image", "images", "screenshot", "screenshots",
            "video", "videos", "pdf", "pdfs", "document", "documents", "recording", "notes", "note"
        )

        /** Words that hint at a file type: a soft +[KIND_HINT_BOOST], never a filter. */
        val KIND_WORDS = mapOf(
            "screenshot" to FileTypeFilter.IMAGES, "screenshots" to FileTypeFilter.IMAGES,
            "photo" to FileTypeFilter.IMAGES, "photos" to FileTypeFilter.IMAGES,
            "picture" to FileTypeFilter.IMAGES, "pictures" to FileTypeFilter.IMAGES,
            "image" to FileTypeFilter.IMAGES, "images" to FileTypeFilter.IMAGES,
            "pdf" to FileTypeFilter.PDF, "pdfs" to FileTypeFilter.PDF,
            "paper" to FileTypeFilter.PDF, "papers" to FileTypeFilter.PDF,
            "document" to FileTypeFilter.DOCUMENTS, "documents" to FileTypeFilter.DOCUMENTS,
            "note" to FileTypeFilter.DOCUMENTS, "notes" to FileTypeFilter.DOCUMENTS,
            "code" to FileTypeFilter.CODE
        )

        const val Z_GATE = 2.0

        /**
         * Text→photo similarity at which a photo plainly matches, measured on EmbeddingGemma 2
         * (LiteRT): real matches scored 0.70–0.79, unrelated photos ≤ 0.65 (kayaking, motorcycle,
         * "boat on river" vs an unrelated "electricity bill"). Retune if the model changes.
         */
        const val IMAGE_COS_FLOOR = 0.70f
        const val MEANING_WEIGHT = 1.0
        const val WORDS_WEIGHT = 2.0
        const val KIND_HINT_BOOST = 1.0
        private const val MIN_COVERAGE = 0.5
        private const val MAX_RESULTS = 100
        private const val MAX_PASSAGES_PER_RESULT = 5

        /** Vectors below this z can't change a ranking meaningfully; skipping them keeps the list small. */
        private const val MIN_Z_CONSIDERED = 0.5
    }

    suspend fun search(
        query: String,
        filter: FileTypeFilter = FileTypeFilter.ALL
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val tokens = trimmed.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val kindHints = tokens.mapNotNull { KIND_WORDS[it] }.toSet()
        val terms = tokens.filter { it.length >= 2 && it !in STOPWORDS }.distinct()

        // Only a type word ("photos", "pdfs"): list indexed files of that type.
        if (terms.isEmpty()) {
            val kind = kindHints.firstOrNull() ?: return@withContext emptyList()
            return@withContext fileDao.getAllIndexedFiles()
                .filter { kindOf(it) == kind }
                .take(MAX_RESULTS)
                .map { it.toResult(score = 1f, snippet = "") }
        }

        // Codes, IDs and long numbers are exact-match lookups: lean on words, not meaning.
        val isLookup = terms.any { t -> t.any(Char::isDigit) && (t.any(Char::isLetter) || t.length >= 4) }
        val meaningWeight = if (isLookup) MEANING_WEIGHT * 0.5 else MEANING_WEIGHT
        val wordWeight = if (isLookup) WORDS_WEIGHT * 2 else WORDS_WEIGHT

        // Meaning: best z per file, and the passage that produced it.
        val bestZ = HashMap<Long, Double>()
        val bestMeaningChunk = HashMap<Long, Long>()
        val chunkZ = HashMap<Long, Double>()
        val chunkFile = HashMap<Long, Long>()
        val queryVector = embedder.embedQuery(trimmed)
        for (m in VectorIndex.search(embeddingDao, queryVector, MIN_Z_CONSIDERED, IMAGE_COS_FLOOR, Z_GATE)) {
            if (m.chunkId != null) {
                chunkZ[m.chunkId] = m.z
                chunkFile[m.chunkId] = m.fileId
            }
            if (m.z > (bestZ[m.fileId] ?: Double.NEGATIVE_INFINITY)) {
                bestZ[m.fileId] = m.z
                if (m.chunkId != null) bestMeaningChunk[m.fileId] = m.chunkId else bestMeaningChunk.remove(m.fileId)
            }
        }

        // Words: which query terms each file contains, and its passage covering the most terms.
        val termsByFile = HashMap<Long, MutableSet<Int>>()
        val termHitsByChunk = HashMap<Long, Int>()
        terms.forEachIndexed { i, term ->
            val match = ftsTerm(term, prefix = i == terms.lastIndex) ?: return@forEachIndexed
            for (hit in runCatching { keywordDao.passages(match) }.getOrDefault(emptyList())) {
                termsByFile.getOrPut(hit.fileId) { HashSet() } += i
                termHitsByChunk[hit.chunkId] = (termHitsByChunk[hit.chunkId] ?: 0) + 1
                chunkFile[hit.chunkId] = hit.fileId
            }
            for (fileId in runCatching { keywordDao.fileNames(match) }.getOrDefault(emptyList())) {
                termsByFile.getOrPut(fileId) { HashSet() } += i
            }
        }
        val bestWordChunk = HashMap<Long, Long>()
        termHitsByChunk.entries.sortedByDescending { it.value }.forEach { (chunkId, _) ->
            chunkFile[chunkId]?.let { bestWordChunk.putIfAbsent(it, chunkId) }
        }

        // Candidates and scores.
        val scored = (bestZ.keys + termsByFile.keys).mapNotNull { fileId ->
            val z = bestZ[fileId] ?: 0.0
            val coverage = (termsByFile[fileId]?.size ?: 0).toDouble() / terms.size
            if (z < Z_GATE && coverage < MIN_COVERAGE) return@mapNotNull null
            fileId to (meaningWeight * max(z, 0.0) + wordWeight * coverage.pow(1.5))
        }
        if (scored.isEmpty()) return@withContext emptyList()

        val files = fileDao.getFilesByIds(scored.map { it.first })
            .filter { it.status == IndexStatus.INDEXED || it.status == IndexStatus.SKIPPED }
            .associateBy { it.id }
        val ranked = scored.mapNotNull { (fileId, base) ->
            val file = files[fileId] ?: return@mapNotNull null
            val kind = kindOf(file)
            if (filter != FileTypeFilter.ALL && kind != filter) return@mapNotNull null
            Triple(file, base + if (kind in kindHints) KIND_HINT_BOOST else 0.0, fileId)
        }.sortedWith(compareByDescending<Triple<FileEntity, Double, Long>> { it.second }.thenBy { it.third })
            .take(MAX_RESULTS)

        val top = ranked.firstOrNull()?.second?.takeIf { it > 0 } ?: 1.0
        // Every passage that matched by meaning (z ≥ gate) or contains a query word, per file.
        val matchedPassages = HashMap<Long, MutableSet<Long>>()
        chunkZ.forEach { (chunkId, z) -> if (z >= Z_GATE) matchedPassages.getOrPut(chunkFile.getValue(chunkId)) { HashSet() } += chunkId }
        termHitsByChunk.keys.forEach { chunkId -> chunkFile[chunkId]?.let { matchedPassages.getOrPut(it) { HashSet() } += chunkId } }
        // A file can match by meaning overall without any one passage clearing the gate: show its best.
        bestMeaningChunk.forEach { (fileId, chunkId) -> matchedPassages.getOrPut(fileId) { HashSet() } += chunkId }
        ranked.map { (file, score, fileId) ->
            val chunkId = bestWordChunk[fileId] ?: bestMeaningChunk[fileId]
            val snippet = chunkId?.let { chunkDao.getChunkById(it) }?.let { extractContextualSnippet(it.textSnippet, terms) }.orEmpty()
            file.toResult(score = (score / top).toFloat(), snippet = snippet).copy(
                matchedChunkIds = matchedPassages[fileId].orEmpty()
                    .sortedByDescending { passageScore(it, chunkZ, termHitsByChunk, terms.size) }
                    .take(MAX_PASSAGES_PER_RESULT),
                matchedTerms = termsByFile[fileId].orEmpty().sorted().map { terms[it] },
                meaningMatch = (bestZ[fileId] ?: 0.0) >= Z_GATE
            )
        }
    }

    /** A passage's own strength: its meaning z plus how many query words it contains. */
    private fun passageScore(chunkId: Long, chunkZ: Map<Long, Double>, termHits: Map<Long, Int>, termCount: Int): Double =
        max(chunkZ[chunkId] ?: 0.0, 0.0) + WORDS_WEIGHT * (termHits[chunkId] ?: 0).toDouble() / termCount

    /** A safe FTS4 term: letters/digits only, optionally as a prefix query (while typing). */
    private fun ftsTerm(term: String, prefix: Boolean): String? {
        val clean = term.filter { it.isLetterOrDigit() }
        if (clean.isEmpty()) return null
        return if (prefix) "$clean*" else clean
    }

    private fun kindOf(file: FileEntity): FileTypeFilter {
        val ext = file.displayName.substringAfterLast('.', "").lowercase()
        return when {
            file.mimeType.startsWith("image/") -> FileTypeFilter.IMAGES
            ext == "pdf" -> FileTypeFilter.PDF
            ext in FileTypeFilter.DOCUMENTS.extensions -> FileTypeFilter.DOCUMENTS
            ext in FileTypeFilter.CODE.extensions -> FileTypeFilter.CODE
            else -> FileTypeFilter.ALL
        }
    }

    private fun FileEntity.toResult(score: Float, snippet: String) = SearchResultItem(
        fileId = id,
        uriString = uri,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        similarityScore = score,
        matchSnippet = snippet,
        matchRationale = "",
        isImage = mimeType.startsWith("image/")
    )

    /** ~180 characters around the first query term found, for the result's preview line. */
    private fun extractContextualSnippet(text: String, queryTerms: List<String>, windowChars: Int = 180): String {
        if (text.isBlank()) return ""
        val lower = text.lowercase()
        val hit = queryTerms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 }?.let { it to term.length } }
            .minByOrNull { it.first }
            ?: return if (text.length > windowChars) text.take(windowChars).trimEnd() + "…" else text
        val half = windowChars / 2
        val start = max(0, hit.first - half)
        val end = minOf(text.length, hit.first + hit.second + half)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).replace(Regex("\\s+"), " ").trim() + suffix
    }
}
