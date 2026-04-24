package com.example.wanoterm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import com.example.wanoterm.theme.WanotermTheme

class MainActivity : AppCompatActivity() {

  // Android 13+ で FG Service の常駐通知を出すには POST_NOTIFICATIONS の runtime 許可が必要。
  // 落ちても FG Service 自体は動くが通知が無音・不可視になるので UX 的には取得を試みる。
  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 結果は問わない */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    maybeRequestNotificationPermission()
    setContent {
      WanotermTheme {
        // ルート Surface にフォーカスのない領域タップで IME を閉じる動作を仕込む。
        // Compose 標準の TextField フォーカスを外すと自動で IME が閉じる。
        // clickable(indication = null) にしているのでリップルや視覚変化は出ない。
        // 子要素の clickable / Button などは優先的に取られるので影響しない。
        // ターミナル画面は Android View 経由で独自 focus を持つため、ここでの clearFocus()
        // では IME が閉じない（必要なら view 側で個別制御）。
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Surface(
            modifier =
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                  focusManager.clearFocus()
                  keyboard?.hide()
                },
            color = MaterialTheme.colorScheme.background,
        ) {
          MainNavigation()
        }
      }
    }
  }

  private fun maybeRequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    if (granted) return
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
}
