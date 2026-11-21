package com.reu.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
  @Query("SELECT * FROM projects ORDER BY updatedAtMs DESC")
  fun observeAllProjects(): Flow<List<ProjectEntity>>

  @Query("SELECT * FROM projects WHERE id = :projectId")
  fun observeProject(projectId: String): Flow<ProjectEntity?>

  @Query("SELECT * FROM projects WHERE id = :projectId")
  suspend fun getProject(projectId: String): ProjectEntity?

  @Query("SELECT * FROM projects WHERE sourceSha256 = :sha256 LIMIT 1")
  suspend fun findBySha256(sha256: String): ProjectEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(project: ProjectEntity)

  @Query("DELETE FROM projects WHERE id = :projectId")
  suspend fun deleteById(projectId: String)
}

@Dao
interface JobDao {
  @Query("SELECT * FROM analysis_jobs ORDER BY startedAtMs DESC")
  fun observeAllJobs(): Flow<List<AnalysisJobEntity>>

  @Query("SELECT * FROM analysis_jobs WHERE projectId = :projectId ORDER BY startedAtMs DESC")
  fun observeJobsForProject(projectId: String): Flow<List<AnalysisJobEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(job: AnalysisJobEntity)

  @Query("DELETE FROM analysis_jobs WHERE projectId = :projectId")
  suspend fun deleteForProject(projectId: String)
}

@Dao
interface AssetDao {
  @Query("SELECT * FROM assets WHERE projectId = :projectId ORDER BY kind, name")
  fun observeAssetsForProject(projectId: String): Flow<List<AssetEntryEntity>>

  @Query("SELECT * FROM assets WHERE projectId = :projectId")
  suspend fun getAssetsForProject(projectId: String): List<AssetEntryEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(assets: List<AssetEntryEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(asset: AssetEntryEntity)

  @Query("DELETE FROM assets WHERE projectId = :projectId")
  suspend fun deleteForProject(projectId: String)
}

@Dao
interface CodeSymbolDao {
  @Query("SELECT * FROM code_symbols WHERE projectId = :projectId ORDER BY assemblyName, namespaceName, typeName")
  fun observeSymbolsForProject(projectId: String): Flow<List<CodeSymbolEntity>>

  @Query("SELECT * FROM code_symbols WHERE projectId = :projectId")
  suspend fun getSymbolsForProject(projectId: String): List<CodeSymbolEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(symbols: List<CodeSymbolEntity>)

  @Query("DELETE FROM code_symbols WHERE projectId = :projectId")
  suspend fun deleteForProject(projectId: String)
}

@Dao
interface DependencyDao {
  @Query("SELECT * FROM dependency_edges WHERE projectId = :projectId")
  fun observeEdgesForProject(projectId: String): Flow<List<DependencyEdgeEntity>>

  @Query("SELECT * FROM dependency_edges WHERE projectId = :projectId")
  suspend fun getEdgesForProject(projectId: String): List<DependencyEdgeEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(edges: List<DependencyEdgeEntity>)

  @Query("DELETE FROM dependency_edges WHERE projectId = :projectId")
  suspend fun deleteForProject(projectId: String)
}

@Dao
interface ToolModuleDao {
  @Query("SELECT * FROM tool_modules ORDER BY isInstalled DESC, name ASC")
  fun observeAllTools(): Flow<List<ToolModuleEntity>>

  @Query("SELECT * FROM tool_modules")
  suspend fun getAllTools(): List<ToolModuleEntity>

  @Query("SELECT * FROM tool_modules WHERE id = :toolId")
  suspend fun getTool(toolId: String): ToolModuleEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(tools: List<ToolModuleEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(tool: ToolModuleEntity)
}
