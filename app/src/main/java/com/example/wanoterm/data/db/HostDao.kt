package com.example.wanoterm.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
  @Query("SELECT * FROM hosts ORDER BY last_used_at DESC, label ASC")
  fun observeAll(): Flow<List<HostEntity>>

  @Query("SELECT * FROM hosts WHERE id = :id")
  suspend fun findById(id: Long): HostEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(host: HostEntity): Long

  @Update suspend fun update(host: HostEntity)

  @Delete suspend fun delete(host: HostEntity)

  @Query("UPDATE hosts SET last_used_at = :ts WHERE id = :id")
  suspend fun touchLastUsed(id: Long, ts: Long = System.currentTimeMillis())
}
