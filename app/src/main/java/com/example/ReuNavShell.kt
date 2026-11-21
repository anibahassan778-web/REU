package com.example

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.theme.ReuTheme
import com.reu.core.common.AppLanguage
import com.reu.features.analysis.AnalysisScreen
import com.reu.features.assets.AssetBrowserScreen
import com.reu.features.code.CodeExplorerScreen
import com.reu.features.dashboard.DashboardScreen
import com.reu.features.export.ExportCenterScreen
import com.reu.features.il2cpp.Il2CppLabScreen
import com.reu.features.importer.ImporterScreen
import com.reu.features.inspector.InspectorScreen
import com.reu.features.rebuilder.ProjectRebuilderScreen
import com.reu.features.scene.SceneExplorerScreen
import com.reu.features.settings.SettingsScreen
import com.reu.features.state.ReuMainViewModel
import com.reu.features.tools.ToolManagerScreen
import com.reu.features.ui.LocalReuStrings
import com.reu.features.ui.ReuPalette
import com.reu.features.ui.ReuRoute
import com.reu.features.ui.ReuStrings
import java.util.Locale
import kotlinx.coroutines.launch

private data class NavDestinationItem(
  val route: ReuRoute,
  val label: String,
  val icon: ImageVector,
  val testTag: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReuNavShell(viewModel: ReuMainViewModel) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val baseContext = LocalContext.current

  val isArabic =
    when (state.settings.language) {
      AppLanguage.ARABIC -> true
      AppLanguage.ENGLISH -> false
      AppLanguage.SYSTEM -> Locale.getDefault().language == "ar"
    }

  val localizedContext =
    remember(baseContext, isArabic) {
      val locale = if (isArabic) Locale("ar") else Locale("en")
      val config = Configuration(baseContext.resources.configuration)
      config.setLocale(locale)
      baseContext.createConfigurationContext(config)
    }

  val strings =
    remember(localizedContext, isArabic) {
      ReuStrings(
        appName = localizedContext.getString(R.string.app_name),
        appSubtitle = localizedContext.getString(R.string.app_subtitle),
        navDashboard = localizedContext.getString(R.string.nav_dashboard),
        navImport = localizedContext.getString(R.string.nav_import),
        navInspector = localizedContext.getString(R.string.nav_inspector),
        navAnalysis = localizedContext.getString(R.string.nav_analysis),
        navCode = localizedContext.getString(R.string.nav_code),
        navAssets = localizedContext.getString(R.string.nav_assets),
        navScenes = localizedContext.getString(R.string.nav_scenes),
        navIl2Cpp = localizedContext.getString(R.string.nav_il2cpp),
        navTools = localizedContext.getString(R.string.nav_tools),
        navRebuilder = localizedContext.getString(R.string.nav_rebuilder),
        navExport = localizedContext.getString(R.string.nav_export),
        navSettings = localizedContext.getString(R.string.nav_settings),
        provenanceOriginal = localizedContext.getString(R.string.provenance_original),
        provenanceReconstructed = localizedContext.getString(R.string.provenance_reconstructed),
        provenancePlaceholder = localizedContext.getString(R.string.provenance_placeholder),
        provenanceMissing = localizedContext.getString(R.string.provenance_missing),
        activeProjectLabel = localizedContext.getString(R.string.label_active_project),
        noProjectSelected = localizedContext.getString(R.string.label_no_project),
        quickActionsLabel = localizedContext.getString(R.string.label_quick_actions),
        recentProjectsLabel = localizedContext.getString(R.string.label_recent_projects),
        actionImportGame = localizedContext.getString(R.string.action_import_game),
        actionAnalyzeGame = localizedContext.getString(R.string.action_analyze_game),
        actionBrowseAssets = localizedContext.getString(R.string.action_browse_assets),
        actionRecoverCode = localizedContext.getString(R.string.action_recover_code),
        actionRebuildProject = localizedContext.getString(R.string.action_rebuild_project),
        actionManageTools = localizedContext.getString(R.string.action_manage_tools),
        isArabic = isArabic,
      )
    }

  val layoutDir = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
  val navController = rememberNavController()
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(state.statusBanner) {
    val msg = state.statusBanner
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearBanner()
    }
  }

  val allItems =
    listOf(
      NavDestinationItem(ReuRoute.Dashboard, strings.navDashboard, Icons.Default.Dashboard, "nav_dashboard"),
      NavDestinationItem(ReuRoute.Importer, strings.navImport, Icons.Default.FileUpload, "nav_import"),
      NavDestinationItem(ReuRoute.Inspector, strings.navInspector, Icons.Default.DeveloperBoard, "nav_inspector"),
      NavDestinationItem(ReuRoute.Analysis, strings.navAnalysis, Icons.Default.Analytics, "nav_analysis"),
      NavDestinationItem(ReuRoute.CodeExplorer, strings.navCode, Icons.Default.Code, "nav_code"),
      NavDestinationItem(ReuRoute.AssetBrowser, strings.navAssets, Icons.Default.PermMedia, "nav_assets"),
      NavDestinationItem(ReuRoute.SceneExplorer, strings.navScenes, Icons.Default.AccountTree, "nav_scenes"),
      NavDestinationItem(ReuRoute.Il2CppLab, strings.navIl2Cpp, Icons.Default.Biotech, "nav_il2cpp"),
      NavDestinationItem(ReuRoute.ToolManager, strings.navTools, Icons.Default.Extension, "nav_tools"),
      NavDestinationItem(ReuRoute.ProjectRebuilder, strings.navRebuilder, Icons.Default.Build, "nav_rebuilder"),
      NavDestinationItem(ReuRoute.ExportCenter, strings.navExport, Icons.Default.Archive, "nav_export"),
      NavDestinationItem(ReuRoute.Settings, strings.navSettings, Icons.Default.Settings, "nav_settings"),
    )

  val bottomPrimaryItems = listOf(allItems[0], allItems[1], allItems[3], allItems[4], allItems[5])
  val currentBackStack by navController.currentBackStackEntryAsState()
  val currentDest = currentBackStack?.destination

  fun navigateTo(route: ReuRoute) {
    navController.navigate(route) {
      popUpTo(ReuRoute.Dashboard) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  CompositionLocalProvider(
    LocalLayoutDirection provides layoutDir,
    LocalReuStrings provides strings,
  ) {
    ReuTheme(isArabic = isArabic) {
      ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
          ModalDrawerSheet(drawerContainerColor = ReuPalette.SurfaceDark) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
              Text(
                text = "${strings.appName} — ${strings.appSubtitle}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ReuPalette.CyanAccent,
                modifier = Modifier.padding(12.dp),
              )
              allItems.forEach { item ->
                val selected = currentDest?.hasRoute(item.route::class) == true
                NavigationDrawerItem(
                  label = { Text(item.label) },
                  selected = selected,
                  icon = { Icon(item.icon, contentDescription = item.label) },
                  onClick = {
                    scope.launch { drawerState.close() }
                    navigateTo(item.route)
                  },
                  modifier = Modifier.testTag("drawer_${item.testTag}"),
                )
              }
            }
          }
        },
      ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
          val useRail = maxWidth >= 600.dp

          Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = ReuPalette.ObsidianBg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
              val activeTitle =
                allItems.firstOrNull { currentDest?.hasRoute(it.route::class) == true }?.label ?: strings.appName
              TopAppBar(
                title = {
                  Column {
                    Text(activeTitle, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("topbar_title"))
                    state.activeProject?.let {
                      Text(
                        text = "${it.displayName} (${it.scriptingBackend.name})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReuPalette.CyanAccent,
                      )
                    }
                  }
                },
                navigationIcon = {
                  IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.testTag("open_drawer_button"),
                  ) {
                    Icon(Icons.Default.Menu, contentDescription = "Open navigation drawer")
                  }
                },
                actions = {
                  IconButton(
                    onClick = { navigateTo(ReuRoute.Settings) },
                    modifier = Modifier.testTag("topbar_settings_button"),
                  ) {
                    Icon(Icons.Default.Settings, contentDescription = strings.navSettings)
                  }
                },
                colors =
                  TopAppBarDefaults.topAppBarColors(
                    containerColor = ReuPalette.SurfaceDark,
                    titleContentColor = ReuPalette.TextPrimary,
                  ),
              )
            },
            bottomBar = {
              if (!useRail) {
                NavigationBar(containerColor = ReuPalette.SurfaceDark) {
                  bottomPrimaryItems.forEach { item ->
                    val selected = currentDest?.hasRoute(item.route::class) == true
                    NavigationBarItem(
                      selected = selected,
                      onClick = { navigateTo(item.route) },
                      icon = { Icon(item.icon, contentDescription = item.label) },
                      label = { Text(item.label, maxLines = 1) },
                      modifier = Modifier.testTag("bottom_${item.testTag}"),
                    )
                  }
                }
              }
            },
          ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
              if (useRail) {
                NavigationRail(
                  containerColor = ReuPalette.SurfaceDark,
                  modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                  allItems.forEach { item ->
                    val selected = currentDest?.hasRoute(item.route::class) == true
                    NavigationRailItem(
                      selected = selected,
                      onClick = { navigateTo(item.route) },
                      icon = { Icon(item.icon, contentDescription = item.label) },
                      label = { Text(item.label, maxLines = 1) },
                    )
                  }
                }
              }

              NavHost(
                navController = navController,
                startDestination = ReuRoute.Dashboard,
                modifier = Modifier.fillMaxSize(),
              ) {
                composable<ReuRoute.Dashboard> {
                  DashboardScreen(
                    state = state,
                    onNavigate = ::navigateTo,
                    onSelectProject = viewModel::selectProject,
                    onDeleteProject = viewModel::deleteProject,
                    onCancelJob = viewModel::cancelJob,
                  )
                }
                composable<ReuRoute.Importer> {
                  ImporterScreen(
                    state = state,
                    onImportFiles = viewModel::importFiles,
                    onImportDirectory = viewModel::importDirectory,
                    onCancelJob = viewModel::cancelJob,
                  )
                }
                composable<ReuRoute.Inspector> {
                  InspectorScreen(state = state, onNavigate = ::navigateTo)
                }
                composable<ReuRoute.Analysis> {
                  AnalysisScreen(
                    state = state,
                    onRunCodeAnalysis = viewModel::runCodeAnalysis,
                    onRunAssetExtraction = viewModel::runAssetAndSceneExtraction,
                    onRunFullPipeline = viewModel::runFullPipeline,
                    onPauseJob = viewModel::pauseJob,
                    onResumeJob = viewModel::resumeJob,
                    onCancelJob = viewModel::cancelJob,
                    onNavigate = ::navigateTo,
                  )
                }
                composable<ReuRoute.CodeExplorer> {
                  CodeExplorerScreen(state = state, onRunCodeAnalysis = viewModel::runCodeAnalysis)
                }
                composable<ReuRoute.AssetBrowser> {
                  AssetBrowserScreen(
                    state = state,
                    onRunAssetExtraction = viewModel::runAssetAndSceneExtraction,
                    onExportAsset = viewModel::exportSingleAsset,
                  )
                }
                composable<ReuRoute.SceneExplorer> {
                  SceneExplorerScreen(state = state, onRunAssetExtraction = viewModel::runAssetAndSceneExtraction)
                }
                composable<ReuRoute.Il2CppLab> {
                  Il2CppLabScreen(state = state, onRunCodeAnalysis = viewModel::runCodeAnalysis)
                }
                composable<ReuRoute.ToolManager> {
                  ToolManagerScreen(
                    state = state,
                    onInstallTool = viewModel::installTool,
                    onUninstallTool = viewModel::uninstallTool,
                    onInstallOfflineZip = viewModel::installOfflineToolZip,
                    onCheckRemoteCatalog = viewModel::checkRemoteCatalog,
                  )
                }
                composable<ReuRoute.ProjectRebuilder> {
                  ProjectRebuilderScreen(
                    state = state,
                    onRebuildProject = viewModel::rebuildProject,
                    onNavigate = ::navigateTo,
                  )
                }
                composable<ReuRoute.ExportCenter> {
                  ExportCenterScreen(
                    state = state,
                    onExportProjectZip = viewModel::exportProjectZip,
                    onSaveZipToSaf = viewModel::copyExportedZipToSaf,
                  )
                }
                composable<ReuRoute.Settings> {
                  SettingsScreen(
                    state = state,
                    onSetLanguage = viewModel::setLanguage,
                    onSetOfflineFirst = viewModel::setOfflineFirst,
                    onSetBufferKb = viewModel::setStreamingBufferKb,
                    onClearTemp = viewModel::clearTempWorkspace,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
