package com.navaneeth.filer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.navaneeth.filer.data.db.dao.ChunkDao
import com.navaneeth.filer.data.db.dao.EmbeddingDao
import com.navaneeth.filer.data.db.dao.KeywordDao
import com.navaneeth.filer.data.db.dao.FileDao
import com.navaneeth.filer.data.db.dao.SourceDao
import com.navaneeth.filer.data.db.dao.TelemetryDao
import com.navaneeth.filer.data.db.entity.ActivityLogEntity
import com.navaneeth.filer.data.db.entity.ChunkEntity
import com.navaneeth.filer.data.db.entity.ChunkFts
import com.navaneeth.filer.data.db.entity.EmbeddingEntity
import com.navaneeth.filer.data.db.entity.FileNameFts
import com.navaneeth.filer.data.db.entity.FileEntity
import com.navaneeth.filer.data.db.entity.IndexRunEntity
import com.navaneeth.filer.data.db.entity.SourceEntity

@Database(
    entities = [
        SourceEntity::class,
        FileEntity::class,
        ChunkEntity::class,
        IndexRunEntity::class,
        ActivityLogEntity::class,
        EmbeddingEntity::class,
        ChunkFts::class,
        FileNameFts::class
    ],
    version = 6,
    exportSchema = false
)
abstract class FilerDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun fileDao(): FileDao
    abstract fun chunkDao(): ChunkDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun keywordDao(): KeywordDao

    companion object {
        @Volatile
        private var INSTANCE: FilerDatabase? = null

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE files ADD COLUMN contentHash TEXT DEFAULT NULL")
            }
        }

        /** Removes any duplicate or orphaned vectors, then makes "one vector per passage" a unique index. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM chunks WHERE fileId NOT IN (SELECT id FROM files)")
                db.execSQL(
                    "DELETE FROM embeddings WHERE fileId NOT IN (SELECT id FROM files) " +
                        "OR (chunkId IS NOT NULL AND chunkId NOT IN (SELECT id FROM chunks)) " +
                        "OR id NOT IN (SELECT MAX(id) FROM embeddings GROUP BY fileId, COALESCE(chunkId, -1))"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_embeddings_chunkId`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_embeddings_chunkId` ON `embeddings` (`chunkId`)")
            }
        }

        /**
         * Records which engine made each vector. Old vectors are tagged "unknown"; the prompt change
         * that came with this version rebuilds them anyway. Smart folders and files are kept.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `embeddings` ADD COLUMN `engine` TEXT NOT NULL DEFAULT 'unknown'")
            }
        }

        fun getInstance(context: Context): FilerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FilerDatabase::class.java,
                    "filer.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
