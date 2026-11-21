package com.reu.features.rebuilder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
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
import com.reu.features.ui.ReuRoute
import com.reu.features.ui.ReuSectionCard

@Composable
fun ProjectRebuilderScreen(
  state: ReuUiState,
  onRebuildProject: (ProjectEntity) -> Unit,
  onNavigate: (ReuRoute) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  val project = state.activeProject
  val report = state.lastRebuildReport

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("project_rebuilder_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "مولّد مشروع يونيتي المعاد بناؤه" else "Unity Project Rebuilder",
        subtitle = project?.displayName ?: strings.noProjectSelected,
        icon = Icons.Default.Build,
      ) {
        Text(
          text =
            "Target Layout: RecoveredProject/ (Assets/{Scripts,Scenes,Prefabs,Materials,Textures,Models,Audio,Animations,Shaders,ScriptableObjects}, Packages/manifest.json, ProjectSettings/, REU_Report/)",
          fontFamily = mono,
          style = MaterialTheme.typography.labelSmall,
          color = ReuPalette.TextMuted,
        )
        Spacer(Modifier.height(10.dp))
        if (project == null) {
          Button(onClick = { onNavigate(ReuRoute.Importer) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text(strings.actionImportGame)
          }
        } else {
          Button(
            onClick = { onRebuildProject(project) },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("rebuild_project_button"),
          ) {
            Text(strings.actionRebuildProject)
          }
        }
      }
    }

    if (report != null) {
      item {
        ReuSectionCard(
          title = if (report.validationPassed) "Reconstruction Validated" else "Reconstruction Completed with Warnings",
          subtitle = report.outputDirectory,
          icon = Icons.Default.CheckCircle,
        ) {
          Text("Scripts Written: ${report.scriptsWritten}", fontFamily = mono, style = MaterialTheme.typography.bodySmall, color = ReuPalette.AmberAccent)
          Text("Assets Copied: ${report.assetsCopied}", fontFamily = mono, style = MaterialTheme.typography.bodySmall, color = ReuPalette.EmeraldAccent)
          Text("Missing / Unextracted Assets: ${report.missingOrPlaceholderCount}", fontFamily = mono, style = MaterialTheme.typography.bodySmall, color = ReuPalette.CrimsonAccent)
          Spacer(Modifier.height(6.dp))
          report.validationNotes.forEach { note ->
            Text("• $note", style = MaterialTheme.typography.bodySmall, color = ReuPalette.TextMuted)
          }
          Spacer(Modifier.height(10.dp))
          Button(
            onClick = { onNavigate(ReuRoute.ExportCenter) },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
          ) {
            Text(strings.navExport)
          }
        }
      }
    }
  }
}
