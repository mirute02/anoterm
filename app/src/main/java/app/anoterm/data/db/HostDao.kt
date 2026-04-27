package app.anoterm.data.db

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

  /**
   * label / address / port / username / tmux attach 先が全一致する他レコード（excludeId は除外）を探す。
   * HostEdit 保存前の重複検知に使う。複数ヒットしたら最も古い 1 件だけ返す。
   * 呼び出し側で trim 済みの値が渡ってきた場合でも、DB 既存データ側に末尾空白が潜んでいる
   * 可能性があるので SQL 側でも TRIM() してマッチさせる（defense-in-depth）。
   */
  @Query(
      "SELECT * FROM hosts WHERE TRIM(label) = :label AND TRIM(address) = :address AND port = :port "
          + "AND TRIM(username) = :username AND use_tmux = :useTmux "
          + "AND (:useTmux = 0 OR COALESCE(NULLIF(TRIM(tmux_session), ''), 'anoterm') = :tmuxSession) "
          + "AND id != :excludeId LIMIT 1",
  )
  suspend fun findDuplicate(
      label: String,
      address: String,
      port: Int,
      username: String,
      useTmux: Boolean,
      tmuxSession: String,
      excludeId: Long = -1L,
  ): HostEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(host: HostEntity): Long

  @Update suspend fun update(host: HostEntity)

  @Delete suspend fun delete(host: HostEntity)

  @Query("UPDATE hosts SET last_used_at = :ts WHERE id = :id")
  suspend fun touchLastUsed(id: Long, ts: Long = System.currentTimeMillis())
}
