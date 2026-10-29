package com.navaneeth.filer.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["fileId"])
    ]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: Long,
    val pageNumber: Int? = null,
    val charStart: Int,
    val charEnd: Int,
    val textSnippet: String,
    /** False for number-heavy text (tables, IDs): kept for keyword search but not embedded. */
    val embedded: Boolean = true
)
