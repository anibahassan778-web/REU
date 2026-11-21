package com.example

import android.app.Application
import com.reu.core.database.ReuDatabase
import com.reu.core.database.ReuRepository
import com.reu.core.jobs.JobCoordinator
import com.reu.core.logging.ReuLogger
import com.reu.core.storage.SettingsDataStore
import com.reu.core.storage.WorkspaceManager
import com.reu.features.pipeline.ReuPipelineOrchestrator
import com.reu.features.state.ReuMainViewModel
import com.reu.integrations.tools.ToolModuleManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class ReuApplication : Application() {
  lateinit var database: ReuDatabase
    private set
  lateinit var repository: ReuRepository
    private set
  lateinit var workspaceManager: WorkspaceManager
    private set
  lateinit var settingsDataStore: SettingsDataStore
    private set
  lateinit var logger: ReuLogger
    private set
  lateinit var jobCoordinator: JobCoordinator
    private set
  lateinit var toolModuleManager: ToolModuleManager
    private set
  lateinit var orchestrator: ReuPipelineOrchestrator
    private set

  override fun onCreate() {
    super.onCreate()
    workspaceManager = WorkspaceManager(this)
    logger = ReuLogger().apply { attachLogDirectory(workspaceManager.logsRoot) }
    installCrashHandler(workspaceManager.logsRoot)

    database = ReuDatabase.build(this)
    repository = ReuRepository(database)
    settingsDataStore = SettingsDataStore(this)
    jobCoordinator = JobCoordinator(repository, logger)
    toolModuleManager = ToolModuleManager(repository, workspaceManager.toolsRoot, logger)
    orchestrator = ReuPipelineOrchestrator(this, repository, workspaceManager, logger)

    logger.info("App", "REU initialized cleanly")
  }

  fun viewModelFactory(): ReuMainViewModel.Factory =
    ReuMainViewModel.Factory(
      context = this,
      repository = repository,
      workspaceManager = workspaceManager,
      settingsStore = settingsDataStore,
      logger = logger,
      jobCoordinator = jobCoordinator,
      toolManager = toolModuleManager,
      orchestrator = orchestrator,
    )

  private fun installCrashHandler(logsRoot: File) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      runCatching {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        File(logsRoot, "crash_last.txt").writeText("Thread: ${thread.name}\n$sw")
      }
      previous?.uncaughtException(thread, throwable)
    }
  }
}
