package com.navaneeth.filer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "index_runs")
data class IndexRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val status: String = "RUNNING"
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val action: String, // SCAN, INDEX_FILE, PRUNE, ERROR
    val fileUri: String? = null,
    val fileName: String? = null,
    val details: String? = null,
    val isError: Boolean = false
)
