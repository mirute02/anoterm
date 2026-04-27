package app.anoterm.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anoterm.AnotermApp
import app.anoterm.data.db.MemoEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ターミナル作業中にその場で書き留めるメモシート。
 *
 * - メモは現在のスコープ (`contextLabel`) ごとに分離される。
 *   接続先ホストごと / tmux attach 先ごとに独立したノート帳になる。
 *   loopback タブ・ホスト一覧 (= contextLabel が null) ではタブをまたいだ「全件」を表示する
 *   フォールバック扱い。
 * - 保存先は Room の `debug_reports` テーブル (歴史的な名前を踏襲)。
 * - 副次的に外部ストレージ `<app dir>/files/memos.json` にも JSON ダンプし、adb pull や
 *   ファイラからのバックアップを楽にする。
 * - 「open / done」をチェックボックス的に切り替えられるので、todo メモとしても使える。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoSheet(
    contextLabel: String?,
    onDismiss: () -> Unit,
) {
  val app = remember { AnotermApp.get() }
  val androidContext = remember { app.applicationContext }
  val dao = remember { app.database.memoDao() }
  // contextLabel が確定していればそのスコープのみ。null のときは loading 状態の名残や
  // host 未選択時なので全件にフォールバック。
  val memoFlow =
      remember(dao, contextLabel) {
        if (contextLabel != null) dao.observeByContext(contextLabel) else dao.observeAll()
      }
  val memos by memoFlow.collectAsStateWithLifecycle(initialValue = emptyList())
  var draft by remember { mutableStateOf("") }
  // タップで編集ダイアログを開くための対象。null = 編集中ではない。
  var editing by remember { mutableStateOf<MemoEntity?>(null) }
  val scope = rememberCoroutineScope()

  // 件数が変わるたびに JSON dump。連続入力に備えて 500ms debounce。
  // memos は Flow 由来なので新しい値が来ると LaunchedEffect が再起動し、前回の delay が
  // 暗黙に cancel されて最終状態だけ書かれる。
  LaunchedEffect(memos) {
    kotlinx.coroutines.delay(500)
    withContext(Dispatchers.IO) { exportToFile(androidContext, memos) }
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    // adjustNothing の下では IME が保存ボタンを覆ってしまうため、imePadding でシートの
    // 下端を IME 高さぶん持ち上げる。これがないと長文入力時に「保存」が押せなくなる。
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("メモ", style = MaterialTheme.typography.titleLarge)
      Text(
          buildString {
            append("ターミナル作業中の覚書を残せます。done で取り消し線、todo としても使えます。\n")
            append("メモをタップで編集 / コピー。")
            if (contextLabel != null) {
              append("\nスコープ: ")
              append(contextLabel)
            }
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          label = { Text("メモを書く") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 3,
          // 8 行を超えたらフィールド内でスクロール。これがないと TextField が無限に伸び続け、
          // 保存ボタンが画面下に押し出される。
          maxLines = 8,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
              val body = draft.trim()
              if (body.isEmpty()) return@Button
              scope.launch(Dispatchers.IO) {
                dao.upsert(MemoEntity(body = body, context = contextLabel))
              }
              draft = ""
            },
        ) {
          Text("保存")
        }
        TextButton(onClick = onDismiss) { Text("閉じる") }
      }

      val doneCount = memos.count { it.status == "done" }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("メモ一覧 (${memos.size})", style = MaterialTheme.typography.titleMedium)
        if (doneCount > 0) {
          TextButton(
              onClick = { scope.launch(Dispatchers.IO) { dao.purgeDone() } },
          ) { Text("完了済み ${doneCount} 件を削除") }
        }
      }
      LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(memos, key = { it.id }) { m ->
          MemoRow(
              memo = m,
              onToggleDone = {
                scope.launch(Dispatchers.IO) {
                  dao.setStatus(m.id, if (m.status == "done") "open" else "done")
                }
              },
              onTap = { editing = m },
              onDelete = { scope.launch(Dispatchers.IO) { dao.deleteById(m.id) } },
          )
        }
      }
    }
  }

  editing?.let { target ->
    MemoEditDialog(
        memo = target,
        onDismiss = { editing = null },
        onUpdate = { newBody ->
          scope.launch(Dispatchers.IO) {
            dao.upsert(target.copy(body = newBody))
          }
          editing = null
        },
        onCopy = { body ->
          val cm = androidContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
          if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("anoterm memo", body))
            Toast.makeText(androidContext, "メモをコピーしました", Toast.LENGTH_SHORT).show()
          }
        },
    )
  }
}

@Composable
private fun MemoEditDialog(
    memo: MemoEntity,
    onDismiss: () -> Unit,
    onUpdate: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
  var draft by remember(memo.id) { mutableStateOf(memo.body) }
  val isDirty = draft.trim() != memo.body && draft.isNotBlank()

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("メモを編集") },
      text = {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 12,
        )
      },
      confirmButton = {
        TextButton(
            onClick = { onUpdate(draft.trim()) },
            enabled = isDirty,
        ) { Text("更新") }
      },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(
              onClick = { onCopy(memo.body) },
              colors = ButtonDefaults.textButtonColors(),
          ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(" コピー")
          }
          TextButton(onClick = onDismiss) { Text("閉じる") }
        }
      },
  )
}

@Composable
private fun MemoRow(
    memo: MemoEntity,
    onToggleDone: () -> Unit,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
  ) {
    IconButton(onClick = onToggleDone) {
      Icon(
          imageVector =
              if (memo.status == "done") Icons.Filled.CheckCircle
              else Icons.Outlined.RadioButtonUnchecked,
          contentDescription = "toggle done",
          tint =
              if (memo.status == "done") MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Column(modifier = Modifier.weight(1f).clickable(onClick = onTap)) {
      Text(
          memo.body,
          style =
              if (memo.status == "done")
                  MaterialTheme.typography.bodyMedium.copy(
                      textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                  )
              else MaterialTheme.typography.bodyMedium,
          color =
              if (memo.status == "done") MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
      )
      Text(
          "${formatTime(memo.createdAt)}${memo.context?.let { " · $it" } ?: ""}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onDelete) {
      Icon(Icons.Filled.Delete, contentDescription = "delete")
    }
  }
}

private val timeFormat: SimpleDateFormat by lazy {
  SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
}

private fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

/**
 * メモを外部ストレージに JSON 配列として書き出す。
 * Android 11+ で app-specific external storage は adb pull でアクセス可能。
 */
private fun exportToFile(ctx: Context, memos: List<MemoEntity>) {
  val dir = ctx.getExternalFilesDir(null) ?: return
  val file = File(dir, "memos.json")
  val sb = StringBuilder()
  sb.append("[\n")
  memos.forEachIndexed { i, m ->
    sb.append("  {")
    sb.append("\"id\":${m.id},")
    sb.append("\"created_at\":${m.createdAt},")
    sb.append("\"created_at_iso\":\"${formatTime(m.createdAt)}\",")
    sb.append("\"status\":\"${m.status}\",")
    sb.append("\"context\":${m.context?.let { "\"${escape(it)}\"" } ?: "null"},")
    sb.append("\"body\":\"${escape(m.body)}\"")
    sb.append("}")
    if (i < memos.size - 1) sb.append(",")
    sb.append("\n")
  }
  sb.append("]\n")
  runCatching { file.writeText(sb.toString()) }
}

private fun escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
