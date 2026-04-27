package app.anoterm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
  @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
  fun observeAll(): Flow<List<SshKeyEntity>>

  @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
  suspend fun findAll(): List<SshKeyEntity>

  @Query("SELECT * FROM ssh_keys WHERE id = :id")
  suspend fun findById(id: Long): SshKeyEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(key: SshKeyEntity): Long

  @Update suspend fun update(key: SshKeyEntity)

  @Query("DELETE FROM ssh_keys WHERE id = :id") suspend fun deleteById(id: Long)
}
