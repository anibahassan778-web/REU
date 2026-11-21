package com.reu.core.database

import kotlinx.coroutines.flow.Flow

class ReuRepository(private val db: ReuDatabase) {
  val allProjects: Flow<List<ProjectEntity>> = db.projectDao().observeAllProjects()
  val allJobs: Flow<List<AnalysisJobEntity>> = db.jobDao().observeAllJobs()
  val allTools: Flow<List<ToolModuleEntity>> = db.toolModuleDao().observeAllTools()

  fun observeProject(projectId: String): Flow<ProjectEntity?> = db.projectDao().observeProject(projectId)

  suspend fun getProject(projectId: String): ProjectEntity? = db.projectDao().getProject(projectId)

  suspend fun findProjectBySha256(sha256: String): ProjectEntity? = db.projectDao().findBySha256(sha256)

  suspend fun upsertProject(project: ProjectEntity) = db.projectDao().upsert(project)

  suspend fun deleteProjectData(projectId: String) {
    db.dependencyDao().deleteForProject(projectId)
    db.codeSymbolDao().deleteForProject(projectId)
    db.assetDao().deleteForProject(projectId)
    db.jobDao().deleteForProject(projectId)
    db.projectDao().deleteById(projectId)
  }

  fun observeJobsForProject(projectId: String): Flow<List<AnalysisJobEntity>> =
    db.jobDao().observeJobsForProject(projectId)

  suspend fun upsertJob(job: AnalysisJobEntity) = db.jobDao().upsert(job)

  fun observeAssetsForProject(projectId: String): Flow<List<AssetEntryEntity>> =
    db.assetDao().observeAssetsForProject(projectId)

  suspend fun getAssetsForProject(projectId: String): List<AssetEntryEntity> =
    db.assetDao().getAssetsForProject(projectId)

  suspend fun replaceAssetsForProject(projectId: String, assets: List<AssetEntryEntity>) {
    db.assetDao().deleteForProject(projectId)
    if (assets.isNotEmpty()) db.assetDao().insertAll(assets)
  }

  suspend fun upsertAsset(asset: AssetEntryEntity) = db.assetDao().upsert(asset)

  fun observeSymbolsForProject(projectId: String): Flow<List<CodeSymbolEntity>> =
    db.codeSymbolDao().observeSymbolsForProject(projectId)

  suspend fun getSymbolsForProject(projectId: String): List<CodeSymbolEntity> =
    db.codeSymbolDao().getSymbolsForProject(projectId)

  suspend fun replaceSymbolsForProject(projectId: String, symbols: List<CodeSymbolEntity>) {
    db.codeSymbolDao().deleteForProject(projectId)
    if (symbols.isNotEmpty()) db.codeSymbolDao().insertAll(symbols)
  }

  fun observeEdgesForProject(projectId: String): Flow<List<DependencyEdgeEntity>> =
    db.dependencyDao().observeEdgesForProject(projectId)

  suspend fun getEdgesForProject(projectId: String): List<DependencyEdgeEntity> =
    db.dependencyDao().getEdgesForProject(projectId)

  suspend fun replaceEdgesForProject(projectId: String, edges: List<DependencyEdgeEntity>) {
    db.dependencyDao().deleteForProject(projectId)
    if (edges.isNotEmpty()) db.dependencyDao().insertAll(edges)
  }

  suspend fun getAllTools(): List<ToolModuleEntity> = db.toolModuleDao().getAllTools()

  suspend fun getTool(toolId: String): ToolModuleEntity? = db.toolModuleDao().getTool(toolId)

  suspend fun upsertTools(tools: List<ToolModuleEntity>) = db.toolModuleDao().upsertAll(tools)

  suspend fun upsertTool(tool: ToolModuleEntity) = db.toolModuleDao().upsert(tool)
}
