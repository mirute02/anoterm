package app.anoterm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DebugReportDao {
  @Query("SELECT * FROM debug_reports ORDER BY createdAt DESC")
  fun observeAll(): Flow<List<DebugReportEntity>>

  @Query("SELECT * FROM debug_reports ORDER BY createdAt DESC")
  suspend fun findAll(): List<DebugReportEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(report: DebugReportEntity): Long

  @Query("UPDATE debug_reports SET status = :status WHERE id = :id")
  suspend fun setStatus(id: Long, status: String)

  @Query("DELETE FROM debug_reports WHERE id = :id") suspend fun deleteById(id: Long)

  @Query("DELETE FROM debug_reports WHERE status = 'done'") suspend fun purgeDone()
}
