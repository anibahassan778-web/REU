package com.reu.features.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reu.core.common.JobStatus
import com.reu.core.common.JobType
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard

@Composable
fun ImporterScreen(
  state: ReuUiState,
  onImportFiles: (List<Uri>) -> Unit,
  onImportDirectory: (Uri) -> Unit,
  onCancelJob: (String) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current

  val singleArchiveLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) onImportFiles(listOf(uri))
    }

  val multiFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isNotEmpty()) onImportFiles(uris)
    }

  val folderLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) onImportDirectory(uri)
    }

  val importJobs = state.jobs.filter { it.jobType == JobType.IMPORT }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("importer_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "استيراد حزمة لعبة يونيتي" else "Import Authorized Unity Build",
        subtitle =
          if (strings.isArabic) "يدعم APK و XAPK و APKS و ZIP والمجلدات والملفات الفردية عبر SAF"
          else "Supports APK, XAPK, APKS split sets, ZIP, extracted folders, and individual files via SAF",
        icon = Icons.Default.FolderZip,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(
            onClick = { singleArchiveLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("import_archive_button"),
          ) {
            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (strings.isArabic) "اختيار ملف APK / XAPK / APKS / ZIP" else "Select APK / XAPK / APKS / ZIP")
          }

          FilledTonalButton(
            onClick = { multiFileLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("import_split_files_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (strings.isArabic) "استيراد حزم مقسمة أو ملفات فردية" else "Select Split APKs or Individual Unity Files")
          }

          FilledTonalButton(
            onClick = { folderLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("import_folder_button"),
          ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (strings.isArabic) "استيراد مجلد لعبة مستخرج" else "Select Extracted Unity Game Folder")
          }
        }
      }
    }

    item {
      ReuSectionCard(
        title = if (strings.isArabic) "ضمانات الأمان والنزاهة" else "Import Security & Integrity Guards",
        icon = Icons.Default.Security,
      ) {
        val bullets =
          if (strings.isArabic) {
            listOf(
              "• التحقق من المسارات لمنع هجمات Zip-Slip واجتياز الدليل.",
              "• حساب بصمة SHA-256 بالبث المتدفق لاكتشاف التكرار دون تحميل اللعبة بالذاكرة.",
              "• عدم تعديل الملفات الأصلية مطلقاً؛ تتم المعالجة داخل مساحة عمل معزولة.",
            )
          } else {
            listOf(
              "• Canonical Zip-Slip path traversal protection on all archive entries.",
              "• Streaming SHA-256 hashing & duplicate detection without loading full APKs into RAM.",
              "• Original files are never modified; isolated per-project workspaces preserve hashes.",
            )
          }
        bullets.forEach {
          Text(it, style = MaterialTheme.typography.bodySmall, color = ReuPalette.TextMuted)
          Spacer(Modifier.height(4.dp))
        }
      }
    }

    if (importJobs.isNotEmpty()) {
      item {
        Text(
          text = if (strings.isArabic) "سجل عمليات الاستيراد" else "Import Tasks",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }
      items(importJobs, key = { it.id }) { job ->
        ReuSectionCard(title = job.title, subtitle = "${job.status.name} • ${job.detailMessage}") {
          if (job.status == JobStatus.RUNNING) {
            LinearProgressIndicator(
              progress = { job.progressPercent / 100f },
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
              onClick = { onCancelJob(job.id) },
              colors = ButtonDefaults.buttonColors(containerColor = ReuPalette.CrimsonAccent),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(if (strings.isArabic) "إلغاء الاستيراد" else "Cancel Import")
            }
          }
          if (!job.errorMessage.isNullOrBlank()) {
            Text(
              text = job.errorMessage!!,
              fontFamily = mono,
              style = MaterialTheme.typography.bodySmall,
              color = ReuPalette.CrimsonAccent,
            )
          }
        }
      }
    }
  }
}
