package com.navaneeth.filer.data.ai

import android.content.Context
import com.navaneeth.filer.data.files.FileManagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A word found in text a plugin read. */
data class TextMatch(val path: String, val source: String, val snippet: String)

/**
 * What the AI plugins produced, per file path (files.db). Usable with smart search off; when it's
 * on, [PluginIndex] copies the text into the search index.
 */
class PluginData private constructor(context: Context) {

    private val dao = FileManagerDatabase.getInstance(context).plugins()

    private val _textChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Emits when plugin text was saved, so smart search can take it in. */
    val textChanged: SharedFlow<Unit> = _textChanged.asSharedFlow()

    // Text

    fun textFlow(path: String, source: String): Flow<FileTextEntity?> = dao.textFlow(path, source)

    /** Saved text for [file], or null when there is none or the file changed since. */
    suspend fun freshText(file: File, source: String): FileTextEntity? =
        dao.text(file.path, source)?.takeIf { it.modifiedAt == file.lastModified() }

    /** [searchable] false: text smart search already has (a PDF's own text), so it isn't added again. */
    suspend fun saveText(file: File, source: String, text: String, segments: List<WhisperMath.Segment>? = null, searchable: Boolean = true) {
        val now = System.currentTimeMillis()
        dao.putText(
            FileTextEntity(
                path = file.path,
                source = source,
                modifiedAt = file.lastModified(),
                text = text.trim(),
                segmentsJson = segments?.let(::segmentsToJson),
                createdAt = now,
                indexedAt = if (searchable) null else now
            )
        )
        if (text.isNotBlank()) _textChanged.tryEmit(Unit)
    }

    fun countFlow(source: String): Flow<Int> = dao.countFlow(source)

    suspend fun pathsWithText(source: String): Set<String> = dao.pathsWithText(source).toHashSet()

    /** Files whose plugin text contains every word in [terms], with the words in context. */
    suspend fun search(terms: List<String>, limit: Int = 100): List<TextMatch> = withContext(Dispatchers.IO) {
        val words = terms.map { it.trim() }.filter { it.length >= 2 }
        if (words.isEmpty()) return@withContext emptyList()
        // The rarest-looking (longest) word narrows the rows; the rest are checked here.
        val lead = words.maxBy { it.length }
        dao.textContaining(lead, limit * 3)
            .filter { row -> words.all { row.text.contains(it, ignoreCase = true) } }
            .filter { File(it.path).exists() }
            .take(limit)
            .map { TextMatch(it.path, it.source, snippetAround(it.text, lead)) }
    }

    // Tags

    fun tagsFlow(path: String): Flow<List<FileTagEntity>> = dao.tagsFlow(path)
    fun tagCounts(): Flow<List<TagCount>> = dao.tagCounts()
    fun pathsTaggedFlow(tag: String): Flow<List<String>> = dao.pathsTaggedFlow(tag)

    /** Files carrying every tag in [tags]. */
    suspend fun pathsWithAllTags(tags: List<String>): Set<String> {
        if (tags.isEmpty()) return emptySet()
        return tags.map { dao.pathsTagged(it).toSet() }.reduce { a, b -> a intersect b }
    }

    suspend fun addTag(path: String, raw: String): Boolean {
        val tag = AiText.normalizeTag(raw) ?: return false
        // A tag the user adds replaces a suggested one of the same name.
        dao.removeTag(path, tag)
        dao.addTags(listOf(FileTagEntity(path, tag, isAuto = false, createdAt = System.currentTimeMillis())))
        return true
    }

    suspend fun removeTag(path: String, tag: String) = dao.removeTag(path, tag)

    /** Tags the user has added somewhere: they're offered as suggestions too. */
    suspend fun userTags(): List<String> = dao.allTags().filterNot { it.isAuto }.map { it.tag }.distinct()

    /** False when [raw] isn't a usable tag name. */
    suspend fun renameTag(oldTag: String, raw: String): Boolean {
        val tag = AiText.normalizeTag(raw) ?: return false
        if (tag != oldTag) dao.renameTag(oldTag, tag)
        return true
    }

    suspend fun deleteTag(tag: String) = dao.deleteTag(tag)
    suspend fun removeSuggestedTags() = dao.removeAllAutoTags()
    fun suggestedTagCount(): Flow<Int> = dao.suggestedCount()

    /** Replaces the suggested tags on a file; tags the user added stay. */
    suspend fun setSuggestedTags(path: String, tags: List<String>) {
        dao.removeAutoTags(path)
        val now = System.currentTimeMillis()
        dao.addTags(tags.map { FileTagEntity(path, it, isAuto = true, createdAt = now) })
    }

    // Names and summaries

    fun suggestionFlow(path: String): Flow<RenameSuggestionEntity?> = dao.suggestionFlow(path)

    suspend fun suggestName(file: File, name: String) = dao.putSuggestion(
        RenameSuggestionEntity(file.path, file.lastModified(), name, RenameSuggestionEntity.PENDING, System.currentTimeMillis())
    )

    suspend fun dismissSuggestion(path: String) = dao.setSuggestionStatus(path, RenameSuggestionEntity.DISMISSED)

    suspend fun freshCache(file: File): FileAiCacheEntity? = dao.cache(file.path)?.takeIf { it.modifiedAt == file.lastModified() }

    suspend fun markInsightsDone(file: File) {
        val current = freshCache(file)
        dao.putCache(FileAiCacheEntity(file.path, file.lastModified(), current?.summary, true, System.currentTimeMillis()))
    }

    /** Summaries were saved by earlier versions; they're only kept in memory now. */
    suspend fun clearSummaries() = dao.clearSummaries()

    // Removing a plugin

    suspend fun forgetSource(source: String) = dao.deleteTextFrom(source)

    /** Language model removed: its suggestions go; tags the user added stay. */
    suspend fun forgetLanguageResults() {
        dao.removeAllAutoTags()
        dao.clearSuggestions()
        dao.clearCache()
    }

    /** Drops what was kept for files deleted outside Filer. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        dao.allPaths().filterNot { File(it).exists() }.forEach { forget(it) }
    }

    // Following files (called by FileManager)

    suspend fun moved(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        dao.moveText(oldPath, newPath)
        dao.moveTags(oldPath, newPath)
        dao.moveSuggestions(oldPath, newPath)
        dao.moveCache(oldPath, newPath)
    }

    suspend fun forget(path: String) = withContext(Dispatchers.IO) {
        dao.forgetText(path)
        dao.forgetTags(path)
        dao.forgetSuggestions(path)
        dao.forgetCache(path)
    }

    companion object {
        @Volatile
        private var INSTANCE: PluginData? = null

        fun getInstance(context: Context): PluginData =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PluginData(context.applicationContext).also { INSTANCE = it }
            }

        fun segmentsToJson(segments: List<WhisperMath.Segment>): String = JSONArray().apply {
            segments.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) }
        }.toString()

        fun segmentsFromJson(json: String?): List<WhisperMath.Segment> = runCatching {
            val array = JSONArray(json ?: return emptyList())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                WhisperMath.Segment(o.getLong("s"), o.getLong("e"), o.getString("t"))
            }
        }.getOrDefault(emptyList())

        /** About 80 characters around the first [word], on one line. */
        fun snippetAround(text: String, word: String): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            val at = flat.indexOf(word, ignoreCase = true).coerceAtLeast(0)
            val start = (at - 30).coerceAtLeast(0)
            val end = (at + word.length + 50).coerceAtMost(flat.length)
            return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
        }
    }
}
