package com.reu.features.code

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reu.core.database.CodeSymbolEntity
import com.reu.core.database.ProjectEntity
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ProvenanceBadge
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard

@Composable
fun CodeExplorerScreen(
  state: ReuUiState,
  onRunCodeAnalysis: (ProjectEntity) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  var query by remember { mutableStateOf("") }
  var selectedSymbol by remember { mutableStateOf<CodeSymbolEntity?>(null) }

  val filtered =
    remember(state.codeSymbols, query) {
      if (query.isBlank()) state.codeSymbols
      else {
        val q = query.trim().lowercase()
        state.codeSymbols.filter {
          it.typeName.lowercase().contains(q) ||
            it.namespaceName.lowercase().contains(q) ||
            it.assemblyName.lowercase().contains(q)
        }
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("code_explorer_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(if (strings.isArabic) "بحث في الفئات ومساحات الأسماء" else "Search Assemblies, Namespaces, or Classes") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("code_search_input"),
      )
    }

    if (selectedSymbol != null) {
      val sym = selectedSymbol!!
      item {
        ReuSectionCard(
          title = "${sym.namespaceName}.${sym.typeName}",
          subtitle = "${sym.assemblyName} • ${sym.symbolKind} • Token/RVA: ${sym.rvaAddress ?: "N/A"}",
          icon = Icons.Default.Code,
          trailing = { ProvenanceBadge(sym.provenance) },
        ) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = ReuPalette.ObsidianBg,
            border = BorderStroke(1.dp, ReuPalette.BorderSubtle),
          ) {
            Text(
              text = sym.sourcePreview,
              fontFamily = mono,
              style = MaterialTheme.typography.bodySmall,
              color = ReuPalette.TextPrimary,
              modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
          }
        }
      }
    }

    if (filtered.isEmpty()) {
      item {
        ReuSectionCard(
          title = if (strings.isArabic) "لا توجد شيفرة محللة بعد" else "No Code Symbols Recovered Yet",
          icon = Icons.Default.Code,
        ) {
          Text(
            text =
              if (strings.isArabic) "قم بتشغيل تحليل Mono DLL أو IL2CPP لاستخراج الفئات والتواقيع."
              else "Run Mono (.dll) or IL2CPP (global-metadata.dat + libil2cpp.so) code analysis to populate types and CIL/signature previews.",
            color = ReuPalette.TextMuted,
          )
          if (state.activeProject != null) {
            Spacer(Modifier.height(10.dp))
            Button(
              onClick = { onRunCodeAnalysis(state.activeProject) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(strings.actionRecoverCode)
            }
          }
        }
      }
    } else {
      items(filtered.take(250), key = { it.id }) { sym ->
        val isSel = selectedSymbol?.id == sym.id
        Surface(
          modifier = Modifier.fillMaxWidth().clickable { selectedSymbol = sym },
          shape = RoundedCornerShape(10.dp),
          color = if (isSel) ReuPalette.SurfaceElevated else ReuPalette.SurfaceCard,
          border = BorderStroke(1.dp, if (isSel) ReuPalette.CyanAccent else ReuPalette.BorderSubtle),
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(sym.typeName, fontWeight = FontWeight.Bold, color = ReuPalette.TextPrimary)
              ProvenanceBadge(sym.provenance)
            }
            Text(
              text = "${sym.assemblyName} • ${sym.namespaceName}",
              fontFamily = mono,
              style = MaterialTheme.typography.labelSmall,
              color = ReuPalette.CyanAccent,
            )
            Text(sym.memberSignature, style = MaterialTheme.typography.bodySmall, color = ReuPalette.TextMuted)
          }
        }
      }
    }
  }
}
