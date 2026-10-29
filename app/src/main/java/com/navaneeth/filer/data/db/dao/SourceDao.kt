package com.navaneeth.filer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.navaneeth.filer.data.db.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY addedAt DESC")
    fun getAllSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE granted = 1")
    suspend fun getActiveSources(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE uri = :uri LIMIT 1")
    suspend fun getSourceByUri(uri: String): SourceEntity?

    /** IGNORE, not REPLACE: replacing a source would cascade-delete all of its files and vectors. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: SourceEntity): Long

    @Update
    suspend fun updateSource(source: SourceEntity)

    @Delete
    suspend fun deleteSource(source: SourceEntity)

    @Query("DELETE FROM sources WHERE uri = :uri")
    suspend fun deleteSourceByUri(uri: String)
}
