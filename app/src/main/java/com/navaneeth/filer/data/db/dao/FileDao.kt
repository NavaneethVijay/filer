package com.navaneeth.filer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.navaneeth.filer.data.db.entity.FileEntity
import com.navaneeth.filer.data.model.IndexStatus
import kotlinx.coroutines.flow.Flow

data class SourceFileCount(val sourceId: Long, val count: Int)

data class SourceProgress(val sourceId: Long, val total: Int, val ready: Int, val failed: Int)

@Dao
interface FileDao {
    @Query("SELECT * FROM files ORDER BY modifiedAt DESC")
    fun getAllFiles(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files ORDER BY modifiedAt DESC LIMIT :limit")
    fun getRecentFiles(limit: Int): Flow<List<FileEntity>>

    @Query("SELECT sourceId, COUNT(*) AS count FROM files GROUP BY sourceId")
    fun getFileCountsBySource(): Flow<List<SourceFileCount>>

    /**
     * Per smart-search folder: files that can be embedded, how many already are, and how many
     * failed (unreadable; retried only when the file changes, so they count as finished).
     */
    @Query("SELECT sourceId, COUNT(*) AS total, SUM(CASE WHEN status = 'INDEXED' THEN 1 ELSE 0 END) AS ready, SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed FROM files WHERE status NOT IN ('SKIPPED', 'GONE') GROUP BY sourceId")
    fun getProgressBySource(): Flow<List<SourceProgress>>

    @Query("SELECT uri FROM files WHERE id = :id")
    suspend fun getUri(id: Long): String?

    @Query("SELECT id FROM files WHERE uri = :uri LIMIT 1")
    suspend fun getIdByUri(uri: String): Long?

    @Query("SELECT * FROM files WHERE status = :status ORDER BY modifiedAt DESC")
    fun getFilesByStatus(status: IndexStatus): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: Long): FileEntity?

    @Query("SELECT * FROM files WHERE id IN (:ids)")
    suspend fun getFilesByIds(ids: List<Long>): List<FileEntity>

    @Query("SELECT * FROM files WHERE uri = :uri LIMIT 1")
    suspend fun getFileByUri(uri: String): FileEntity?

    /**
     * Multi-pass pending ordering:
     * 1. Explicitly added smart folders (LOCAL_FOLDER) get top priority over Whole phone (DEVICE).
     * 2. Documents first (text on GPU ~100ms, immediately searchable).
     * 3. High-priority photos: Camera / DCIM photos, newest first.
     * 4. Other images (downloads, chat media, screenshots), newest first.
     */
    @Query("""
        SELECT files.* FROM files 
        JOIN sources ON files.sourceId = sources.id 
        WHERE files.status = 'PENDING' 
        ORDER BY 
            (sources.type = 'LOCAL_FOLDER') DESC, 
            (files.mimeType LIKE 'image/%') ASC, 
            (CASE WHEN files.uri LIKE '%/DCIM/%' OR files.uri LIKE '%/Camera/%' THEN 0 ELSE 1 END) ASC, 
            files.modifiedAt DESC 
        LIMIT :limit
    """)
    suspend fun getPendingFiles(limit: Int): List<FileEntity>

    @Query("""
        SELECT files.* FROM files 
        JOIN sources ON files.sourceId = sources.id 
        WHERE files.status = 'PENDING' AND files.id NOT IN (:excludeIds) 
        ORDER BY 
            (sources.type = 'LOCAL_FOLDER') DESC, 
            (files.mimeType LIKE 'image/%') ASC, 
            (CASE WHEN files.uri LIKE '%/DCIM/%' OR files.uri LIKE '%/Camera/%' THEN 0 ELSE 1 END) ASC, 
            files.modifiedAt DESC 
        LIMIT :limit
    """)
    suspend fun getPendingFilesExcluding(excludeIds: List<Long>, limit: Int): List<FileEntity>

    @Query("SELECT COUNT(*) FROM files WHERE status = 'PENDING'")
    suspend fun getPendingFilesCount(): Int

    @Query("SELECT * FROM files WHERE status = 'INDEXED'")
    suspend fun getAllIndexedFiles(): List<FileEntity>

    @Query("SELECT * FROM files WHERE displayName LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchFilesByName(query: String): List<FileEntity>

    @Query("SELECT COUNT(*) FROM files")
    fun getTotalFileCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM files")
    suspend fun getTotalFilesCount(): Int

    @Query("SELECT COUNT(*) FROM files WHERE status = :status")
    fun getCountByStatus(status: IndexStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM files WHERE status = 'INDEXED'")
    suspend fun getIndexedCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFile(file: FileEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFiles(files: List<FileEntity>): List<Long>

    @Update
    suspend fun updateFile(file: FileEntity)

    @Update
    suspend fun updateFiles(files: List<FileEntity>)

    @Query("SELECT * FROM files")
    suspend fun getAllFilesOnce(): List<FileEntity>

    @Query("DELETE FROM files WHERE id IN (:ids)")
    suspend fun deleteFilesByIds(ids: List<Long>)

    @Delete
    suspend fun deleteFile(file: FileEntity)

    @Query("DELETE FROM files WHERE uri = :uri")
    suspend fun deleteFileByUri(uri: String)

    @Query("UPDATE files SET status = :status, errorMessage = :error, indexedAt = :timestamp, modelVersion = :modelVersion, contentHash = :contentHash WHERE id = :fileId")
    suspend fun updateFileStatus(
        fileId: Long,
        status: IndexStatus,
        error: String? = null,
        timestamp: Long? = null,
        modelVersion: String? = null,
        contentHash: String? = null
    )

    @Query("UPDATE files SET status = 'PENDING' WHERE status NOT IN ('INDEXED', 'GONE')")
    suspend fun resetPendingFiles()

    /** Marks every readable file for re-embedding; files the indexer can't read stay skipped. */
    @Query("UPDATE files SET status = 'PENDING', errorMessage = NULL WHERE status NOT IN ('SKIPPED', 'GONE')")
    suspend fun resetAllToPending()

    @Query("SELECT * FROM files WHERE sourceId = :sourceId")
    suspend fun getFilesBySourceId(sourceId: Long): List<FileEntity>

    @Query("DELETE FROM files WHERE sourceId = :sourceId")
    suspend fun deleteFilesBySourceId(sourceId: Long)

    @Query("DELETE FROM files")
    suspend fun deleteAllFiles()

    @Query("DELETE FROM files WHERE id = :fileId")
    suspend fun pruneFile(fileId: Long)

    /** The file at [uri] and, if it's a folder, everything under it ([prefix] = uri + "/"). */
    @Query("SELECT * FROM files WHERE uri = :uri OR substr(uri, 1, length(:prefix)) = :prefix")
    suspend fun getFilesAtOrUnder(uri: String, prefix: String): List<FileEntity>
}
