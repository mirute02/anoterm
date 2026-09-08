package app.anoterm

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HostList : NavKey

@Serializable data class HostEdit(val hostId: Long? = null) : NavKey

@Serializable data class Terminal(val tabId: String) : NavKey

@Serializable data object Settings : NavKey

@Serializable data object KnownHosts : NavKey

@Serializable data object CustomShortcuts : NavKey

@Serializable data object SshKeyHelp : NavKey

@Serializable data object SshKeyGen : NavKey

@Serializable data object SshKeyList : NavKey

