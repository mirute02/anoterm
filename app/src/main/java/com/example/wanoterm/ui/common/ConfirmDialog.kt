package com.example.wanoterm.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "OK",
    dismissLabel: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(title) },
      text = { Text(message) },
      confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
      dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
  )
}
