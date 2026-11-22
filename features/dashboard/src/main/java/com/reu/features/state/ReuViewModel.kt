package com.reu.features.state

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reu.core.common.AppLanguage
import com.reu.core.common.JobType
import com.reu.core.database.AnalysisJobEntity
import com.reu.core.database.AssetEntryEntity
import com.reu.core.database.CodeSymbolEntity
import com.reu.core.database.DependencyEdgeEntity
import com.reu.core.database.ProjectEntity
import com.reu.core.database.ReuRepository
import com.reu.core.database.ToolModuleEntity
import com.reu.core.jobs.JobCoordinator
import com.reu.core.logging.LogEntry
import com.reu.core.logging.ReuLogger
import com.reu.core.storage.SettingsDataStore
import com.reu.core.storage.StorageStats
import com.reu.core.storage.UserSettings
import com.reu.core.storage.WorkspaceManager
import com.reu.engine.detector.UnityDetectionReport
import com.reu.engine.elf.ElfAnalysisReport
import com.reu.engine.il2cpp.Il2CppPipelineReport
import com.reu.engine.reconstruction.RebuildMode
import com.reu.engine.reconstruction.ReconstructionReport
import com.reu.engine.scene.SceneGraphSummary
import com.reu.features.pipeline.ReuPipelineOrchestrator
import com.reu.integrations.NativeEngineBridge
import com.reu.integrations.tools.ToolModuleManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReuUiState(
  val projects: List<ProjectEntity> = emptyList(),
  val activeProject: ProjectEntity? = null,
  val detectionReport: UnityDetectionReport? = null,
  val elfReports: List<ElfAnalysisReport> = emptyList(),
  val il2cppReport: Il2CppPipelineReport? = null,
  val sceneGraph: SceneGraphSummary? = null,
  val assets: List<AssetEntryEntity> = emptyList(),
  val codeSymbols: List<CodeSymbolEntity> = emptyList(),
  val dependencyEdges: List<DependencyEdgeEntity> = emptyList(),
  val jobs: List<AnalysisJobEntity> = emptyList(),
  val tools: List<ToolModuleEntity> = emptyList(),
  val logs: List<LogEntry> = emptyList(),
  val settings: UserSettings = UserSettings(),
  val storageStats: StorageStats = StorageStats(0, 0, 0, 0),
  val lastRebuildReport: ReconstructionReport? = null,
  val lastExportedZipPath: String? = null,
  val lastExportedZipSha256: String? = null,
  val statusBanner: String? = null,
  val nativeEngineStatus: String = NativeEngineBridge.engineStatusSummary(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReuMainViewModel(
  private val context: Context,
  private val repository: ReuRepository,
  private val workspaceManager: WorkspaceManager,
  private val settingsStore: SettingsDataStore,
  private val logger: ReuLogger,
  private val jobCoordinator: JobCoordinator,
  private val toolManager: ToolModuleManager,
  private val orchestrator: ReuPipelineOrchestrator,
) : ViewModel() {

  private val _storageStats = MutableStateFlow(workspaceManager.getStorageStats())
  private val _lastRebuild = MutableStateFlow<ReconstructionReport?>(null)
  private val _lastExport = MutableStateFlow<Pair<String?, String?>>(null to null)
  private val _statusBanner = MutableStateFlow<String?>(null)

  private val activeProjectFlow: StateFlow<ProjectEntity?> =
    combine(repository.allProjects, settingsStore.settingsFlow) { projects, settings ->
      projects.firstOrNull { it.id == settings.activeProjectId } ?: projects.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  private val activeAssetsFlow =
    activeProjectFlow.flatMapLatest { proj ->
      if (proj == null) flowOf(emptyList()) else repository.observeAssetsForProject(proj.id)
    }

  private val activeSymbolsFlow =
    activeProjectFlow.flatMapLatest { proj ->
      if (proj == null) flowOf(emptyList()) else repository.observeSymbolsForProject(proj.id)
    }

  private val activeEdgesFlow =
    activeProjectFlow.flatMapLatest { proj ->
      if (proj == null) flowOf(emptyList()) else repository.observeEdgesForProject(proj.id)
    }

  val uiState: StateFlow<ReuUiState> =
    combine(
      repository.allProjects,
      activeProjectFlow,
      activeAssetsFlow,
      activeSymbolsFlow,
      activeEdgesFlow,
      repository.allJobs,
      repository.allTools,
      logger.logs,
      settingsStore.settingsFlow,
      _storageStats,
      _lastRebuild,
      _lastExport,
      _statusBanner,
    ) { values ->
      @Suppress("UNCHECKED_CAST")
      val projects = values[0] as List<ProjectEntity>
      val activeProj = values[1] as ProjectEntity?
      @Suppress("UNCHECKED_CAST")
      val assets = values[2] as List<AssetEntryEntity>
      @Suppress("UNCHECKED_CAST")
      val symbols = values[3] as List<CodeSymbolEntity>
      @Suppress("UNCHECKED_CAST")
      val edges = values[4] as List<DependencyEdgeEntity>
      @Suppress("UNCHECKED_CAST")
      val jobs = values[5] as List<AnalysisJobEntity>
      @Suppress("UNCHECKED_CAST")
      val tools = values[6] as List<ToolModuleEntity>
      @Suppress("UNCHECKED_CAST")
      val logs = values[7] as List<LogEntry>
      val settings = values[8] as UserSettings
      val stats = values[9] as StorageStats
      val rebuild = values[10] as ReconstructionReport?
      @Suppress("UNCHECKED_CAST")
      val exportPair = values[11] as Pair<String?, String?>
      val banner = values[12] as String?

      ReuUiState(
        projects = projects,
        activeProject = activeProj,
        detectionReport = activeProj?.let { orchestrator.loadDetectionReport(it) },
        elfReports = activeProj?.let { orchestrator.loadElfReports(it) }.orEmpty(),
        il2cppReport = activeProj?.let { orchestrator.loadIl2CppReport(it) },
        sceneGraph = activeProj?.let { orchestrator.loadSceneGraph(it) },
        assets = assets,
        codeSymbols = symbols,
        dependencyEdges = edges,
        jobs = jobs,
        tools = tools,
        logs = logs,
        settings = settings,
        storageStats = stats,
        lastRebuildReport = rebuild,
        lastExportedZipPath = exportPair.first,
        lastExportedZipSha256 = exportPair.second,
        statusBanner = banner,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReuUiState())

  init {
    viewModelScope.launch(Dispatchers.IO) {
      toolManager.ensureSeedCatalog()
      refreshStorageStats()
    }
  }

  fun clearBanner() {
    _statusBanner.value = null
  }

  fun selectProject(projectId: String) {
    viewModelScope.launch {
      settingsStore.setActiveProjectId(projectId)
      _lastRebuild.value = null
      refreshStorageStats()
    }
  }

  fun deleteProject(projectId: String) {
    viewModelScope.launch(Dispatchers.IO) {
      repository.deleteProjectData(projectId)
      workspaceManager.deleteProjectWorkspace(projectId)
      if (uiState.value.activeProject?.id == projectId) {
        settingsStore.setActiveProjectId(null)
      }
      refreshStorageStats()
      _statusBanner.value = "Project workspace deleted."
    }
  }

  fun importFiles(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val bufKb = uiState.value.settings.streamingBufferKb
    jobCoordinator.launchJob(
      projectId = null,
      jobType = JobType.IMPORT,
      title = "Import (${uris.size} item${if (uris.size > 1) "s" else ""})",
    ) { token, updateProgress ->
      val outcome = orchestrator.importFromSafUris(uris, bufKb, token, updateProgress)
      settingsStore.setActiveProjectId(outcome.project.id)
      refreshStorageStats()
      _statusBanner.value =
        if (outcome.isDuplicate) "Duplicate SHA-256 detected; opened existing project ${outcome.project.displayName}"
        else "Imported ${outcome.project.displayName} (${outcome.detection.scriptingBackend})"
    }
  }

  fun importDirectory(treeUri: Uri) {
    jobCoordinator.launchJob(
      projectId = null,
      jobType = JobType.IMPORT,
      title = "Import Unity Folder",
    ) { token, updateProgress ->
      val outcome = orchestrator.importDirectoryTree(treeUri, token, updateProgress)
      settingsStore.setActiveProjectId(outcome.project.id)
      refreshStorageStats()
      _statusBanner.value = "Imported directory ${outcome.project.displayName}"
    }
  }

  fun runCodeAnalysis(project: ProjectEntity) {
    jobCoordinator.launchJob(
      projectId = project.id,
      jobType = if (project.scriptingBackend.name == "MONO") JobType.ANALYZE_MONO else JobType.ANALYZE_IL2CPP,
      title = "Code & ELF Analysis: ${project.displayName}",
    ) { token, updateProgress ->
      val (elfs, il2cpp) = orchestrator.runFullCodeAnalysis(project, token, updateProgress)
      refreshStorageStats()
      _statusBanner.value =
        "Analyzed ${elfs.size} ELF binaries" +
          (il2cpp?.metadataReport?.let { " • Metadata v${it.metadataVersion}: ${it.typeDefinitionCount} types" } ?: "")
    }
  }

  fun runAssetAndSceneExtraction(project: ProjectEntity) {
    jobCoordinator.launchJob(
      projectId = project.id,
      jobType = JobType.EXTRACT_ASSETS,
      title = "Asset & Scene Extraction: ${project.displayName}",
    ) { token, updateProgress ->
      val (assets, scene) = orchestrator.runAssetAndSceneExtraction(project, token, updateProgress)
      refreshStorageStats()
      _statusBanner.value = "Indexed ${assets.size} assets, ${scene.rootNodes.size} scene objects"
    }
  }

  fun runFullPipeline(project: ProjectEntity) {
    jobCoordinator.launchJob(
      projectId = project.id,
      jobType = JobType.EXTRACT_ASSETS,
      title = "Full Pipeline: ${project.displayName}",
    ) { token, updateProgress ->
      orchestrator.runFullCodeAnalysis(project, token) { pct, msg -> updateProgress(pct / 2, msg) }
      orchestrator.runAssetAndSceneExtraction(project, token) { pct, msg -> updateProgress(50 + pct / 2, msg) }
      val report = orchestrator.rebuildUnityProject(project)
      _lastRebuild.value = report
      refreshStorageStats()
      _statusBanner.value = "Full analysis & project rebuild finished (${report.scriptsWritten} scripts, ${report.assetsCopied} assets)"
    }
  }

  fun rebuildProject(project: ProjectEntity, mode: RebuildMode = RebuildMode.FULL_PROJECT) {
    jobCoordinator.launchJob(
      projectId = project.id,
      jobType = JobType.REBUILD_PROJECT,
      title = if (mode == RebuildMode.CODE_ONLY) "Recover Code Only: ${project.displayName}" else "Rebuild Project: ${project.displayName}",
    ) { _, updateProgress ->
      updateProgress(35, if (mode == RebuildMode.CODE_ONLY) "Extracting game scripts only (no assets/libraries)..." else "Generating RecoveredProject structure...")
      val report = orchestrator.rebuildUnityProject(project, mode)
      _lastRebuild.value = report
      refreshStorageStats()
      updateProgress(100, "Output ready at ${report.outputDirectory}")
      _statusBanner.value =
        if (mode == RebuildMode.CODE_ONLY) {
          "Recovered ${report.scriptsWritten} game scripts (${report.excludedLibraryCount} libraries excluded)"
        } else {
          "Project rebuilt (${report.scriptsWritten} scripts, ${report.assetsCopied} assets)"
        }
    }
  }

  fun exportProjectZip(project: ProjectEntity) {
    jobCoordinator.launchJob(
      projectId = project.id,
      jobType = JobType.EXPORT_PROJECT,
      title = "Export ZIP: ${project.displayName}",
    ) { token, updateProgress ->
      val res = orchestrator.exportProjectArchive(project, token, updateProgress)
      _lastExport.value = res.zipFile.absolutePath to res.sha256
      refreshStorageStats()
      _statusBanner.value = "Exported ${res.zipFile.name} (${res.fileCount} files)"
    }
  }

  fun copyExportedZipToSaf(destinationUri: Uri) {
    val zipPath = _lastExport.value.first ?: return
    val srcFile = File(zipPath)
    if (!srcFile.exists()) return
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openOutputStream(destinationUri)?.use { os ->
          srcFile.inputStream().use { it.copyTo(os, 65_536) }
        }
      }.onSuccess {
        _statusBanner.value = "Saved ${srcFile.name} to user-selected destination."
      }.onFailure {
        _statusBanner.value = "Export failed: ${it.message}"
      }
    }
  }

  fun exportSingleAsset(asset: AssetEntryEntity, project: ProjectEntity, format: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val res = orchestrator.exportAssetToRequestedFormat(asset, project, format)
      _statusBanner.value =
        res.fold(
          onSuccess = { "Exported ${it.name} to ${it.parentFile?.name}" },
          onFailure = { "Export note: ${it.message}" },
        )
    }
  }

  fun cancelJob(jobId: String) = jobCoordinator.cancelJob(jobId)

  fun pauseJob(jobId: String) = jobCoordinator.pauseJob(jobId)

  fun resumeJob(jobId: String) = jobCoordinator.resumeJob(jobId)

  fun installTool(moduleId: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val res = toolManager.installOrUpdateOptionalModule(moduleId)
      _statusBanner.value =
        res.fold(
          onSuccess = { "Installed ${it.name} v${it.version}" },
          onFailure = { "Install failed: ${it.message}" },
        )
    }
  }

  fun uninstallTool(moduleId: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val res = toolManager.uninstallOptionalModule(moduleId)
      _statusBanner.value =
        res.fold(
          onSuccess = { "Uninstalled optional module." },
          onFailure = { "Cannot uninstall: ${it.message}" },
        )
    }
  }

  fun installOfflineToolZip(uri: Uri, expectedSha256: String?) {
    viewModelScope.launch(Dispatchers.IO) {
      val tmp = File(workspaceManager.tempRoot, "offline_mod_${System.currentTimeMillis()}.zip")
      runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
          FileOutputStream(tmp).use { fos -> input.copyTo(fos, 32_768) }
        } ?: error("Cannot read selected offline package.")
        toolManager.installOfflinePackage(tmp, expectedSha256).getOrThrow()
      }.onSuccess {
        _statusBanner.value = "Verified & installed offline module ${it.name} v${it.version}"
      }.onFailure {
        _statusBanner.value = "Offline install rejected: ${it.message}"
      }
      tmp.delete()
    }
  }

  fun checkRemoteCatalog(url: String) {
    if (uiState.value.settings.offlineFirstMode) {
      _statusBanner.value = "Offline-First Mode is enabled; disable it in Settings to fetch remote catalogs."
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      val res = toolManager.checkRemoteManifest(url)
      _statusBanner.value =
        res.fold(
          onSuccess = { "Synced $it module definitions from manifest." },
          onFailure = { "Manifest sync failed: ${it.message}" },
        )
    }
  }

  fun setLanguage(language: AppLanguage) {
    viewModelScope.launch { settingsStore.setLanguage(language) }
  }

  fun setOfflineFirst(enabled: Boolean) {
    viewModelScope.launch { settingsStore.setOfflineFirstMode(enabled) }
  }

  fun setStreamingBufferKb(kb: Int) {
    viewModelScope.launch { settingsStore.setStreamingBufferKb(kb) }
  }

  fun clearTempWorkspace() {
    viewModelScope.launch(Dispatchers.IO) {
      val freed = workspaceManager.clearTempCache()
      refreshStorageStats()
      _statusBanner.value = "Cleaned temporary cache (${com.reu.core.common.FormatUtils.formatBytes(freed)} freed)."
    }
  }

  fun refreshStorageStats() {
    _storageStats.value = workspaceManager.getStorageStats()
  }

  class Factory(
    private val context: Context,
    private val repository: ReuRepository,
    private val workspaceManager: WorkspaceManager,
    private val settingsStore: SettingsDataStore,
    private val logger: ReuLogger,
    private val jobCoordinator: JobCoordinator,
    private val toolManager: ToolModuleManager,
    private val orchestrator: ReuPipelineOrchestrator,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return ReuMainViewModel(
        context,
        repository,
        workspaceManager,
        settingsStore,
        logger,
        jobCoordinator,
        toolManager,
        orchestrator,
      ) as T
    }
  }
}
