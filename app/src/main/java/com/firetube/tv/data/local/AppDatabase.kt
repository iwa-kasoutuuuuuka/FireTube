package com.firetube.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VideoHistoryEntity::class, SubscriptionEntity::class, WatchLaterEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2: 「後で見る」テーブル追加、履歴にチャンネル URL 列を追加
         * (fallbackToDestructiveMigration のみだと、バージョン更新時に視聴履歴・登録チャンネルが全消去されるため明示的に移行)
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history_videos ADD COLUMN uploaderUrl TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_later` (" +
                        "`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`uploaderName` TEXT NOT NULL, " +
                        "`uploaderUrl` TEXT, " +
                        "`thumbnailUrl` TEXT NOT NULL, " +
                        "`durationSeconds` INTEGER NOT NULL, " +
                        "`addedTimestamp` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "firetube_local.db"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
