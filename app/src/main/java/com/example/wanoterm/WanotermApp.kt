package com.example.wanoterm

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.wanoterm.data.HostRepository
import com.example.wanoterm.data.db.AppDatabase
import com.example.wanoterm.data.prefs.AppPrefs
import com.example.wanoterm.data.secrets.SecretStore
import com.example.wanoterm.ssh.SshSessionManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class WanotermApp : Application() {

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

    // Android 組み込みの BouncyCastle は X25519 / CHACHA20 などが未提供のため、
    // フル機能の BC を手元の jar で差し替える。sshj が "BC" プロバイダを要求したときに
    // これが見つかるようにする。
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 1)

    prefs = AppPrefs(applicationContext)
    database = AppDatabase.create(applicationContext)
    secretStore = SecretStore(applicationContext)
    sessionManager = SshSessionManager()
    hostRepository = HostRepository(database.hostDao(), secretStore)

    // Apply persisted locale before any Activity is created.
    AppCompatDelegate.setApplicationLocales(prefs.localeList())
  }

  override fun onTerminate() {
    sessionManager.closeAll()
    super.onTerminate()
  }

  companion object {
    @Volatile
    private var instance: WanotermApp? = null

    fun get(): WanotermApp =
        instance ?: error("WanotermApp not yet created")
  }
}
