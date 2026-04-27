package app.anoterm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["address", "port", "key_type"], unique = true)],
)
data class KnownHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "port") val port: Int,
    @ColumnInfo(name = "key_type") val keyType: String, // e.g. "ssh-ed25519"
    @ColumnInfo(name = "fingerprint_sha256") val fingerprintSha256: String,
    @ColumnInfo(name = "public_key_b64") val publicKeyBase64: String,
    @ColumnInfo(name = "trusted_at") val trustedAt: Long = System.currentTimeMillis(),
)

@Dao
interface KnownHostDao {
  @Query("SELECT * FROM known_hosts ORDER BY address, port")
  fun observeAll(): Flow<List<KnownHostEntity>>

  @Query("SELECT * FROM known_hosts WHERE address = :address AND port = :port AND key_type = :keyType LIMIT 1")
  suspend fun find(address: String, port: Int, keyType: String): KnownHostEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: KnownHostEntity): Long

  @Query("DELETE FROM known_hosts WHERE id = :id") suspend fun deleteById(id: Long)

  @Query("DELETE FROM known_hosts WHERE address = :address AND port = :port")
  suspend fun deleteByAddress(address: String, port: Int)
}
