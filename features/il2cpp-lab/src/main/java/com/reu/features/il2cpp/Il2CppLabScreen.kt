package com.reu.features.il2cpp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reu.core.common.DataProvenance
import com.reu.core.database.ProjectEntity
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ProvenanceBadge
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard

@Composable
fun Il2CppLabScreen(
  state: ReuUiState,
  onRunCodeAnalysis: (ProjectEntity) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  val il2cpp = state.il2cppReport
  val elfs = state.elfReports

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("il2cpp_lab_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "مختبر IL2CPP و ELF" else "IL2CPP & Native ELF Lab",
        subtitle = state.nativeEngineStatus,
        icon = Icons.Default.Biotech,
      ) {
        if (il2cpp == null && elfs.isEmpty()) {
          Text(
            text =
              if (strings.isArabic) "شغّل تحليل الشيفرة لفحص global-metadata.dat ومكتبات libil2cpp.so."
              else "Run Code & ELF Analysis to inspect ELF64/ELF32 sections, symbols, and global-metadata.dat tables.",
            color = ReuPalette.TextMuted,
          )
          if (state.activeProject != null) {
            Spacer(Modifier.height(10.dp))
            Button(
              onClick = { onRunCodeAnalysis(state.activeProject) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(if (strings.isArabic) "تشغيل مختبر IL2CPP" else "Run IL2CPP & ELF Analysis")
            }
          }
        }
      }
    }

    if (il2cpp != null) {
      val meta = il2cpp.metadataReport
      item {
        ReuSectionCard(
          title = "global-metadata.dat (v${meta.metadataVersion})",
          subtitle = "Sanity: ${meta.sanityHex} • Valid: ${meta.valid}",
          icon = Icons.Default.Memory,
          trailing = { ProvenanceBadge(DataProvenance.ORIGINAL_RECOVERED) },
        ) {
          if (!meta.errorMessage.isNullOrBlank()) {
            Text(meta.errorMessage!!, color = ReuPalette.CrimsonAccent, fontFamily = mono)
          } else {
            Text("Images: ${meta.imageCount} • Types: ${meta.typeDefinitionCount} • Methods: ${meta.methodCount} • Fields: ${meta.fieldCount}", fontFamily = mono, style = MaterialTheme.typography.bodySmall, color = ReuPalette.CyanAccent)
            Spacer(Modifier.height(6.dp))
            Text("Sample String Literals: ${meta.sampleStringLiterals.take(6).joinToString(" | ")}", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.TextMuted)
          }
        }
      }

      item {
        ReuSectionCard(
          title = if (strings.isArabic) "قيود استعادة IL2CPP الصادقة" else "IL2CPP Recovery Accuracy & Boundaries",
          icon = Icons.Default.WarningAmber,
          trailing = { ProvenanceBadge(DataProvenance.MISSING_UNRECOVERABLE) },
        ) {
          il2cpp.limitations.forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodySmall, color = ReuPalette.AmberAccent)
            Spacer(Modifier.height(4.dp))
          }
        }
      }
    }

    items(elfs, key = { it.fileName }) { elf ->
      ReuSectionCard(
        title = elf.fileName,
        subtitle = "${elf.machineArch} • ${if (elf.is64Bit) "ELF64" else "ELF32"} • Entry ${elf.entryPointHex}",
        icon = Icons.Default.Memory,
      ) {
        Text("Sections: ${elf.sections.size} • Exported Symbols: ${elf.exportedSymbols.size}", fontFamily = mono, style = MaterialTheme.typography.bodySmall, color = ReuPalette.CyanAccent)
        if (elf.il2cppSymbolsFound.isNotEmpty()) {
          Spacer(Modifier.height(4.dp))
          Text(
            text = "IL2CPP/JNI Symbols: ${elf.il2cppSymbolsFound.take(8).joinToString(", ")}",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = ReuPalette.EmeraldAccent,
          )
        }
      }
    }
  }
}
