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
)
