package com.navaneeth.filer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.navaneeth.filer.data.model.SourceType

@Entity(
    tableName = "sources",
    indices = [Index(value = ["uri"], unique = true)]
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SourceType,
    val uri: String,
    val displayName: String,
    val granted: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)
