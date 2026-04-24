package com.example.wanoterm.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.wanoterm.MainActivity
import com.example.wanoterm.R
import com.example.wanoterm.util.Logger

/**
 * SSH セッションが生きている間プロセスを延命する Foreground Service。
 *
 * 方針:
 * - SessionBundle が 1 つでも作られたら start、全部閉じたら stop
 * - 常駐通知を出してアプリを再起動しないでも状態を見せる
 * - 通知タップで MainActivity に戻れる（Termius と同じ UX）
 *
 * Android 13+ では POST_NOTIFICATIONS が必要、Android 14+ では foregroundServiceType の
 * 用途宣言が必要。manifest 側で両方済ませてある。
 */
class SshForegroundService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    createChannelIfNeeded()
    val notification = buildNotification()
    startForeground(NOTIFICATION_ID, notification)
    Logger.i("FgService", "started")
    return START_STICKY
  }

  override fun onDestroy() {
    Logger.i("FgService", "destroyed")
    super.onDestroy()
  }

  private fun createChannelIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val ch =
        NotificationChannel(
            CHANNEL_ID,
            "SSH Session",
            NotificationManager.IMPORTANCE_LOW,
        )
    ch.setShowBadge(false)
    nm.createNotificationChannel(ch)
  }

  private fun buildNotification(): Notification {
    val intent =
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val pi =
        PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("wanoterm SSH 実行中")
        .setContentText("タップして戻る")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(pi)
        .build()
  }

  companion object {
    private const val CHANNEL_ID = "wanoterm_ssh"
    private const val NOTIFICATION_ID = 1

    fun start(context: Context) {
      val intent = Intent(context, SshForegroundService::class.java)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, SshForegroundService::class.java)
      context.stopService(intent)
    }
  }
}
