package app.anoterm.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.anoterm.AnotermApp
import app.anoterm.data.SecretInput
import app.anoterm.data.db.AuthMethod
import app.anoterm.data.db.HostEntity
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
    val tmuxSession: String = "anoterm",
    val isBusy: Boolean = false,
    /**
     * 既存ホストを編集中で、元の認証情報（DB の auth + secret_id）がそのまま残っている場合 true。
     * auth 方式を切り替えたり、パスワード・鍵を再入力すると false に落ちる。
     * true のまま保存すれば secret を作り直さず既存を流用する。
     */
    val preserveSecret: Boolean = false,
    /** 編集を開いた時点での元の auth 方式（preserveSecret の戻り先判定用） */
    val originalAuth: AuthMethod? = null,
    /** 編集を開いた時点での tmux attach 設定。attach 先を変えた保存は新規行にする。 */
    val originalUseTmux: Boolean? = null,
    val originalTmuxSession: String? = null,
    /** 保存直前に検知した重複ホスト。UI 側で「上書き / キャンセル」ダイアログを出す。 */
    val duplicateHost: HostEntity? = null,
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
    private val app: AnotermApp,
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
                originalUseTmux = h.useTmux,
                originalTmuxSession = h.tmuxSession,
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

  /**
   * 保存前処理。label をトリムし、他の既存ホストと接続先 + tmux attach 先が重複していたら
   * duplicateHost を立てて UI に確認ダイアログを促す。重複がなければ performSave に進む。
   */
  fun save(onDone: () -> Unit) {
    val raw = _state.value
    if (!raw.isValid() || raw.isBusy) return
    val s =
        raw.copy(
            label = raw.label.trim(),
            address = raw.address.trim(),
            username = raw.username.trim(),
            tmuxSession = raw.tmuxSession.trim(),
        )
    _state.value = s.copy(isBusy = true)
    viewModelScope.launch {
      val duplicate =
          app.hostRepository.findDuplicate(
              label = s.label,
              address = s.address,
              port = s.port.toIntOrNull() ?: 22,
              username = s.username,
              useTmux = s.useTmux,
              tmuxSession = normalizedTmuxSession(s.tmuxSession),
              excludeId = if (s.shouldInsertAsNewTmuxTarget()) null else s.id,
          )
      if (duplicate != null) {
        // UI 側に判断を委ねる。isBusy は解除し、duplicateHost を立てる。
        _state.value = s.copy(isBusy = false, duplicateHost = duplicate)
        return@launch
      }
      performSave(
          s,
          overwriteId = if (s.shouldInsertAsNewTmuxTarget()) null else s.id,
          onDone = onDone,
      )
    }
  }

  /** 重複ダイアログで「上書き」を選ばれたとき、既存ホストの id を使って upsert する。 */
  fun confirmOverwrite(onDone: () -> Unit) {
    val s = _state.value
    val target = s.duplicateHost ?: return
    _state.value = s.copy(isBusy = true, duplicateHost = null)
    viewModelScope.launch {
      performSave(s, overwriteId = target.id, onDone = onDone)
    }
  }

  fun dismissDuplicate() {
    _state.value = _state.value.copy(duplicateHost = null)
  }

  private suspend fun performSave(
      s: HostEditUiState,
      overwriteId: Long?,
      onDone: () -> Unit,
  ) {
    if (s.preserveSecret && s.auth == s.originalAuth && overwriteId != null) {
      // 認証情報を一切変えない編集（label / tmux 切替など）。既存 secret_id を温存する。
      app.hostRepository.upsertMetadata(
          id = overwriteId,
          label = s.label,
          address = s.address,
          port = s.port.toInt(),
          username = s.username,
          useTmux = s.useTmux,
          tmuxSession = s.tmuxSession,
      )
    } else if (s.preserveSecret && s.auth == s.originalAuth && overwriteId == null && s.id != null) {
      // tmux attach 先だけを別行として追加する場合。secret_id は共有せず複製する。
      app.hostRepository.duplicateWithMetadata(
          sourceId = s.id,
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
          existingId = overwriteId,
      )
    }
    // 現在の state を base にして isBusy だけ落とす。`s` は呼び出し時点のスナップショットで
    // confirmOverwrite 経路では duplicateHost が入っているため、`s.copy()` だと書き戻されて
    // ダイアログが再表示されてしまう（ユーザが「上書き」を押すたびに無限ループする原因だった）。
    _state.value = _state.value.copy(isBusy = false)
    onDone()
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
        viewModelFactory { initializer { HostEditViewModel(AnotermApp.get(), hostId) } }
  }
}

private fun normalizedTmuxSession(value: String): String = value.trim().ifBlank { "anoterm" }

private fun HostEditUiState.tmuxAttachTarget(): String? =
    if (useTmux) normalizedTmuxSession(tmuxSession) else null

private fun HostEditUiState.originalTmuxAttachTarget(): String? =
    if (originalUseTmux == true) normalizedTmuxSession(originalTmuxSession.orEmpty()) else null

private fun HostEditUiState.shouldInsertAsNewTmuxTarget(): Boolean =
    id != null && useTmux && originalUseTmux != null && tmuxAttachTarget() != originalTmuxAttachTarget()

@Suppress("unused") private fun HostEntity.neverUsed() = Unit
