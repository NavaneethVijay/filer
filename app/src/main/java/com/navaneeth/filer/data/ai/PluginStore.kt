package com.navaneeth.filer.data.ai

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// What the AI plugins produced for a file, kept in files.db (not the search index) so the plugins
// work without smart search and an index rebuild never loses them. Rows are keyed by path and
// follow the file through renames and moves (see PluginData.moved).

/** Where a plugin's text came from. */
object TextSource {
    const val OCR = "OCR"
    const val TRANSCRIPT = "TRANSCRIPT"
}

/**
 * Text a plugin read from a file. [modifiedAt] is the file's mtime when it was read: a file changed
 * since is stale. Empty [text] means "read, nothing found", so it isn't read again.
 * [indexedAt] is when smart search last took this text in (null: not yet).
 */
@Entity(tableName = "file_text", indices = [Index(value = ["path", "source"], unique = true)])
data class FileTextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val source: String,
    val modifiedAt: Long,
    val text: String,
    /** Transcripts: JSON array of {"s": startMs, "e": endMs, "t": text}. */
    val segmentsJson: String? = null,
    val createdAt: Long,
    val indexedAt: Long? = null
)

/** A tag on a file. [isAuto]: suggested by the language model; false: added by the user. */
@Entity(tableName = "file_tags", primaryKeys = ["path", "tag"], indices = [Index("tag")])
data class FileTagEntity(
    val path: String,
    val tag: String,
    val isAuto: Boolean,
    val createdAt: Long
)

/** A clearer name the language model suggested. Never applied without the user's tap. */
@Entity(tableName = "rename_suggestions")
data class RenameSuggestionEntity(
    @PrimaryKey val path: String,
    val modifiedAt: Long,
    val suggestedName: String,
    val status: String,
    val createdAt: Long
) {
    companion object {
        const val PENDING = "PENDING"
        const val DISMISSED = "DISMISSED"
    }
}

/**
 * Language-model results for one version of a file: its summary (made on tap) and whether tags and
 * a name were already suggested ([insightsDone]), so viewing it again doesn't run the model.
 */
@Entity(tableName = "file_ai_cache")
data class FileAiCacheEntity(
    @PrimaryKey val path: String,
    val modifiedAt: Long,
    val summary: String? = null,
    val insightsDone: Boolean = false,
    val cachedAt: Long
)

data class TagCount(val tag: String, val count: Int)

@Dao
interface PluginDao {
    // Text

    @Query("SELECT * FROM file_text WHERE path = :path AND source = :source")
    suspend fun text(path: String, source: String): FileTextEntity?

    @Query("SELECT * FROM file_text WHERE path = :path AND source = :source")
    fun textFlow(path: String, source: String): Flow<FileTextEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putText(row: FileTextEntity)

    /** Text smart search hasn't taken in yet (new, or re-read since). */
    @Query("SELECT * FROM file_text WHERE text != '' AND (indexedAt IS NULL OR indexedAt < createdAt) LIMIT :limit")
    suspend fun textToIndex(limit: Int): List<FileTextEntity>

    @Query("UPDATE file_text SET indexedAt = :at WHERE id = :id")
    suspend fun markIndexed(id: Long, at: Long)

    /** After smart search re-embeds a file (dropping its passages), its plugin text goes back in. */
    @Query("UPDATE file_text SET indexedAt = NULL WHERE path = :path")
    suspend fun markUnindexed(path: String)

    @Query("UPDATE file_text SET indexedAt = NULL")
    suspend fun markAllUnindexed()

