package com.example.wanoterm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AuthMethod {
  PASSWORD,
  PRIVATE_KEY,
}

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "port") val port: Int = 22,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "auth") val auth: AuthMethod,
    /** SecretStore 内の参照 id（パスワードまたは鍵本体 + パスフレーズ） */
    @ColumnInfo(name = "secret_id") val secretId: String,
    @ColumnInfo(name = "color_tag") val colorTag: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    /**
     * tmux 統合。true なら接続成功直後に `tmux new -A -s <sessionName>\r` を送出して
     * リモート側に永続 tmux セッションを確保する。iTerm2 流の `-CC` control mode は
     * Phase 2 以降で対応予定。ここでは通常 tmux を自動起動するだけ。
     */
    @ColumnInfo(name = "use_tmux") val useTmux: Boolean = false,
    /** `tmux new -A -s <this>` に使う session 名。空なら "wanoterm" デフォルト */
    @ColumnInfo(name = "tmux_session") val tmuxSession: String = "wanoterm",
)
