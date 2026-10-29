package com.navaneeth.filer.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.navaneeth.filer.data.model.IndexStatus

@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["sourceId"]),
        Index(value = ["status"]),
        Index(value = ["modifiedAt"])
    ]
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val generation: Long = 0L,
    val status: IndexStatus = IndexStatus.PENDING,
    val errorMessage: String? = null,
    val indexedAt: Long? = null,
    val modelVersion: String? = null,
    val contentHash: String? = null
)
