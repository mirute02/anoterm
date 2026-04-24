package com.example.wanoterm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class RoomConverters {
  @TypeConverter fun authToString(v: AuthMethod): String = v.name

  @TypeConverter
  fun authFromString(v: String): AuthMethod =
      runCatching { AuthMethod.valueOf(v) }.getOrDefault(AuthMethod.PASSWORD)
}

@Database(
    entities = [HostEntity::class, KnownHostEntity::class, DebugReportEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun hostDao(): HostDao

  abstract fun knownHostDao(): KnownHostDao

  abstract fun debugReportDao(): DebugReportDao

  companion object {
    private const val DB_NAME = "wanoterm.db"

    // v1 → v2: DebugReportEntity を追加するだけ。hosts / known_hosts には一切触れない。
    // ここを fallbackToDestructiveMigration に戻すと既存ユーザのホスト情報が全消失するので
    // 必ず Migration を書くこと（Play Store 更新時の事故 #1）。
    internal val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `debug_reports` (" +
                    "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`body` TEXT NOT NULL, " +
                    "`context` TEXT, " +
                    "`status` TEXT NOT NULL)",
            )
          }
        }

    fun create(context: Context): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
  }
}
