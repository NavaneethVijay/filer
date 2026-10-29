package com.navaneeth.filer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.navaneeth.filer.data.db.entity.EmbeddingEntity

/** Row shape used to build the in-memory search matrix. */
data class EmbeddingRow(val fileId: Long, val chunkId: Long?, val kind: Int, val vector: ByteArray)

/** Vectors made by one engine. */
data class EngineCount(val engine: String, val count: Int)

/** An indexed passage's text, for comparing engines. */
data class PassageSample(val text: String)

@Dao
interface EmbeddingDao {
    @Insert
    suspend fun insertAll(rows: List<EmbeddingEntity>)

    @Query("SELECT fileId, chunkId, kind, vector FROM embeddings")
    suspend fun all(): List<EmbeddingRow>

    @Query("SELECT * FROM embeddings WHERE fileId = :fileId")
    suspend fun forFile(fileId: Long): List<EmbeddingEntity>

    @Query("SELECT COUNT(*) FROM embeddings WHERE fileId = :fileId")
    suspend fun countForFile(fileId: Long): Int

    @Query("SELECT engine, COUNT(*) AS count FROM embeddings GROUP BY engine ORDER BY count DESC")
    fun engineCounts(): kotlinx.coroutines.flow.Flow<List<EngineCount>>

    /** Files with at least one stand-in vector (made while the model couldn't run). */
    @Query("SELECT DISTINCT fileId FROM embeddings WHERE engine = :engine")
    suspend fun fileIdsWithEngine(engine: String): List<Long>

    /** Random embedded passages (their stored text), for comparing engines. */
    @Query(
        "SELECT chunks.textSnippet AS text FROM embeddings JOIN chunks ON chunks.id = embeddings.chunkId " +
            "WHERE embeddings.kind = :textKind ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun samplePassages(textKind: Int, limit: Int): List<PassageSample>

    /** Uris of random embedded photos, for comparing engines. */
    @Query(
        "SELECT files.uri FROM embeddings JOIN files ON files.id = embeddings.fileId " +
            "WHERE embeddings.kind = :imageKind ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun samplePhotoUris(imageKind: Int, limit: Int): List<String>

    @Query("DELETE FROM embeddings WHERE fileId = :fileId")
    suspend fun deleteForFile(fileId: Long)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAll()

    @Query("DELETE FROM embeddings WHERE fileId IN (:fileIds)")
    suspend fun deleteForFiles(fileIds: List<Long>)

    /**
     * Safety net against duplicate vectors: keeps only the newest vector per passage and per photo,
     * and drops vectors whose file or passage is gone. Returns rows removed.
     */
    @Query(
        "DELETE FROM embeddings WHERE " +
            "(chunkId IS NULL AND id NOT IN (SELECT MAX(id) FROM embeddings WHERE chunkId IS NULL GROUP BY fileId)) " +
            "OR (chunkId IS NOT NULL AND chunkId NOT IN (SELECT id FROM chunks)) " +
            "OR fileId NOT IN (SELECT id FROM files)"
    )
    suspend fun deleteDuplicatesAndOrphans(): Int
}

/** A keyword hit: which file, which passage. */
data class KeywordHit(val fileId: Long, val chunkId: Long)

@Dao
interface KeywordDao {
    /** Passages whose text matches an FTS query (e.g. `insur*`). */
    @Query(
        "SELECT chunks.fileId AS fileId, chunks.id AS chunkId FROM chunks " +
            "JOIN chunks_fts ON chunks.id = chunks_fts.rowid WHERE chunks_fts MATCH :match LIMIT 1000"
    )
    suspend fun passages(match: String): List<KeywordHit>

    /** Files whose name matches an FTS query. */
    @Query("SELECT files.id FROM files JOIN files_fts ON files.id = files_fts.rowid WHERE files_fts MATCH :match LIMIT 1000")
    suspend fun fileNames(match: String): List<Long>
}
