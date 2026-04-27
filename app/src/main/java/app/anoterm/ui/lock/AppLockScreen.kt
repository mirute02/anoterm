package app.anoterm.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.content.ContextWrapper
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.anoterm.R
import app.anoterm.util.Logger

@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
  val ctx = LocalContext.current
  val activity = remember(ctx) { ctx.findActivity() as? FragmentActivity }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var attempted by remember { mutableStateOf(false) }

  LaunchedEffect(activity) {
    if (activity == null) return@LaunchedEffect
    if (attempted) return@LaunchedEffect
    attempted = true
    tryBiometric(activity, onSuccess = onUnlocked, onFail = { errorMessage = it })
  }

  Column(
      modifier = Modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
          if (activity != null) tryBiometric(activity, onSuccess = onUnlocked, onFail = { errorMessage = it })
          else onUnlocked()
        },
    ) { Text(stringResource(R.string.lock_unlock)) }
  }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
  var ctx: android.content.Context = this
  while (ctx is ContextWrapper) {
    if (ctx is android.app.Activity) return ctx
    ctx = ctx.baseContext
  }
  return null
}

private fun tryBiometric(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onFail: (String) -> Unit,
) {
  val manager = BiometricManager.from(activity)
  val canAuth =
      manager.canAuthenticate(
          BiometricManager.Authenticators.BIOMETRIC_STRONG or
              BiometricManager.Authenticators.DEVICE_CREDENTIAL,
      )
  if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
    // 利用できないなら即 unlock（Play Store 向けは後で堅くする）
    Logger.w("Lock", "biometric not available: $canAuth — skipping")
    onSuccess()
    return
  }
  val prompt =
      BiometricPrompt(
          activity,
          ContextCompat.getMainExecutor(activity),
          object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
              onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
              when (errorCode) {
                BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onFail(errString.toString())
                BiometricPrompt.ERROR_LOCKOUT,
                BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> onFail("Locked out")
                else -> onFail(errString.toString())
              }
            }
          },
      )
  val info =
      BiometricPrompt.PromptInfo.Builder()
          .setTitle(activity.getString(R.string.lock_biometric_title))
          .setSubtitle(activity.getString(R.string.lock_biometric_subtitle))
          .setAllowedAuthenticators(
              BiometricManager.Authenticators.BIOMETRIC_STRONG or
                  BiometricManager.Authenticators.DEVICE_CREDENTIAL,
          )
          .build()
  prompt.authenticate(info)
}
