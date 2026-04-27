package app.anoterm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 実機使用中にユーザが書く bug report / メモ。
 * 後で adb で pull して参照・修正するのが想定ワークフロー。
 */
@Entity(tableName = "debug_reports")
data class DebugReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 作成時刻 (unix millis) */
    val createdAt: Long = System.currentTimeMillis(),
    /** 本文 */
    val body: String,
    /** 書いた時点でアクティブだったタブの label（文脈情報） */
    val context: String? = null,
    /** "open" or "done" */
    val status: String = "open",
)
