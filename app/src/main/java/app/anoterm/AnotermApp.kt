package app.anoterm

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import app.anoterm.data.HostRepository
import app.anoterm.data.db.AppDatabase
import app.anoterm.data.prefs.AppPrefs
import app.anoterm.data.secrets.SecretStore
import app.anoterm.ssh.SshSessionManager
import app.anoterm.util.Logger
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
