package com.example.wanoterm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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

    fun create(context: Context): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
  }
}
