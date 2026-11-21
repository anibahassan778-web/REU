package com.reu.features.analysis

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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reu.core.common.JobStatus
import com.reu.core.database.ProjectEntity
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuRoute
import com.reu.features.ui.ReuSectionCard

@Composable
fun AnalysisScreen(
  state: ReuUiState,
  onRunCodeAnalysis: (ProjectEntity) -> Unit,
  onRunAssetExtraction: (ProjectEntity) -> Unit,
  onRunFullPipeline: (ProjectEntity) -> Unit,
  onPauseJob: (String) -> Unit,
  onResumeJob: (String) -> Unit,
  onCancelJob: (String) -> Unit,
  onNavigate: (ReuRoute) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  val project = state.activeProject

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("analysis_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "مركز التحليل الهندسي" else "Analysis & Pipeline Center",
        subtitle = project?.displayName ?: strings.noProjectSelected,
        icon = Icons.Default.Analytics,
      ) {
        if (project == null) {
          Button(onClick = { onNavigate(ReuRoute.Importer) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text(strings.actionImportGame)
          }
        } else {
          Button(
            onClick = { onRunFullPipeline(project) },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("run_full_pipeline_button"),
          ) {
            Text(if (strings.isArabic) "تشغيل خط التحليل الكامل + إعادة البناء" else "Run Full Analysis + Asset Extraction + Rebuild")
          }
          Spacer(Modifier.height(8.dp))
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
              onClick = { onRunCodeAnalysis(project) },
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("run_code_analysis_button"),
            ) {
              Text(if (strings.isArabic) "تحليل الشيفرة و ELF" else "Code & ELF")
            }
            FilledTonalButton(
              onClick = { onRunAssetExtraction(project) },
              modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("run_asset_extraction_button"),
            ) {
              Text(if (strings.isArabic) "فهرسة واستخراج الأصول" else "Assets & Scenes")
            }
          }
        }
      }
    }

    item {
      Text(
        text = if (strings.isArabic) "المهام الخلفية (${state.jobs.size})" else "Job Queue (${state.jobs.size})",
        style = MaterialTheme.typography.titleMedium,
        color = ReuPalette.TextPrimary,
      )
    }

    items(state.jobs.take(20), key = { it.id }) { job ->
      ReuSectionCard(
        title = job.title,
        subtitle = "${job.status.name} • ${job.progressPercent}% • ${job.detailMessage}",
        icon = Icons.Default.PlayArrow,
      ) {
        LinearProgressIndicator(progress = { job.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
        if (job.status == JobStatus.RUNNING || job.status == JobStatus.PAUSED) {
          Spacer(Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.status == JobStatus.RUNNING) {
              FilledTonalButton(onClick = { onPauseJob(job.id) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(if (strings.isArabic) "إيقاف مؤقت" else "Pause")
              }
            } else {
              FilledTonalButton(onClick = { onResumeJob(job.id) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(if (strings.isArabic) "استئناف" else "Resume")
              }
            }
            Button(
              onClick = { onCancelJob(job.id) },
              colors = ButtonDefaults.buttonColors(containerColor = ReuPalette.CrimsonAccent),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(if (strings.isArabic) "إلغاء" else "Cancel")
            }
          }
        }
        if (!job.errorMessage.isNullOrBlank()) {
          Spacer(Modifier.height(6.dp))
          Text(job.errorMessage!!, fontFamily = mono, style = MaterialTheme.typography.bodySmall, color = ReuPalette.CrimsonAccent)
        }
      }
    }

    item {
      ReuSectionCard(
        title = if (strings.isArabic) "سجل المحرك التشخيصي" else "Engine Diagnostic Logs",
        icon = Icons.Default.Terminal,
      ) {
        val recentLogs = state.logs.takeLast(25).reversed()
        if (recentLogs.isEmpty()) {
          Text("No diagnostic log entries recorded yet.", color = ReuPalette.TextMuted)
        } else {
          recentLogs.forEach { entry ->
            Text(
              text = entry.formatted(),
              fontFamily = mono,
              style = MaterialTheme.typography.labelSmall,
              color =
                when (entry.level.name) {
                  "ERROR" -> ReuPalette.CrimsonAccent
                  "WARN" -> ReuPalette.AmberAccent
                  else -> ReuPalette.TextMuted
                },
            )
          }
        }
      }
    }
  }
}
