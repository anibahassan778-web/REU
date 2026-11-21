package com.reu.features.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reu.core.database.ProjectEntity
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard

@Composable
fun ExportCenterScreen(
  state: ReuUiState,
  onExportProjectZip: (ProjectEntity) -> Unit,
  onSaveZipToSaf: (Uri) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  val project = state.activeProject

  val saveLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
      if (uri != null) onSaveZipToSaf(uri)
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("export_center_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "مركز التصدير والحفظ" else "Export Center",
        subtitle = project?.displayName ?: strings.noProjectSelected,
        icon = Icons.Default.Archive,
      ) {
        if (project == null) {
          Text("Select or import a project before exporting.", color = ReuPalette.TextMuted)
        } else {
          Button(
            onClick = { onExportProjectZip(project) },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("generate_export_zip_button"),
          ) {
            Text(if (strings.isArabic) "توليد حزمة RecoveredProject.zip" else "Package RecoveredProject.zip")
          }
          if (state.lastExportedZipPath != null) {
            Spacer(Modifier.height(10.dp))
            Text("Archive: ${state.lastExportedZipPath}", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.EmeraldAccent)
            Text("SHA-256: ${state.lastExportedZipSha256}", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.CyanAccent)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
              onClick = { saveLauncher.launch("${project.displayName}_RecoveredProject.zip") },
              modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("save_zip_saf_button"),
            ) {
              Text(if (strings.isArabic) "حفظ الحزمة عبر مدير الملفات (SAF)" else "Save ZIP to Device Storage (SAF)")
            }
          }
        }
      }
    }

    item {
      ReuSectionCard(
        title = if (strings.isArabic) "ملاحظة التصدير والترخيص" else "Export Format Support",
        icon = Icons.Default.SaveAlt,
      ) {
        Text(
          text =
            "• Textures: PNG, JPG, TGA\n" +
              "• Audio: WAV, OGG, FSB5 container\n" +
              "• 3D Meshes: Wavefront OBJ, glTF 2.0 (Blender-compatible; FBX omitted without proprietary SDK)\n" +
              "• Reports: analysis.json, recovery.json, missing_files.json, compatibility.json, recovery_log.txt",
          style = MaterialTheme.typography.bodySmall,
          color = ReuPalette.TextMuted,
        )
      }
    }
  }
}
