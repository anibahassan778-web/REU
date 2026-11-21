package com.reu.engine.dependency

import com.reu.core.database.AssetEntryEntity
import com.reu.core.database.DependencyEdgeEntity
import java.util.UUID

data class DependencyReport(
  val totalAssets: Int,
  val edges: List<DependencyEdgeEntity>,
  val missingReferencesCount: Int,
  val duplicateNames: List<String>,
)

class DependencyEngine {
  fun buildGraph(
    projectId: String,
    assets: List<AssetEntryEntity>,
    explicitRefs: Map<String, List<Long>> = emptyMap(),
  ): DependencyReport {
    val byPathId = assets.associateBy { it.pathId }
    val edges = mutableListOf<DependencyEdgeEntity>()
    var missingCount = 0

    for (asset in assets) {
      val refs = explicitRefs[asset.id].orEmpty()
      for (targetPathId in refs) {
        val resolved = byPathId[targetPathId]
        val isMissing = resolved == null
        if (isMissing) missingCount++
        edges.add(
          DependencyEdgeEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            sourceAssetId = asset.id,
            targetIdentifier = "pathID:$targetPathId",
            targetAssetId = resolved?.id,
            relationType = "PPtrReference",
            isMissing = isMissing,
          ),
        )
      }
    }

    val duplicates =
      assets.groupBy { "${it.kind}:${it.name}" }
        .filter { it.value.size > 1 }
        .keys
        .sorted()

    return DependencyReport(
      totalAssets = assets.size,
      edges = edges,
      missingReferencesCount = missingCount,
      duplicateNames = duplicates,
    )
  }
}
