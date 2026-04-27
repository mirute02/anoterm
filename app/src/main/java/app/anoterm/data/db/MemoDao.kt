package app.anoterm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoDao {
  @Query("SELECT * FROM debug_reports ORDER BY createdAt DESC")
  fun observeAll(): Flow<List<MemoEntity>>

  /**
   * 現在のスコープ (ホスト名 / tmux attach 先 = 「ServerA · tmux:wanoterm」のような labelFor 文字列)
   * のメモだけを返す。スコープごとにメモを独立させる仕組み。
   */
  @Query("SELECT * FROM debug_reports WHERE context = :context ORDER BY createdAt DESC")
  fun observeByContext(context: String): Flow<List<MemoEntity>>

  @Query("SELECT * FROM debug_reports ORDER BY createdAt DESC")
  suspend fun findAll(): List<MemoEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(memo: MemoEntity): Long

  @Query("UPDATE debug_reports SET status = :status WHERE id = :id")
  suspend fun setStatus(id: Long, status: String)

  @Query("DELETE FROM debug_reports WHERE id = :id") suspend fun deleteById(id: Long)

  @Query("DELETE FROM debug_reports WHERE status = 'done'") suspend fun purgeDone()
}
