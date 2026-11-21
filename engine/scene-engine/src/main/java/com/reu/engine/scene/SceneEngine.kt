package com.reu.engine.scene

import com.reu.core.common.AssetKind
import com.reu.engine.asset.ParsedUnityObject
import kotlinx.serialization.Serializable

@Serializable
data class SceneNode(
  val pathId: Long,
  val name: String,
  val nodeType: String,
  val parentPathId: Long?,
  val components: List<String>,
  val children: List<SceneNode> = emptyList(),
)

@Serializable
data class MaterialInspection(
  val pathId: Long,
  val name: String,
  val shaderRef: String,
  val properties: Map<String, String>,
)

@Serializable
data class AnimationInspection(
  val pathId: Long,
  val name: String,
  val sourceFile: String,
  val byteSize: Long,
)

@Serializable
data class ScriptableObjectInspection(
  val pathId: Long,
  val name: String,
  val sourceFile: String,
  val byteSize: Long,
)

@Serializable
data class SceneGraphSummary(
  val sceneNames: List<String>,
  val rootNodes: List<SceneNode>,
  val materials: List<MaterialInspection>,
  val animations: List<AnimationInspection>,
  val scriptableObjects: List<ScriptableObjectInspection>,
  val camerasCount: Int,
  val lightsCount: Int,
)

class SceneEngine {
  fun buildSceneSummary(objects: List<ParsedUnityObject>): SceneGraphSummary {
    val sceneSources = objects.map { it.sourceFile }.filter { it.contains("level") || it.endsWith(".unity") }.distinct()
    val gameObjects = objects.filter { it.classId == 1 }
    val transforms = objects.filter { it.classId == 4 }
    val cameras = objects.count { it.classId == 20 }
    val lights = objects.count { it.classId == 108 }

    val rootNodes =
      gameObjects.take(200).mapIndexed { index, go ->
        val attached = mutableListOf("Transform")
        if (index == 0 && cameras > 0) attached.add("Camera")
        if (index == 0 && lights > 0) attached.add("Light")
        SceneNode(
          pathId = go.pathId,
          name = go.name,
          nodeType = go.unityTypeName,
          parentPathId = null,
          components = attached,
        )
      }

    val materials =
      objects.filter { it.assetKind == AssetKind.MATERIAL }.map { mat ->
        MaterialInspection(
          pathId = mat.pathId,
          name = mat.name,
          shaderRef = "Standard / Unresolved Shader Ref",
          properties = mapOf("SourceArchive" to mat.sourceFile, "ByteSize" to "${mat.byteSize} B"),
        )
      }

    val animations =
      objects.filter { it.assetKind == AssetKind.ANIMATION }.map { anim ->
        AnimationInspection(anim.pathId, anim.name, anim.sourceFile, anim.byteSize)
      }

    val scriptables =
      objects.filter { it.assetKind == AssetKind.SCRIPTABLE_OBJECT }.map { so ->
        ScriptableObjectInspection(so.pathId, so.name, so.sourceFile, so.byteSize)
      }

    return SceneGraphSummary(
      sceneNames = sceneSources.ifEmpty { listOf("Serialized Scene Objects") },
      rootNodes = rootNodes,
      materials = materials,
      animations = animations,
      scriptableObjects = scriptables,
      camerasCount = cameras,
      lightsCount = lights,
    )
  }
}
