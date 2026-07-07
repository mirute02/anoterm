package app.anoterm

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import app.anoterm.data.HostRepository
import app.anoterm.data.db.AppDatabase
import app.anoterm.data.prefs.AppPrefs
import app.anoterm.data.secrets.SecretStore
import app.anoterm.ssh.SshSessionManager
import app.anoterm.util.Logger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.Security

class AnotermApp : Application() {

  lateinit var prefs: AppPrefs
    private set
  lateinit var database: AppDatabase
    private set
  lateinit var secretStore: SecretStore
    private set
  lateinit var sessionManager: SshSessionManager
    private set
  lateinit var hostRepository: HostRepository
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this

    installCrashLogger()
    if (BuildConfig.DEBUG) enableStrictMode()

    // 前回プロセスがどう終わったか（CRASH / ANR / LOW_MEMORY / FGS timeout など）を起動時に
    // 記録する。テレメトリを持たない本アプリでは、フィールドで発生した「勝手に落ちた/消えた」の
    // 唯一の手掛かりになる。w レベルなので release ビルドでも logcat に残る。
    logLastExitReason()

    // Android 組み込みの BouncyCastle は X25519 / CHACHA20 などが未提供のため、
    // フル機能の BC を手元の jar で差し替える。sshj が "BC" プロバイダを要求したときに
    // これが見つかるようにする。
    // 多重起動ガード: 既に同じ org.bouncycastle.* の BC が挿さっていれば no-op。
    // Android 側の BC は `com.android.org.bouncycastle.*` なので型判定でうちの版と区別できる。
    if (Security.getProvider("BC") !is BouncyCastleProvider) {
      Security.removeProvider("BC")
      Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    prefs = AppPrefs(applicationContext)
    database = AppDatabase.create(applicationContext)
    secretStore = SecretStore(applicationContext)
    sessionManager = SshSessionManager(applicationContext)
    hostRepository = HostRepository(database.hostDao(), secretStore)

    // Apply persisted locale before any Activity is created.
    AppCompatDelegate.setApplicationLocales(prefs.localeList())
  }

  override fun onTerminate() {
    sessionManager.closeAll()
    super.onTerminate()
  }

  /**
   * 未捕捉例外を filesDir/crash/ に書き出してから既定ハンドラ（プロセス終了）へ委譲する。
   * テレメトリを持たないため、これがフィールドのクラッシュを事後に読める唯一の手段。
   * スタックトレースはクラッシュ地点のコード情報のみで、SSH のバイト内容など機密は含まない。
   */
  private fun installCrashLogger() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      runCatching {
        val dir = File(filesDir, "crash").apply { mkdirs() }
        // ファイル名は起動からの経過時間。Date.now は使わず単調増加の識別子で十分。
        val stamp = android.os.SystemClock.elapsedRealtimeNanos()
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        File(dir, "crash_$stamp.txt").writeText(
            "thread=${thread.name}\nversion=${BuildConfig.VERSION_NAME}\n\n$sw",
        )
        // 古いログが溜まり続けないよう最新 20 件に丸める。
        dir.listFiles()?.sortedBy { it.name }?.dropLast(20)?.forEach { it.delete() }
      }
      previous?.uncaughtException(thread, throwable)
    }
  }

  private fun enableStrictMode() {
    // 今回の調査で見つけた「メインスレッドで socket close」「stream の close 漏れ」の類を
    // 開発中に自動検出する。penaltyLog なのでクラッシュはさせず logcat に出すだけ。
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder().detectNetwork().detectCustomSlowCalls().penaltyLog().build(),
    )
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build(),
    )
  }

  private fun logLastExitReason() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
      val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      // 直近 1 件のみ。reason は ApplicationExitInfo.REASON_* の定数値。
      am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let { info ->
        Logger.w(
            "ExitInfo",
            "last exit reason=${info.reason} status=${info.status} importance=${info.importance} desc=${info.description}",
        )
      }
    }
  }

  companion object {
    @Volatile
    private var instance: AnotermApp? = null

    fun get(): AnotermApp =
        instance ?: error("AnotermApp not yet created")
  }
}
