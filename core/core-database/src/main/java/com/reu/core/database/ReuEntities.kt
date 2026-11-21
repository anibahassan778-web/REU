package com.reu.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.reu.core.common.AssetKind
import com.reu.core.common.DataProvenance
import com.reu.core.common.ImportSourceType
import com.reu.core.common.JobStatus
import com.reu.core.common.JobType
import com.reu.core.common.UnityScriptingBackend

@Entity(
  tableName = "projects",
  indices = [Index(value = ["sourceSha256"]), Index(value = ["updatedAtMs"])],
)
data class ProjectEntity(
  @PrimaryKey val id: String,
  val displayName: String,
  val packageName: String?,
  val sourceType: ImportSourceType,
  val sourceFileName: String,
  val sourcePath: String,
  val sourceSizeBytes: Long,
  val sourceSha256: String,
  val workspacePath: String,
  val unityVersion: String?,
  val unityVersionEvidence: String?,
  val scriptingBackend: UnityScriptingBackend,
  val architecturesJson: String,
  val detectionReportJson: String?,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

@Entity(
  tableName = "analysis_jobs",
  indices = [Index(value = ["projectId"]), Index(value = ["status"])],
)
data class AnalysisJobEntity(
  @PrimaryKey val id: String,
  val projectId: String?,
  val jobType: JobType,
  val title: String,
  val status: JobStatus,
  val progressPercent: Int,
  val detailMessage: String,
  val errorMessage: String?,
  val startedAtMs: Long,
  val updatedAtMs: Long,
  val finishedAtMs: Long?,
)

@Entity(
  tableName = "assets",
  indices = [Index(value = ["projectId"]), Index(value = ["kind"]), Index(value = ["pathId"])],
)
data class AssetEntryEntity(
  @PrimaryKey val id: String,
  val projectId: String,
  val pathId: Long,
  val name: String,
  val originalPath: String?,
  val kind: AssetKind,
  val unityTypeName: String,
  val sourceArchivePath: String,
  val byteOffset: Long,
  val byteSize: Long,
  val provenance: DataProvenance,
  val extractedFilePath: String?,
  val previewMetadataJson: String?,
  val extractionStatus: String,
)

@Entity(
  tableName = "code_symbols",
  indices = [Index(value = ["projectId"]), Index(value = ["assemblyName"]), Index(value = ["namespaceName"])],
)
data class CodeSymbolEntity(
  @PrimaryKey val id: String,
  val projectId: String,
  val assemblyName: String,
  val namespaceName: String,
  val typeName: String,
  val memberSignature: String,
  val symbolKind: String,
  val rvaAddress: String?,
  val sourcePreview: String,
  val provenance: DataProvenance,
)

@Entity(
  tableName = "dependency_edges",
  indices = [Index(value = ["projectId"]), Index(value = ["sourceAssetId"])],
)
data class DependencyEdgeEntity(
  @PrimaryKey val id: String,
  val projectId: String,
  val sourceAssetId: String,
  val targetIdentifier: String,
  val targetAssetId: String?,
  val relationType: String,
  val isMissing: Boolean,
)

@Entity(tableName = "tool_modules")
data class ToolModuleEntity(
  @PrimaryKey val id: String,
  val name: String,
  val version: String,
  val availableVersion: String,
  val description: String,
  val supportedArchs: String,
  val minAndroidApi: Int,
  val dependenciesJson: String,
  val sha256Checksum: String,
  val licenseName: String,
  val sourceUrl: String,
  val isOptional: Boolean,
  val isInstalled: Boolean,
  val installedPath: String?,
  val compatibilityNote: String,
  val updatedAtMs: Long,
)
