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
    entities = [HostEntity::class, KnownHostEntity::class, DebugReportEntity::class, SshKeyEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun hostDao(): HostDao

  abstract fun knownHostDao(): KnownHostDao

  abstract fun debugReportDao(): DebugReportDao

  abstract fun sshKeyDao(): SshKeyDao

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

    // v2 → v3: SshKeyEntity を追加。既存データは温存。
    internal val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ssh_keys` (" +
                    "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "`label` TEXT NOT NULL, " +
                    "`algo` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`secret_id` TEXT NOT NULL, " +
                    "`public_ssh` TEXT NOT NULL)",
            )
          }
        }

    fun create(context: Context): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
  }
}
