package com.navaneeth.filer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One embedding vector, stored as float16 (half the size of float32 with no measurable loss for
 * cosine search). A text passage has one per chunk; a photo has one for the whole file.
 * Rows go away automatically with their file or chunk.
 */
@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(entity = FileEntity::class, parentColumns = ["id"], childColumns = ["fileId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChunkEntity::class, parentColumns = ["id"], childColumns = ["chunkId"], onDelete = ForeignKey.CASCADE)
    ],
    // One vector per passage, enforced by the database (photos: one per file, kept by IndexRepository).
    indices = [Index("fileId"), Index(value = ["chunkId"], unique = true)]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: Long,
    /** Null for a whole-file (image) embedding. */
    val chunkId: Long?,
    /** [EmbeddingKind] ordinal: text and image scores are centred separately when searching. */
    val kind: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    /** Which engine made it (`EngineTag`: phone-gpu, phone-cpu, fallback, mac-gpu, mac-cpu). */
    @ColumnInfo(defaultValue = "unknown") val engine: String
)

enum class EmbeddingKind { TEXT, IMAGE }

/** Keyword index over passage text (Porter stemming: "renewal" finds "renewals"). */
@Fts4(contentEntity = ChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_PORTER)
@Entity(tableName = "chunks_fts")
data class ChunkFts(
    val textSnippet: String
)

/** Keyword index over file names. */
@Fts4(contentEntity = FileEntity::class, tokenizer = FtsOptions.TOKENIZER_PORTER)
@Entity(tableName = "files_fts")
data class FileNameFts(
    val displayName: String
)
