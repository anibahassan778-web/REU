package com.reu.features.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reu.core.common.DataProvenance
import kotlinx.serialization.Serializable

object ReuPalette {
  val ObsidianBg = Color(0xFF0B111E)
  val SurfaceDark = Color(0xFF121A2B)
  val SurfaceCard = Color(0xFF182238)
  val SurfaceElevated = Color(0xFF202D48)
  val BorderSubtle = Color(0xFF2A3B5E)
  val CyanAccent = Color(0xFF00E5FF)
  val AmberAccent = Color(0xFFFFB300)
  val EmeraldAccent = Color(0xFF38EF7D)
  val CrimsonAccent = Color(0xFFFF5370)
  val TextPrimary = Color(0xFFEAF2FF)
  val TextMuted = Color(0xFF91A4C7)
}

val ReuDarkColorScheme =
  darkColorScheme(
    primary = ReuPalette.CyanAccent,
    onPrimary = Color(0xFF04141D),
    primaryContainer = Color(0xFF0E394B),
    onPrimaryContainer = Color(0xFFB4F6FF),
    secondary = ReuPalette.AmberAccent,
    onSecondary = Color(0xFF1D1200),
    secondaryContainer = Color(0xFF3D2A05),
    onSecondaryContainer = Color(0xFFFFDF94),
    tertiary = ReuPalette.EmeraldAccent,
    onTertiary = Color(0xFF00210E),
    background = ReuPalette.ObsidianBg,
    onBackground = ReuPalette.TextPrimary,
    surface = ReuPalette.SurfaceDark,
    onSurface = ReuPalette.TextPrimary,
    surfaceVariant = ReuPalette.SurfaceCard,
    onSurfaceVariant = ReuPalette.TextMuted,
    surfaceContainer = ReuPalette.SurfaceCard,
    surfaceContainerHigh = ReuPalette.SurfaceElevated,
    outline = ReuPalette.BorderSubtle,
    error = ReuPalette.CrimsonAccent,
    onError = Color(0xFF2A0009),
  )

data class ReuStrings(
  val appName: String = "REU",
  val appSubtitle: String = "Reverse Engineering Unity",
  val navDashboard: String = "Dashboard",
  val navImport: String = "Import",
  val navInspector: String = "Inspector",
  val navAnalysis: String = "Analysis",
  val navCode: String = "Code",
  val navAssets: String = "Assets",
  val navScenes: String = "Scenes",
  val navIl2Cpp: String = "IL2CPP",
  val navTools: String = "Tools",
  val navRebuilder: String = "Rebuilder",
  val navExport: String = "Export",
  val navSettings: String = "Settings",
  val provenanceOriginal: String = "Original Recovered",
  val provenanceReconstructed: String = "Decompiled / Reconstructed",
  val provenancePlaceholder: String = "Generated Placeholder",
  val provenanceMissing: String = "Missing / Unrecoverable",
  val activeProjectLabel: String = "Active Project",
  val noProjectSelected: String = "No project selected",
  val quickActionsLabel: String = "Quick Actions",
  val recentProjectsLabel: String = "Recent Projects",
  val actionImportGame: String = "Import Game",
  val actionAnalyzeGame: String = "Analyze Game",
  val actionBrowseAssets: String = "Browse Assets",
  val actionRecoverCode: String = "Recover Code",
  val actionRebuildProject: String = "Rebuild Project",
  val actionManageTools: String = "Manage Tools",
  val isArabic: Boolean = false,
)

val LocalReuStrings = staticCompositionLocalOf { ReuStrings() }
val LocalMonoFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

@Serializable
sealed interface ReuRoute {
  @Serializable data object Dashboard : ReuRoute
  @Serializable data object Importer : ReuRoute
  @Serializable data object Inspector : ReuRoute
  @Serializable data object Analysis : ReuRoute
  @Serializable data object CodeExplorer : ReuRoute
  @Serializable data object AssetBrowser : ReuRoute
  @Serializable data object SceneExplorer : ReuRoute
  @Serializable data object Il2CppLab : ReuRoute
  @Serializable data object ToolManager : ReuRoute
  @Serializable data object ProjectRebuilder : ReuRoute
  @Serializable data object ExportCenter : ReuRoute
  @Serializable data object Settings : ReuRoute
}

@Composable
fun ProvenanceBadge(provenance: DataProvenance, modifier: Modifier = Modifier) {
  val strings = LocalReuStrings.current
  val (label, color) =
    when (provenance) {
      DataProvenance.ORIGINAL_RECOVERED -> strings.provenanceOriginal to ReuPalette.EmeraldAccent
      DataProvenance.DECOMPILED_RECONSTRUCTED -> strings.provenanceReconstructed to ReuPalette.AmberAccent
      DataProvenance.GENERATED_PLACEHOLDER -> strings.provenancePlaceholder to ReuPalette.CyanAccent
      DataProvenance.MISSING_UNRECOVERABLE -> strings.provenanceMissing to ReuPalette.CrimsonAccent
    }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(6.dp),
    color = color.copy(alpha = 0.14f),
    border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
  ) {
    Text(
      text = label,
      color = color,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

@Composable
fun ReuSectionCard(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  icon: ImageVector? = null,
  trailing: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = ReuPalette.SurfaceCard),
    border = BorderStroke(1.dp, ReuPalette.BorderSubtle),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          if (icon != null) {
            Box(
              modifier =
                Modifier.size(34.dp)
                  .background(
                    Brush.linearGradient(
                      listOf(ReuPalette.CyanAccent.copy(alpha = 0.22f), ReuPalette.SurfaceElevated),
                    ),
                    RoundedCornerShape(8.dp),
                  ),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = icon,
                contentDescription = title,
                tint = ReuPalette.CyanAccent,
                modifier = Modifier.size(18.dp),
              )
            }
            Spacer(Modifier.width(10.dp))
          }
          Column {
            Text(
              text = title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = ReuPalette.TextPrimary,
            )
            if (!subtitle.isNullOrBlank()) {
              Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ReuPalette.TextMuted,
              )
            }
          }
        }
        if (trailing != null) trailing()
      }
      Spacer(Modifier.height(12.dp))
      content()
    }
  }
}

@Composable
fun MetricStatTile(
  label: String,
  value: String,
  accent: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = ReuPalette.SurfaceDark,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = ReuPalette.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
