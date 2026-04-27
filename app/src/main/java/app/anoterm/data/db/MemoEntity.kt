package app.anoterm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ターミナル作業中にその場で書き留めるメモ。
 *
 * テーブル名は歴史的経緯で `debug_reports` のまま (旧称 DebugReport)。
 * リネームすると Room migration が要るので table name は据え置き、Kotlin 側だけ Memo に統一。
 */
@Entity(tableName = "debug_reports")
data class MemoEntity(
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
