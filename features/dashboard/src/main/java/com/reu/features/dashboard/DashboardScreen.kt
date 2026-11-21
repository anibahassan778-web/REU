package com.reu.features.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reu.core.common.FormatUtils
import com.reu.core.common.JobStatus
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.MetricStatTile
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuRoute
import com.reu.features.ui.ReuSectionCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
  state: ReuUiState,
  onNavigate: (ReuRoute) -> Unit,
  onSelectProject: (String) -> Unit,
  onDeleteProject: (String) -> Unit,
  onCancelJob: (String) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  val activeJobs = state.jobs.filter { it.status == JobStatus.RUNNING || it.status == JobStatus.PAUSED }
  val completedJobs = state.jobs.count { it.status == JobStatus.COMPLETED }
  val installedTools = state.tools.count { it.isInstalled }
  val updatesAvailable = state.tools.count { it.isInstalled && it.version != it.availableVersion }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("dashboard_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Active Project Banner
    item {
      ReuSectionCard(
        title = state.activeProject?.displayName ?: strings.noProjectSelected,
        subtitle =
          state.activeProject?.let {
            "${it.sourceType.name} • ${it.scriptingBackend.name} • ${it.unityVersion ?: "Unity (Unconfirmed Version)"}"
          } ?: if (strings.isArabic) "استورد ملف APK أو XAPK أو APKS أو ZIP أو مجلد لعبة للبدء." else "Import an authorized APK, XAPK, APKS, ZIP, or Unity directory to begin.",
        icon = Icons.Default.Memory,
      ) {
        if (state.activeProject != null) {
          Text(
            text = "SHA-256: ${state.activeProject.sourceSha256}",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = ReuPalette.CyanAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = { onNavigate(ReuRoute.Inspector) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dash_open_inspector"),
            ) {
              Text(strings.navInspector)
            }
            FilledTonalButton(
              onClick = { onNavigate(ReuRoute.Analysis) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dash_open_analysis"),
            ) {
              Text(strings.navAnalysis)
            }
          }
        } else {
          Button(
            onClick = { onNavigate(ReuRoute.Importer) },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dash_import_cta"),
          ) {
            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(strings.actionImportGame)
          }
        }
      }
    }

    // Telemetry Overview Grid
    item {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricStatTile(
          label = if (strings.isArabic) "الألعاب المستوردة" else "Imported Games",
          value = state.projects.size.toString(),
          accent = ReuPalette.CyanAccent,
          modifier = Modifier.weight(1f),
        )
        MetricStatTile(
          label = if (strings.isArabic) "المهام النشطة" else "Active Tasks",
          value = activeJobs.size.toString(),
          accent = ReuPalette.AmberAccent,
          modifier = Modifier.weight(1f),
        )
      }
      Spacer(Modifier.height(10.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricStatTile(
          label = if (strings.isArabic) "عمليات مكتملة" else "Completed Ops",
          value = completedJobs.toString(),
          accent = ReuPalette.EmeraldAccent,
          modifier = Modifier.weight(1f),
        )
        MetricStatTile(
          label = if (strings.isArabic) "الأدوات / التحديثات" else "Tools / Updates",
          value = "$installedTools / $updatesAvailable",
          accent = ReuPalette.TextPrimary,
          modifier = Modifier.weight(1f),
        )
      }
    }

    // Quick Actions
    item {
      ReuSectionCard(
        title = strings.quickActionsLabel,
        icon = Icons.Default.PlayArrow,
      ) {
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          QuickActionChip(strings.actionImportGame, Icons.Default.FileUpload, "qa_import") {
            onNavigate(ReuRoute.Importer)
          }
          QuickActionChip(strings.actionAnalyzeGame, Icons.Default.Memory, "qa_analyze") {
            onNavigate(ReuRoute.Analysis)
          }
          QuickActionChip(strings.actionBrowseAssets, Icons.Default.PermMedia, "qa_assets") {
            onNavigate(ReuRoute.AssetBrowser)
          }
          QuickActionChip(strings.actionRecoverCode, Icons.Default.Code, "qa_code") {
            onNavigate(ReuRoute.CodeExplorer)
          }
          QuickActionChip(strings.actionRebuildProject, Icons.Default.Build, "qa_rebuild") {
            onNavigate(ReuRoute.ProjectRebuilder)
          }
          QuickActionChip(strings.actionManageTools, Icons.Default.Extension, "qa_tools") {
            onNavigate(ReuRoute.ToolManager)
          }
        }
      }
    }

    // Active Tasks
    if (activeJobs.isNotEmpty()) {
      item {
        ReuSectionCard(
          title = if (strings.isArabic) "المهام النشطة" else "Active Operations",
          icon = Icons.Default.PlayArrow,
        ) {
          activeJobs.forEach { job ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(job.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(
                  onClick = { onCancelJob(job.id) },
                  colors = ButtonDefaults.buttonColors(containerColor = ReuPalette.CrimsonAccent),
                  modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                ) {
                  Text(if (strings.isArabic) "إلغاء" else "Cancel")
                }
              }
              Spacer(Modifier.height(6.dp))
              LinearProgressIndicator(
                progress = { job.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = ReuPalette.CyanAccent,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = "${job.progressPercent}% • ${job.detailMessage}",
                style = MaterialTheme.typography.labelSmall,
                color = ReuPalette.TextMuted,
              )
            }
          }
        }
      }
    }

    // Storage Usage
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "استخدام التخزين" else "Storage Usage",
        icon = Icons.Default.Storage,
      ) {
        Text(
          text =
            "Workspaces: ${FormatUtils.formatBytes(state.storageStats.workspaceBytes)}  •  " +
              "Temp: ${FormatUtils.formatBytes(state.storageStats.tempBytes)}  •  " +
              "Free Device: ${FormatUtils.formatBytes(state.storageStats.availableDeviceBytes)}",
          fontFamily = mono,
          style = MaterialTheme.typography.bodySmall,
          color = ReuPalette.TextPrimary,
        )
      }
    }

    // Recent Projects
    item {
      Text(
        text = strings.recentProjectsLabel,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = ReuPalette.TextPrimary,
      )
    }

    if (state.projects.isEmpty()) {
      item {
        ReuSectionCard(
          title = if (strings.isArabic) "لا توجد مشاريع بعد" else "No Imported Builds Yet",
          icon = Icons.Default.FolderZip,
        ) {
          Text(
            text =
              if (strings.isArabic) "استخدم شاشة الاستيراد لاختيار حزمة لعبة مصرح بتحليلها."
              else "Use Game Importer to select an authorized APK, XAPK, APKS, ZIP, or extracted Unity folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = ReuPalette.TextMuted,
          )
        }
      }
    } else {
      items(state.projects, key = { it.id }) { proj ->
        val isSelected = proj.id == state.activeProject?.id
        Surface(
          modifier =
            Modifier.fillMaxWidth()
              .clickable { onSelectProject(proj.id) }
              .testTag("project_card_${proj.id}"),
          shape = RoundedCornerShape(12.dp),
          color = if (isSelected) ReuPalette.SurfaceElevated else ReuPalette.SurfaceCard,
          border = BorderStroke(1.dp, if (isSelected) ReuPalette.CyanAccent else ReuPalette.BorderSubtle),
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(proj.displayName, fontWeight = FontWeight.Bold, color = ReuPalette.TextPrimary)
              Text(
                text = "${proj.sourceType.name} • ${proj.scriptingBackend.name} • ${FormatUtils.formatBytes(proj.sourceSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = ReuPalette.TextMuted,
              )
              Text(
                text = "SHA-256: ${proj.sourceSha256.take(16)}...",
                fontFamily = mono,
                style = MaterialTheme.typography.labelSmall,
                color = ReuPalette.CyanAccent,
              )
            }
            IconButton(
              onClick = { onDeleteProject(proj.id) },
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete project",
                tint = ReuPalette.CrimsonAccent,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun QuickActionChip(
  label: String,
  icon: ImageVector,
  tag: String,
  onClick: () -> Unit,
) {
  FilledTonalButton(
    onClick = onClick,
    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(tag),
    shape = RoundedCornerShape(10.dp),
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, maxLines = 1)
  }
}
