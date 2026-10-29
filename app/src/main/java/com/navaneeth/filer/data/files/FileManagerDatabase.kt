package com.navaneeth.filer.data.files

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.navaneeth.filer.data.ai.FileAiCacheEntity
import com.navaneeth.filer.data.ai.FileTagEntity
import com.navaneeth.filer.data.ai.FileTextEntity
import com.navaneeth.filer.data.ai.PluginDao
import com.navaneeth.filer.data.ai.RenameSuggestionEntity
import kotlinx.coroutines.flow.Flow

/** A file or folder in the Recycle bin. [storedPath] is where it sits now, [originalPath] where it came from. */
@Entity(tableName = "trash")
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val originalPath: String,
    val storedPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mimeType: String,
    val deletedAt: Long
)

/** A file or folder moved into the Locked folder (app-private storage). */
@Entity(tableName = "locked")
data class LockedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val originalPath: String,
    val storedPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mimeType: String,
    val lockedAt: Long
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val addedAt: Long
)

/**
 * Where a photo was taken, read once from its EXIF data. [lat] is null for photos without a
 * location, so they aren't read again until they change.
 */
@Entity(tableName = "photo_places", indices = [Index("region"), Index("country"), Index("lat")])
data class PhotoPlaceEntity(
    @PrimaryKey val path: String,
    val modifiedAt: Long,
    val lat: Double?,
    val lon: Double?,
    val city: String?,
    val region: String?,
    val country: String?
)

@Dao
interface PhotoPlaceDao {
    @Query("SELECT path, modifiedAt FROM photo_places")
    suspend fun scanned(): List<ScannedPhoto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<PhotoPlaceEntity>)

    @Query("DELETE FROM photo_places WHERE path IN (:paths)")
    suspend fun delete(paths: List<String>)

    @Query("SELECT * FROM photo_places WHERE path = :path")
    suspend fun get(path: String): PhotoPlaceEntity?

    @Query("SELECT path FROM photo_places WHERE region = :region AND country = :country")
    suspend fun inRegion(region: String, country: String): List<String>

    @Query("SELECT path FROM photo_places WHERE country = :country")
    suspend fun inCountry(country: String): List<String>

    @Query("SELECT * FROM photo_places WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<PhotoPlaceEntity>

    @Query("SELECT COUNT(*) FROM photo_places WHERE lat IS NOT NULL")
    suspend fun withLocationCount(): Int

    @Query("DELETE FROM photo_places")
    suspend fun clear()
}

data class ScannedPhoto(val path: String, val modifiedAt: Long)

@Dao
interface FileManagerDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun trash(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash WHERE id IN (:ids)")
    suspend fun trashByIds(ids: List<Long>): List<TrashEntity>

    @Query("SELECT * FROM trash")
    suspend fun allTrash(): List<TrashEntity>

    @Query("SELECT * FROM trash WHERE deletedAt < :before")
    suspend fun trashOlderThan(before: Long): List<TrashEntity>

    @Insert
    suspend fun insertTrash(item: TrashEntity): Long

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun deleteTrash(id: Long)

    @Query("SELECT * FROM locked ORDER BY lockedAt DESC")
    fun locked(): Flow<List<LockedEntity>>

    @Query("SELECT * FROM locked WHERE id IN (:ids)")
    suspend fun lockedByIds(ids: List<Long>): List<LockedEntity>

    @Insert
    suspend fun insertLocked(item: LockedEntity): Long

    @Query("DELETE FROM locked WHERE id = :id")
    suspend fun deleteLocked(id: Long)

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites")
    suspend fun allFavorites(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavorite(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE path = :path OR path LIKE :path || '/%'")
    suspend fun deleteFavorite(path: String)

    @Query("UPDATE favorites SET path = :newPath WHERE path = :oldPath")
    suspend fun renameFavorite(oldPath: String, newPath: String)
}

/**
 * File-manager state (Recycle bin, Locked folder, favourites, photo places, what the AI plugins
 * produced), kept apart from the search index so changing either schema never wipes the other.
 */
@Database(
    entities = [
        TrashEntity::class, LockedEntity::class, FavoriteEntity::class, PhotoPlaceEntity::class,
        FileTextEntity::class, FileTagEntity::class, RenameSuggestionEntity::class, FileAiCacheEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class FileManagerDatabase : RoomDatabase() {
    abstract fun dao(): FileManagerDao
    abstract fun photoPlaces(): PhotoPlaceDao
    abstract fun plugins(): PluginDao

    companion object {
        @Volatile
        private var INSTANCE: FileManagerDatabase? = null

        // Never destructive: the bin and Locked folder records point at real files.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photo_places` (`path` TEXT NOT NULL, `modifiedAt` INTEGER NOT NULL, " +
                        "`lat` REAL, `lon` REAL, `city` TEXT, `region` TEXT, `country` TEXT, PRIMARY KEY(`path`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_places_region` ON `photo_places` (`region`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_places_country` ON `photo_places` (`country`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_places_lat` ON `photo_places` (`lat`)")
            }
        }

        /**
         * The AI plugins' tables, exactly as Room creates them (app/schemas/…/3.json); a unit test
         * checks the two match, so the migration can't leave a schema Room rejects.
         */
        internal val PLUGIN_TABLES_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `file_text` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `modifiedAt` INTEGER NOT NULL, `text` TEXT NOT NULL, `segmentsJson` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `indexedAt` INTEGER)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_file_text_path_source` ON `file_text` (`path`, `source`)",
            "CREATE TABLE IF NOT EXISTS `file_tags` (`path` TEXT NOT NULL, `tag` TEXT NOT NULL, `isAuto` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`path`, `tag`))",
            "CREATE INDEX IF NOT EXISTS `index_file_tags_tag` ON `file_tags` (`tag`)",
            "CREATE TABLE IF NOT EXISTS `rename_suggestions` (`path` TEXT NOT NULL, `modifiedAt` INTEGER NOT NULL, " +
                "`suggestedName` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`path`))",
            "CREATE TABLE IF NOT EXISTS `file_ai_cache` (`path` TEXT NOT NULL, `modifiedAt` INTEGER NOT NULL, `summary` TEXT, " +
                "`insightsDone` INTEGER NOT NULL, `cachedAt` INTEGER NOT NULL, PRIMARY KEY(`path`))"
        )

        // Adds the AI plugins' tables; nothing existing changes.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                PLUGIN_TABLES_SQL.forEach(db::execSQL)
            }
        }

        fun getInstance(context: Context): FileManagerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, FileManagerDatabase::class.java, "files.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
