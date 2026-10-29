package com.navaneeth.filer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.navaneeth.filer.data.db.entity.ActivityLogEntity
import com.navaneeth.filer.data.db.entity.ChunkEntity
import com.navaneeth.filer.data.db.entity.IndexRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE fileId = :fileId")
    suspend fun getChunksForFile(fileId: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE id = :chunkId LIMIT 1")
    suspend fun getChunkById(chunkId: Long): ChunkEntity?

    @Query("DELETE FROM chunks WHERE fileId NOT IN (SELECT id FROM files)")
    suspend fun deleteOrphans(): Int

    @Query("SELECT * FROM chunks WHERE textSnippet LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchChunksByText(query: String): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>): List<Long>

    @Query("DELETE FROM chunks WHERE fileId = :fileId")
    suspend fun deleteChunksForFile(fileId: Long)

    @Query("DELETE FROM chunks WHERE fileId IN (:fileIds)")
    suspend fun deleteChunksForFiles(fileIds: List<Long>)

    @Query("DELETE FROM chunks WHERE fileId IN (SELECT id FROM files WHERE sourceId = :sourceId)")
    suspend fun deleteChunksBySourceId(sourceId: Long)

    @Query("DELETE FROM chunks")
    suspend fun deleteAllChunks()

    // Passages an AI plugin added (text in a photo, a transcript), marked by a reserved page
    // number (see data/ai/PluginIndex). Vectors are deleted first, not left to the cascade.

    @Query("DELETE FROM embeddings WHERE chunkId IN (SELECT id FROM chunks WHERE fileId = :fileId AND pageNumber = :page)")
    suspend fun deletePluginVectors(fileId: Long, page: Int)

    @Query("DELETE FROM chunks WHERE fileId = :fileId AND pageNumber = :page")
    suspend fun deletePluginChunks(fileId: Long, page: Int)

    @Query("DELETE FROM embeddings WHERE chunkId IN (SELECT id FROM chunks WHERE pageNumber = :page)")
    suspend fun deleteAllPluginVectors(page: Int)

    @Query("DELETE FROM chunks WHERE pageNumber = :page")
    suspend fun deleteAllPluginChunks(page: Int)
}

/**
 * Scan runs and a small activity log, kept only for debugging (not shown in the app).
 * Both are capped so they never grow with the number of files.
 */
@Dao
interface TelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: IndexRunEntity): Long

    @Query("DELETE FROM index_runs WHERE id NOT IN (SELECT id FROM index_runs ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun trimRuns(keep: Int)

    @Transaction
    suspend fun insertIndexRun(run: IndexRunEntity): Long {
        val id = insertRun(run)
        trimRuns(MAX_RUNS)
        return id
    }

    @Query("UPDATE index_runs SET finishedAt = :finishedAt, processedCount = :processed, status = :status WHERE id = :id")
    suspend fun finishIndexRun(id: Long, finishedAt: Long, processed: Int, status: String)

    @Query("SELECT * FROM index_runs WHERE finishedAt IS NOT NULL ORDER BY finishedAt DESC LIMIT 1")
    fun getLatestFinishedRun(): Flow<IndexRunEntity?>

    @Insert
    suspend fun insertActivity(log: ActivityLogEntity)

    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM activity_logs ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimActivity(keep: Int)

    @Transaction
    suspend fun logActivity(log: ActivityLogEntity) {
        insertActivity(log)
        trimActivity(MAX_ACTIVITY)
    }

    @Query("DELETE FROM activity_logs")
    suspend fun clearLogs()

    companion object {
        const val MAX_RUNS = 30
        const val MAX_ACTIVITY = 30
    }
}
