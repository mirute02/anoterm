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
) {
  fun isValid(): Boolean {
    if (label.isBlank() || address.isBlank() || username.isBlank()) return false
    val p = port.toIntOrNull() ?: return false
    if (p !in 1..65535) return false
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
                // 既存の機密は再入力を要求（表示しない）
            )
      }
    }
  }

  fun update(transform: (HostEditUiState) -> HostEditUiState) {
    _state.value = transform(_state.value)
  }

  fun setKey(bytes: ByteArray, fileName: String) {
    update { it.copy(keyBytes = bytes, keyFileName = fileName) }
  }

  fun save(onDone: () -> Unit) {
    val s = _state.value
    if (!s.isValid() || s.isBusy) return
    _state.value = s.copy(isBusy = true)
    viewModelScope.launch {
      val secret: SecretInput =
          when (s.auth) {
            AuthMethod.PASSWORD -> SecretInput.Password(s.password)
            AuthMethod.PRIVATE_KEY -> SecretInput.PrivateKey(s.keyBytes!!, s.keyPassphrase.ifEmpty { null })
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
