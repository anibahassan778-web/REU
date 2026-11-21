package com.reu.features.scene

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reu.core.database.ProjectEntity
import com.reu.features.state.ReuUiState
import com.reu.features.ui.LocalMonoFont
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuSectionCard

@Composable
fun SceneExplorerScreen(
  state: ReuUiState,
  onRunAssetExtraction: (ProjectEntity) -> Unit,
) {
  val strings = LocalReuStrings.current
  val mono = LocalMonoFont.current
  val graph = state.sceneGraph
  val missingEdges = state.dependencyEdges.count { it.isMissing }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("scene_explorer_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ReuSectionCard(
        title = if (strings.isArabic) "مستكشف المشاهد والاعتماديات" else "Scene & Dependency Explorer",
        icon = Icons.Default.AccountTree,
      ) {
        if (graph == null) {
          Text(
            text =
              if (strings.isArabic) "شغّل استخراج الأصول لبناء شجرة GameObjects ورسم الاعتماديات."
              else "Run Asset & Scene Extraction to reconstruct GameObject hierarchies, Materials, Animations, ScriptableObjects, and dependency edges.",
            color = ReuPalette.TextMuted,
          )
          if (state.activeProject != null) {
            Spacer(Modifier.height(10.dp))
            Button(
              onClick = { onRunAssetExtraction(state.activeProject) },
              modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
              Text(if (strings.isArabic) "استخراج المشاهد" else "Extract Scene Graph")
            }
          }
        } else {
          Text("Scenes: ${graph.sceneNames.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
          Text(
            text = "GameObjects: ${graph.rootNodes.size} • Materials: ${graph.materials.size} • Animations: ${graph.animations.size} • ScriptableObjects: ${graph.scriptableObjects.size}",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = ReuPalette.CyanAccent,
          )
          Text(
            text = "Cameras: ${graph.camerasCount} • Lights: ${graph.lightsCount} • Dependency Edges: ${state.dependencyEdges.size} (Missing: $missingEdges)",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = if (missingEdges > 0) ReuPalette.AmberAccent else ReuPalette.EmeraldAccent,
          )
        }
      }
    }

    if (graph != null) {
      item {
        Text(
          text = if (strings.isArabic) "عقد GameObjects" else "GameObject Hierarchy Nodes",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }
      items(graph.rootNodes.take(100), key = { it.pathId }) { node ->
        ReuSectionCard(
          title = node.name,
          subtitle = "PathID: ${node.pathId} • Components: ${node.components.joinToString(", ")}",
          icon = Icons.Default.Hub,
        ) {}
      }

      if (graph.materials.isNotEmpty()) {
        item {
          ReuSectionCard(
            title = "Materials (${graph.materials.size})",
            icon = Icons.Default.Hub,
          ) {
            graph.materials.take(40).forEach { mat ->
              Text(
                text = "• ${mat.name} (${mat.shaderRef})",
                fontFamily = mono,
                style = MaterialTheme.typography.labelSmall,
                color = ReuPalette.TextPrimary,
              )
            }
          }
        }
      }
    }
  }
}
