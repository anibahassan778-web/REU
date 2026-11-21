package com.reu.features.assets

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reu.core.common.AssetKind
import com.reu.core.common.FormatUtils
import com.reu.core.database.AssetEntryEntity
import com.reu.core.database.ProjectEntity
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ProvenanceBadge
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard
import java.io.File

@Composable
fun AssetBrowserScreen(
  state: ReuUiState,
  onRunAssetExtraction: (ProjectEntity) -> Unit,
  onExportAsset: (AssetEntryEntity, ProjectEntity, String) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  var selectedKind by remember { mutableStateOf<AssetKind?>(null) }
  var selectedAsset by remember { mutableStateOf<AssetEntryEntity?>(null) }

  val filtered =
    remember(state.assets, selectedKind) {
      if (selectedKind == null) state.assets else state.assets.filter { it.kind == selectedKind }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("asset_browser_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = selectedKind == null,
          onClick = { selectedKind = null },
          label = { Text("All (${state.assets.size})") },
        )
        AssetKind.entries.forEach { kind ->
          val count = state.assets.count { it.kind == kind }
          if (count > 0) {
            FilterChip(
              selected = selectedKind == kind,
              onClick = { selectedKind = kind },
              label = { Text("${kind.name} ($count)") },
            )
          }
        }
      }
    }

    if (selectedAsset != null && state.activeProject != null) {
      val asset = selectedAsset!!
      val file = asset.extractedFilePath?.let { File(it) }?.takeIf { it.exists() }
      item {
        ReuSectionCard(
          title = asset.name,
          subtitle = "${asset.unityTypeName} • PathID: ${asset.pathId} • ${FormatUtils.formatBytes(asset.byteSize)}",
          icon = Icons.Default.PermMedia,
          trailing = { ProvenanceBadge(asset.provenance) },
        ) {
          if (asset.kind == AssetKind.TEXTURE && file != null) {
            AsyncImage(
              model = file,
              contentDescription = "Preview of ${asset.name}",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Spacer(Modifier.height(8.dp))
          } else if ((asset.kind == AssetKind.TEXT_ASSET || asset.kind == AssetKind.SHADER) && file != null) {
            val preview = remember(file.absolutePath) { runCatching { file.readText().take(800) }.getOrDefault("") }
            Text(preview, fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.TextPrimary)
            Spacer(Modifier.height(8.dp))
          }

          Text("Source: ${asset.sourceArchivePath}", fontFamily = mono, style = MaterialTheme.typography.labelSmall, color = ReuPalette.TextMuted)
          Text("Status: ${asset.extractionStatus}", style = MaterialTheme.typography.bodySmall, color = ReuPalette.CyanAccent)
          Spacer(Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (asset.kind == AssetKind.TEXTURE) {
              FilledTonalButton(onClick = { onExportAsset(asset, state.activeProject, "PNG") }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text("PNG")
              }
              FilledTonalButton(onClick = { onExportAsset(asset, state.activeProject, "JPG") }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text("JPG")
              }
              FilledTonalButton(onClick = { onExportAsset(asset, state.activeProject, "TGA") }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text("TGA")
              }
            } else {
              FilledTonalButton(onClick = { onExportAsset(asset, state.activeProject, "RAW") }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(if (strings.isArabic) "تصدير الأصل" else "Export Asset")
              }
            }
          }
        }
      }
    }

    if (filtered.isEmpty()) {
      item {
        ReuSectionCard(
          title = if (strings.isArabic) "لا توجد أصول مستخرجة بعد" else "No Indexed Assets Yet",
          icon = Icons.Default.PermMedia,
        ) {
          Text(
            text =
              if (strings.isArabic) "شغّل مستخرج الأصول لفحص ملفات assets و UnityFS."
              else "Run Asset & Scene Extraction to index Texture2D, AudioClip, Mesh, Material, AnimationClip, and StreamingAssets.",
            color = ReuPalette.TextMuted,
          )
          if (state.activeProject != null) {
            Spacer(Modifier.height(10.dp))
            Button(
              onClick = { onRunAssetExtraction(state.activeProject) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(strings.actionBrowseAssets)
            }
          }
        }
      }
    } else {
      items(filtered.take(300), key = { it.id }) { itemAsset ->
        val isSel = selectedAsset?.id == itemAsset.id
        Surface(
          modifier = Modifier.fillMaxWidth().clickable { selectedAsset = itemAsset },
          shape = RoundedCornerShape(10.dp),
          color = if (isSel) ReuPalette.SurfaceElevated else ReuPalette.SurfaceCard,
          border = BorderStroke(1.dp, if (isSel) ReuPalette.CyanAccent else ReuPalette.BorderSubtle),
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(itemAsset.name, fontWeight = FontWeight.SemiBold, color = ReuPalette.TextPrimary, modifier = Modifier.weight(1f))
              ProvenanceBadge(itemAsset.provenance)
            }
            Text(
              text = "${itemAsset.unityTypeName} • ${FormatUtils.formatBytes(itemAsset.byteSize)} • ${itemAsset.extractionStatus}",
              fontFamily = mono,
              style = MaterialTheme.typography.labelSmall,
              color = ReuPalette.TextMuted,
            )
          }
        }
      }
    }
  }
}
