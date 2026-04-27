package app.anoterm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * アプリ内で生成・保管する SSH 秘密鍵のメタ情報。秘密鍵本体は [app.anoterm.data.secrets.SecretStore] に
 * 暗号化保存され、ここでは [secretId] でその blob を指す。
 *
 * ホスト (HostEntity) は自分専用の secret を別途持つ設計のまま変えない。ここで鍵を HostEdit に
 * 流すときは blob を **コピー** して新しい host 用 secret を作る。そうすることで、ここから鍵を
 * 削除してもホスト接続は動き続ける（鍵ローテーションは後日の課題）。
 */
@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** "ed25519" / "rsa4096" 等の識別子（enum 名でなく自由文字列にして将来拡張しやすく） */
    val algo: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "secret_id") val secretId: String,
    /** authorized_keys 1 行形式 ("ssh-ed25519 AAAA... comment") */
    @ColumnInfo(name = "public_ssh") val publicSsh: String,
)
