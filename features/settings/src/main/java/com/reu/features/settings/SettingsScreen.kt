package com.reu.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reu.core.common.AppLanguage
import com.reu.core.common.FormatUtils
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  state: ReuUiState,
  onSetLanguage: (AppLanguage) -> Unit,
  onSetOfflineFirst: (Boolean) -> Unit,
  onSetBufferKb: (Int) -> Unit,
  onClearTemp: () -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("settings_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "اللغة والاتجاه (RTL / LTR)" else "Language & Direction (RTL / LTR)",
        icon = Icons.Default.Language,
      ) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          FilterChip(
            selected = state.settings.language == AppLanguage.SYSTEM,
            onClick = { onSetLanguage(AppLanguage.SYSTEM) },
            label = { Text("System") },
            modifier = Modifier.testTag("lang_system"),
          )
          FilterChip(
            selected = state.settings.language == AppLanguage.ENGLISH,
            onClick = { onSetLanguage(AppLanguage.ENGLISH) },
            label = { Text("English (LTR)") },
            modifier = Modifier.testTag("lang_english"),
          )
          FilterChip(
            selected = state.settings.language == AppLanguage.ARABIC,
            onClick = { onSetLanguage(AppLanguage.ARABIC) },
            label = { Text("العربية (RTL)") },
            modifier = Modifier.testTag("lang_arabic"),
          )
        }
      }
    }

    item {
      ReuSectionCard(
        title = if (strings.isArabic) "الأداء والوضع المحلي" else "Performance & Offline-First Mode",
        icon = Icons.Default.Memory,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (strings.isArabic) "الوضع المحلي دون اتصال بالشبكة" else "Strict Offline-First Mode",
            modifier = Modifier.weight(1f),
          )
          Switch(
            checked = state.settings.offlineFirstMode,
            onCheckedChange = onSetOfflineFirst,
            modifier = Modifier.testTag("offline_mode_switch"),
          )
        }
        Spacer(Modifier.height(10.dp))
        Text("Streaming I/O Buffer: ${state.settings.streamingBufferKb} KB", fontFamily = mono, style = MaterialTheme.typography.bodySmall)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          listOf(64, 256, 1024).forEach { kb ->
            FilterChip(
              selected = state.settings.streamingBufferKb == kb,
              onClick = { onSetBufferKb(kb) },
              label = { Text("${kb} KB") },
            )
          }
        }
        Spacer(Modifier.height(8.dp))
        Text("Engine: ${state.nativeEngineStatus}", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.CyanAccent)
      }
    }

    item {
      ReuSectionCard(
        title = if (strings.isArabic) "إدارة الذاكرة المؤقتة" else "Temporary Workspace Cleanup",
        icon = Icons.Default.CleaningServices,
      ) {
        Text("Current Temp Cache: ${FormatUtils.formatBytes(state.storageStats.tempBytes)}", fontFamily = mono, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = onClearTemp,
          modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("clear_temp_button"),
        ) {
          Text(if (strings.isArabic) "تنظيف الملفات المؤقتة" else "Clean Temporary Cache")
        }
      }
    }

    item {
      ReuSectionCard(
        title = if (strings.isArabic) "الاستخدام المصرح به وسياسة الأمان" else "Authorized Use & Security Policy",
        icon = Icons.Default.Security,
      ) {
        Text(
          text =
            "REU is engineered exclusively for analyzing authorized Unity builds, preservation, and debugging. " +
              "It contains zero DRM circumvention, anti-cheat bypass, or credential extraction mechanisms. " +
              "All archives are inspected with Zip-Slip path traversal protection and SHA-256 verification.",
          style = MaterialTheme.typography.bodySmall,
          color = ReuPalette.TextMuted,
        )
      }
    }

    item {
      ReuSectionCard(
        title = if (strings.isArabic) "تراخيص الطرف الثالث" else "Third-Party Licenses & Attribution",
        icon = Icons.Default.Gavel,
      ) {
        Text(
          text =
            "• AndroidX, Jetpack Compose, Room, WorkManager, Navigation (Apache-2.0)\n" +
              "• Kotlin & Kotlinx Serialization / Coroutines (Apache-2.0)\n" +
              "• OkHttp (Apache-2.0) • Coil (Apache-2.0)\n" +
              "• Clean-room binary format adapters compatible with UnityPy (MIT), Cpp2IL (MIT), Il2CppDumper (MIT), ILSpy (MIT), UABEA (MIT), AssetStudio (MIT), and Ghidra script.json (Apache-2.0).",
          style = MaterialTheme.typography.bodySmall,
          color = ReuPalette.TextMuted,
        )
      }
    }
  }
}
