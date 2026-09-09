package app.anoterm.ssh

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
import app.anoterm.AnotermApp
import app.anoterm.MainActivity
import app.anoterm.R
import app.anoterm.util.Logger

/**
 * SSH セッションが生きている間プロセスを延命する Foreground Service。
 *
 * 方針:
 * - SessionBundle が 1 つでも作られたら start、全部閉じたら stop
 * - 常駐通知を出してアプリを再起動しないでも状態を見せる
 * - 通知タップで MainActivity に戻れる（Termius と同じ UX）
 * - 通知の「すべて切断」アクションで全セッションを閉じられる
 *
 * foregroundServiceType について:
 * 以前は `dataSync` を宣言していたが、targetSdk 35+ では dataSync FGS は 24 時間あたり
 * 合計 6 時間しか動けず、超過すると onTimeout → 数秒以内に stopSelf しないと
 * ForegroundServiceDidNotStopInTimeException でアプリがクラッシュする。
 * SSH セッションの常時維持は Google のガイドでも `specialUse` の想定ユースケースなので
 * specialUse に変更してタイムアウトを回避する（manifest で用途を宣言済み）。
 * 万一の将来変更に備え onTimeout の安全弁も残す。
 */
class SshForegroundService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_DISCONNECT_ALL) {
      Logger.i("FgService", "disconnect all requested")
      runCatching { AnotermApp.get().sessionManager.closeAll() }
      stopSelf()
      return START_NOT_STICKY
    }

    createChannelIfNeeded()

    // 既に生きているセッションが 1 つも無ければ延命する意味が無い。プロセスがクラッシュ / LMK で
    // 死んで START_NOT_STICKY 前提の再配送や空 start が来た場合に、通知だけ残るゾンビ FGS を防ぐ。
    val activeCount = runCatching { AnotermApp.get().sessionManager.activeTabIds().size }.getOrDefault(0)
    startForeground(NOTIFICATION_ID, buildNotification(activeCount))
    if (activeCount == 0) {
      Logger.i("FgService", "no active sessions — stopping")
      stopSelf()
      return START_NOT_STICKY
    }

    Logger.i("FgService", "started activeSessions=$activeCount")
    // START_STICKY だとプロセス死後にシステムが null intent で再起動し、セッションが全滅した
    // 新プロセスで通知だけ生き続ける。再配送は不要なので START_NOT_STICKY。
    return START_NOT_STICKY
  }

  /**
   * 時間制限付き FGS タイプ（現在の specialUse では呼ばれないが将来の保険）でタイムアウトした際、
   * 猶予内に stopForeground しないとクラッシュするため、ここで安全に畳む。
   */
  override fun onTimeout(startId: Int) {
    Logger.w("FgService", "onTimeout — closing all sessions to avoid forced crash")
    runCatching { AnotermApp.get().sessionManager.closeAll() }
    stopSelf()
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

  private fun buildNotification(activeCount: Int): Notification {
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
    val disconnectPi =
        PendingIntent.getService(
            this,
            1,
            Intent(this, SshForegroundService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    // 英語は単数と複数で語形が変わる。日本語は変わらない。数え方は言語ごとの
    // 問題なので plurals に任せ、ここで if を書かない。
    val text = resources.getQuantityString(R.plurals.notification_sessions, activeCount, activeCount)
    return NotificationCompat.Builder(this, CHANNEL_ID)
        // Android 8+ は status bar の小アイコンを単色 alpha で描画するので mipmap/ic_launcher は
        // 白い角丸四角に潰れてしまう。専用のモノクロ vector drawable を指す。
        .setSmallIcon(R.drawable.ic_notification_terminal)
        .setContentTitle("AnoTerm")
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(pi)
        .addAction(0, getString(R.string.notification_disconnect_all), disconnectPi)
        .build()
  }

  companion object {
    private const val CHANNEL_ID = "anoterm_ssh"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_DISCONNECT_ALL = "app.anoterm.action.DISCONNECT_ALL"

    /**
     * FGS を起動 / 通知を最新化する。セッション数が変わったときにも呼べば通知本文が更新される。
     * バックグラウンドから startForegroundService を呼ぶと Android 12+ では
     * ForegroundServiceStartNotAllowedException になり得るため、握って落とさない。
     * 起動し損ねても MainActivity.onStart が復帰時に再試行する（そのときは前面なので必ず成功）。
     */
    fun start(context: Context) {
      val intent = Intent(context, SshForegroundService::class.java)
      runCatching { ContextCompat.startForegroundService(context, intent) }
          .onFailure { Logger.w("FgService", "start refused (will retry on foreground)", it) }
    }

    fun stop(context: Context) {
      val intent = Intent(context, SshForegroundService::class.java)
      runCatching { context.stopService(intent) }
    }
  }
}
