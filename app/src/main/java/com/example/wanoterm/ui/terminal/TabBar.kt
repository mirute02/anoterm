package com.example.wanoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun TabBar(
    tabs: List<String>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  if (tabs.isEmpty()) return
  Row(
      modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    for (t in tabs) {
      val selected = t == activeTabId
      Row(
          modifier =
              Modifier.clip(RoundedCornerShape(6.dp))
                  .background(
                      if (selected) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
                  )
                  .clickable { onSelect(t) }
                  .padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = shortLabel(t),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { onClose(t) }, modifier = Modifier.height(28.dp)) {
          Icon(
              Icons.Filled.Close,
              contentDescription = null,
              modifier = Modifier.height(16.dp),
          )
        }
      }
    }
  }
}

private fun shortLabel(tabId: String): String =
    when {
      tabId.startsWith("loopback") -> "loopback"
      tabId.startsWith("host:") -> tabId.removePrefix("host:").substringBefore(":").take(12)
      else -> tabId.take(12)
    }
