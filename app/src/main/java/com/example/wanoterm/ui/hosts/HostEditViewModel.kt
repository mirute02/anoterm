package com.example.wanoterm.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.data.SecretInput
import com.example.wanoterm.data.db.AuthMethod
import com.example.wanoterm.data.db.HostEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HostEditUiState(
    val id: Long? = null,
    val label: String = "",
    val address: String = "",
    val port: String = "22",
    val username: String = "",
    val auth: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val keyBytes: ByteArray? = null,
    val keyFileName: String? = null,
    val keyPassphrase: String = "",
    val useTmux: Boolean = false,
    val tmuxSession: String = "wanoterm",
    val isBusy: Boolean = false,
    /**
     * 既存ホストを編集中で、元の認証情報（DB の auth + secret_id）がそのまま残っている場合 true。
     * auth 方式を切り替えたり、パスワード・鍵を再入力すると false に落ちる。
     * true のまま保存すれば secret を作り直さず既存を流用する。
     */
    val preserveSecret: Boolean = false,
    /** 編集を開いた時点での元の auth 方式（preserveSecret の戻り先判定用） */
    val originalAuth: AuthMethod? = null,
) {
  fun isValid(): Boolean {
    if (label.isBlank() || address.isBlank() || username.isBlank()) return false
    val p = port.toIntOrNull() ?: return false
    if (p !in 1..65535) return false
    // 既存 secret をそのまま使うなら credentials の再入力は不要。
    if (preserveSecret && auth == originalAuth) return true
    return when (auth) {
      AuthMethod.PASSWORD -> password.isNotEmpty()
      AuthMethod.PRIVATE_KEY -> keyBytes != null
    }
  }
}

class HostEditViewModel(
    private val app: WanotermApp,
    private val existingId: Long?,
) : ViewModel() {

  private val _state = MutableStateFlow(HostEditUiState(id = existingId))
  val state: StateFlow<HostEditUiState> = _state.asStateFlow()

  init {
    if (existingId != null) {
      viewModelScope.launch {
        val h = app.database.hostDao().findById(existingId) ?: return@launch
        _state.value =
            _state.value.copy(
                id = h.id,
                label = h.label,
                address = h.address,
                port = h.port.toString(),
                username = h.username,
                auth = h.auth,
                useTmux = h.useTmux,
                tmuxSession = h.tmuxSession,
                // 既存 secret をそのまま流用できる状態でスタート。
                // auth 切替 / 明示的な credentials 入力があれば onChangeAuth / setKey /
                // password onValueChange が preserveSecret=false に倒す。
                preserveSecret = true,
                originalAuth = h.auth,
            )
      }
    }
  }

  fun update(transform: (HostEditUiState) -> HostEditUiState) {
    _state.value = transform(_state.value)
  }

  fun setKey(bytes: ByteArray, fileName: String) {
    // 鍵が差し替わったので既存 secret の流用を中止。
    update { it.copy(keyBytes = bytes, keyFileName = fileName, preserveSecret = false) }
  }

  fun save(onDone: () -> Unit) {
    val s = _state.value
    if (!s.isValid() || s.isBusy) return
    _state.value = s.copy(isBusy = true)
    viewModelScope.launch {
      if (s.preserveSecret && s.auth == s.originalAuth && s.id != null) {
        // 認証情報を一切変えない編集（label / tmux 切替など）。既存 secret_id を温存する。
        app.hostRepository.upsertMetadata(
            id = s.id,
            label = s.label,
            address = s.address,
            port = s.port.toInt(),
            username = s.username,
            useTmux = s.useTmux,
            tmuxSession = s.tmuxSession,
        )
      } else {
        val secret: SecretInput =
            when (s.auth) {
              AuthMethod.PASSWORD -> SecretInput.Password(s.password)
              AuthMethod.PRIVATE_KEY ->
                  SecretInput.PrivateKey(s.keyBytes!!, s.keyPassphrase.ifEmpty { null })
            }
        app.hostRepository.upsert(
            label = s.label,
            address = s.address,
            port = s.port.toInt(),
            username = s.username,
            auth = s.auth,
            secret = secret,
            useTmux = s.useTmux,
            tmuxSession = s.tmuxSession,
            existingId = s.id,
        )
      }
      _state.value = s.copy(isBusy = false)
      onDone()
    }
  }

  fun deleteSelf(onDone: () -> Unit) {
    val id = existingId ?: return
    viewModelScope.launch {
      val host = app.database.hostDao().findById(id) ?: return@launch
      app.hostRepository.delete(host)
      onDone()
    }
  }

  companion object {
    fun factory(hostId: Long?): ViewModelProvider.Factory =
        viewModelFactory { initializer { HostEditViewModel(WanotermApp.get(), hostId) } }
  }
}

@Suppress("unused") private fun HostEntity.neverUsed() = Unit
