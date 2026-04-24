package com.example.wanoterm.ui.terminal

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
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
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.data.db.DebugReportEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 実機で使いながら「ここおかしい」をその場でメモするシート。
 *
 * 保存は Room の debug_reports テーブル + 外部ストレージ（adb pull 可能な場所）への
 * JSON ダンプ両方に書く。Claude 側は `adb pull /sdcard/Android/data/.../files/debug_reports.json`
 * で最新のレポートを取得できる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugReportSheet(
    contextLabel: String?,
    onDismiss: () -> Unit,
) {
  val app = remember { WanotermApp.get() }
  val androidContext = remember { app.applicationContext }
  val dao = remember { app.database.debugReportDao() }
  val reports by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  var draft by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  // レポートが変わるたびに外部ストレージへ dump。
  // 連続書き込みがあると全件 JSON serialize を毎回走らせるので 500ms debounce。
  // reports は Flow 由来なので新しい値が来ると LaunchedEffect が再起動し、前回の delay が
  // 暗黙に cancel されて最終状態だけ書かれる。
  LaunchedEffect(reports) {
    kotlinx.coroutines.delay(500)
    withContext(Dispatchers.IO) { exportToFile(androidContext, reports) }
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("デバッグ報告", style = MaterialTheme.typography.titleLarge)
      Text(
          "気づいた不具合・改善点をその場でメモ。後で Claude が adb pull で読んで修正。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          label = { Text("内容を書く") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 3,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
              val body = draft.trim()
              if (body.isEmpty()) return@Button
              scope.launch(Dispatchers.IO) {
                dao.upsert(DebugReportEntity(body = body, context = contextLabel))
              }
              draft = ""
            },
        ) {
          Text("保存")
        }
        TextButton(onClick = onDismiss) { Text("閉じる") }
      }

      val doneCount = reports.count { it.status == "done" }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("既存レポート (${reports.size})", style = MaterialTheme.typography.titleMedium)
        if (doneCount > 0) {
          TextButton(
              onClick = { scope.launch(Dispatchers.IO) { dao.purgeDone() } },
          ) { Text("完了済み ${doneCount} 件を削除") }
        }
      }
      LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(reports, key = { it.id }) { r ->
          ReportRow(
              report = r,
              onToggleDone = {
                scope.launch(Dispatchers.IO) {
                  dao.setStatus(r.id, if (r.status == "done") "open" else "done")
                }
              },
              onDelete = { scope.launch(Dispatchers.IO) { dao.deleteById(r.id) } },
          )
        }
      }
    }
  }
}

@Composable
private fun ReportRow(
    report: DebugReportEntity,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
  ) {
    IconButton(onClick = onToggleDone) {
      Icon(
          imageVector =
              if (report.status == "done") Icons.Filled.CheckCircle
              else Icons.Outlined.RadioButtonUnchecked,
          contentDescription = "toggle done",
          tint =
              if (report.status == "done") MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
          report.body,
          style =
              if (report.status == "done")
                  MaterialTheme.typography.bodyMedium.copy(
                      textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                  )
              else MaterialTheme.typography.bodyMedium,
          color =
              if (report.status == "done") MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
      )
      Text(
          "${formatTime(report.createdAt)}${report.context?.let { " · $it" } ?: ""}",
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
 * レポートを外部ストレージに JSON Lines 形式で書き出す。Claude 側が adb pull する用。
 * Android 11+ では app-specific external storage は adb pull でアクセス可能。
 */
private fun exportToFile(ctx: Context, reports: List<DebugReportEntity>) {
  val dir = ctx.getExternalFilesDir(null) ?: return
  val file = File(dir, "debug_reports.json")
  val sb = StringBuilder()
  sb.append("[\n")
  reports.forEachIndexed { i, r ->
    sb.append("  {")
    sb.append("\"id\":${r.id},")
    sb.append("\"created_at\":${r.createdAt},")
    sb.append("\"created_at_iso\":\"${formatTime(r.createdAt)}\",")
    sb.append("\"status\":\"${r.status}\",")
    sb.append("\"context\":${r.context?.let { "\"${escape(it)}\"" } ?: "null"},")
    sb.append("\"body\":\"${escape(r.body)}\"")
    sb.append("}")
    if (i < reports.size - 1) sb.append(",")
    sb.append("\n")
  }
  sb.append("]\n")
  runCatching { file.writeText(sb.toString()) }
}

private fun escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