    /** Rows whose text contains [term] (case-insensitive for ASCII), newest first. */
    @Query("SELECT * FROM file_text WHERE text LIKE '%' || :term || '%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun textContaining(term: String, limit: Int): List<FileTextEntity>

    @Query("SELECT path FROM file_text WHERE source = :source")
    suspend fun pathsWithText(source: String): List<String>

    @Query("SELECT COUNT(*) FROM file_text WHERE source = :source")
    fun countFlow(source: String): Flow<Int>

    @Query("DELETE FROM file_text WHERE source = :source")
    suspend fun deleteTextFrom(source: String)

    // Tags

    @Query("SELECT * FROM file_tags WHERE path = :path ORDER BY isAuto, createdAt")
    fun tagsFlow(path: String): Flow<List<FileTagEntity>>

    @Query("SELECT tag, COUNT(*) AS count FROM file_tags GROUP BY tag ORDER BY count DESC, tag")
    fun tagCounts(): Flow<List<TagCount>>

    @Query("SELECT path FROM file_tags WHERE tag = :tag ORDER BY createdAt DESC")
    fun pathsTaggedFlow(tag: String): Flow<List<String>>

    @Query("SELECT path FROM file_tags WHERE tag = :tag")
    suspend fun pathsTagged(tag: String): List<String>

    @Query("SELECT path, tag, isAuto, createdAt FROM file_tags")
    suspend fun allTags(): List<FileTagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTags(rows: List<FileTagEntity>)

    @Query("DELETE FROM file_tags WHERE path = :path AND tag = :tag")
    suspend fun removeTag(path: String, tag: String)

    @Query("DELETE FROM file_tags WHERE path = :path AND isAuto = 1")
    suspend fun removeAutoTags(path: String)

    @Query("DELETE FROM file_tags WHERE isAuto = 1")
    suspend fun removeAllAutoTags()

    /** Renames a tag everywhere; a file that already has [newTag] keeps one copy. */
    @Query("UPDATE OR REPLACE file_tags SET tag = :newTag WHERE tag = :oldTag")
    suspend fun renameTag(oldTag: String, newTag: String)

    @Query("DELETE FROM file_tags WHERE tag = :tag")
    suspend fun deleteTag(tag: String)

    @Query("SELECT COUNT(*) FROM file_tags WHERE isAuto = 1")
    fun suggestedCount(): Flow<Int>

    // Names

    @Query("SELECT * FROM rename_suggestions WHERE path = :path")
    fun suggestionFlow(path: String): Flow<RenameSuggestionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSuggestion(row: RenameSuggestionEntity)

    @Query("UPDATE rename_suggestions SET status = :status WHERE path = :path")
    suspend fun setSuggestionStatus(path: String, status: String)

    @Query("DELETE FROM rename_suggestions")
    suspend fun clearSuggestions()

    // Summaries and insights

    @Query("SELECT * FROM file_ai_cache WHERE path = :path")
    suspend fun cache(path: String): FileAiCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(row: FileAiCacheEntity)

    @Query("UPDATE file_ai_cache SET summary = NULL")
    suspend fun clearSummaries()

    @Query("DELETE FROM file_ai_cache")
    suspend fun clearCache()

    @Query("SELECT path FROM file_tags UNION SELECT path FROM file_text UNION SELECT path FROM rename_suggestions UNION SELECT path FROM file_ai_cache")
    suspend fun allPaths(): List<String>

    // Following files: a rename or move of a file or folder carries everything under it.

    @Query("UPDATE OR REPLACE file_text SET path = :newPath || substr(path, length(:oldPath) + 1), indexedAt = NULL WHERE path = :oldPath OR substr(path, 1, length(:oldPath) + 1) = :oldPath || '/'")
    suspend fun moveText(oldPath: String, newPath: String)

    @Query("UPDATE OR REPLACE file_tags SET path = :newPath || substr(path, length(:oldPath) + 1) WHERE path = :oldPath OR substr(path, 1, length(:oldPath) + 1) = :oldPath || '/'")
    suspend fun moveTags(oldPath: String, newPath: String)

    @Query("UPDATE OR REPLACE rename_suggestions SET path = :newPath || substr(path, length(:oldPath) + 1) WHERE path = :oldPath OR substr(path, 1, length(:oldPath) + 1) = :oldPath || '/'")
    suspend fun moveSuggestions(oldPath: String, newPath: String)

    @Query("UPDATE OR REPLACE file_ai_cache SET path = :newPath || substr(path, length(:oldPath) + 1) WHERE path = :oldPath OR substr(path, 1, length(:oldPath) + 1) = :oldPath || '/'")
    suspend fun moveCache(oldPath: String, newPath: String)

    @Query("DELETE FROM file_text WHERE path = :path OR substr(path, 1, length(:path) + 1) = :path || '/'")
    suspend fun forgetText(path: String)

    @Query("DELETE FROM file_tags WHERE path = :path OR substr(path, 1, length(:path) + 1) = :path || '/'")
    suspend fun forgetTags(path: String)

    @Query("DELETE FROM rename_suggestions WHERE path = :path OR substr(path, 1, length(:path) + 1) = :path || '/'")
    suspend fun forgetSuggestions(path: String)

    @Query("DELETE FROM file_ai_cache WHERE path = :path OR substr(path, 1, length(:path) + 1) = :path || '/'")
    suspend fun forgetCache(path: String)
}
