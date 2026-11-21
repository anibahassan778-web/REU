package com.reu.features.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard

@Composable
fun ToolManagerScreen(
  state: ReuUiState,
  onInstallTool: (String) -> Unit,
  onUninstallTool: (String) -> Unit,
  onInstallOfflineZip: (Uri, String?) -> Unit,
  onCheckRemoteCatalog: (String) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  var expectedSha by remember { mutableStateOf("") }
  var manifestUrl by remember { mutableStateOf("https://example.invalid/reu-modules/manifest.json") }

  val offlineLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) onInstallOfflineZip(uri, expectedSha.ifBlank { null })
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("tool_manager_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "التثبيت المحلي والتحقق من SHA-256" else "Offline Module Package & Hybrid Sync",
        subtitle = "Verified declarative schema packs (Zero DCL / Play Policy Compliant)",
        icon = Icons.Default.FolderZip,
      ) {
        OutlinedTextField(
          value = expectedSha,
          onValueChange = { expectedSha = it },
          label = { Text(if (strings.isArabic) "بصمة SHA-256 المتوقعة (اختياري)" else "Expected SHA-256 Checksum (Optional)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { offlineLauncher.launch(arrayOf("application/zip", "*/*")) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("install_offline_module_button"),
        ) {
          Text(if (strings.isArabic) "تثبيت حزمة أداة محلية (.zip)" else "Install Offline Module Package (.zip)")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = manifestUrl,
          onValueChange = { manifestUrl = it },
          label = { Text("Remote Signed Catalog URL") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
          onClick = { onCheckRemoteCatalog(manifestUrl) },
          modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
          Icon(Icons.Default.CloudSync, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(if (strings.isArabic) "التحقق من تحديثات الفهرس" else "Check Manifest Updates")
        }
      }
    }

    items(state.tools, key = { it.id }) { tool ->
      val hasUpdate = tool.isInstalled && tool.version != tool.availableVersion
      ReuSectionCard(
        title = tool.name,
        subtitle = "ID: ${tool.id} • Installed: ${if (tool.isInstalled) "v${tool.version}" else "Not installed"} • Latest: v${tool.availableVersion}",
        icon = Icons.Default.Extension,
      ) {
        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = ReuPalette.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("Arch: ${tool.supportedArchs} • Min API: ${tool.minAndroidApi}", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.TextMuted)
        Text("License: ${tool.licenseName}", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.CyanAccent)
        Text("SHA-256: ${tool.sha256Checksum.take(24)}...", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.TextMuted)
        Text("Note: ${tool.compatibilityNote}", style = MaterialTheme.typography.labelSmall, color = ReuPalette.AmberAccent)
        Spacer(Modifier.height(10.dp))
        if (tool.isOptional) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!tool.isInstalled || hasUpdate) {
              Button(
                onClick = { onInstallTool(tool.id) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("tool_install_${tool.id}"),
              ) {
                Text(if (hasUpdate) "Update" else "Install")
              }
            }
            if (tool.isInstalled) {
              Button(
                onClick = { onUninstallTool(tool.id) },
                colors = ButtonDefaults.buttonColors(containerColor = ReuPalette.CrimsonAccent),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("tool_uninstall_${tool.id}"),
              ) {
                Text("Uninstall")
              }
            }
          }
        } else {
          Text("Core Built-In Engine Module", style = MaterialTheme.typography.labelMedium, color = ReuPalette.EmeraldAccent)
        }
      }
    }
  }
}
