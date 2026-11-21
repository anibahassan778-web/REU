package com.reu.features.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reu.core.common.FormatUtils
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuRoute
import com.reu.features.ui.ReuSectionCard

@Composable
fun InspectorScreen(
  state: ReuUiState,
  onNavigate: (ReuRoute) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  val project = state.activeProject
  val report = state.detectionReport

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("inspector_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (project == null) {
      item {
        ReuSectionCard(title = strings.noProjectSelected, icon = Icons.Default.Info) {
          Text(
            text = if (strings.isArabic) "استورد لعبة أولاً لفحص بنية يونيتي الخاصة بها." else "Import a Unity build first to inspect its binaries, backend, and asset layout.",
            color = ReuPalette.TextMuted,
          )
          Spacer(Modifier.height(10.dp))
          Button(onClick = { onNavigate(ReuRoute.Importer) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text(strings.actionImportGame)
          }
        }
      }
      return@LazyColumn
    }

    item {
      ReuSectionCard(
        title = project.displayName,
        subtitle = project.packageName ?: "Package ID not detected",
        icon = Icons.Default.DeveloperBoard,
      ) {
        KeyValueRow("Source Type", project.sourceType.name, mono)
        KeyValueRow("Source Size", FormatUtils.formatBytes(project.sourceSizeBytes), mono)
        KeyValueRow("SHA-256", project.sourceSha256, mono)
        KeyValueRow("Scripting Backend", project.scriptingBackend.name, mono)
        KeyValueRow("Unity Version", project.unityVersion ?: "Unconfirmed (No header evidence)", mono)
        if (!project.unityVersionEvidence.isNullOrBlank()) {
          KeyValueRow("Version Evidence", project.unityVersionEvidence!!, mono)
        }
      }
    }

    if (report != null) {
      item {
        ReuSectionCard(
          title = if (strings.isArabic) "تقرير الكشف الهندسي" else "Unity Build Detection Report",
          icon = Icons.Default.Build,
        ) {
          KeyValueRow("Is Unity Build", report.isUnityBuild.toString(), mono)
          KeyValueRow("Architectures", report.architectures.joinToString(", ").ifBlank { "None detected" }, mono)
          KeyValueRow("Native Libs (.so)", report.nativeLibraries.size.toString(), mono)
          KeyValueRow("Managed DLLs", report.managedAssemblies.size.toString(), mono)
          KeyValueRow("Metadata Files", report.metadataFiles.size.toString(), mono)
          KeyValueRow("Asset Files", report.assetFiles.size.toString(), mono)
          KeyValueRow("AssetBundles", report.assetBundles.size.toString(), mono)
          KeyValueRow("Level Files", report.levelFiles.size.toString(), mono)
          KeyValueRow("StreamingAssets", report.streamingAssets.size.toString(), mono)
          report.detectionNotes.forEach { note ->
            Spacer(Modifier.height(4.dp))
            Text("• $note", style = MaterialTheme.typography.bodySmall, color = ReuPalette.AmberAccent)
          }
        }
      }

      item {
        ReuSectionCard(
          title = if (strings.isArabic) "الملفات المكتشفة" else "Discovered Unity Artifacts",
          icon = Icons.Default.FolderSpecial,
        ) {
          val listed =
            (report.metadataFiles + report.nativeLibraries + report.managedAssemblies + report.assetFiles + report.assetBundles + report.levelFiles)
              .distinct()
              .take(80)
          if (listed.isEmpty()) {
            Text("No Unity-specific files found inside container.", color = ReuPalette.TextMuted)
          } else {
            listed.forEach { path ->
              Text(path, fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.TextPrimary)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun KeyValueRow(label: String, value: String, mono: androidx.compose.ui.text.font.FontFamily) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = ReuPalette.TextMuted)
    Spacer(Modifier.weight(0.1f))
    Text(value, fontFamily = mono, style = MaterialTheme.typography.bodySmall, color = ReuPalette.TextPrimary)
  }
}
