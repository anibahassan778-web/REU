package com.reu.engine.scene

import com.reu.core.common.AssetKind
import com.reu.core.common.FormatUtils
import com.reu.engine.asset.ParsedGameObject
import com.reu.engine.asset.ParsedMonoBehaviour
import com.reu.engine.asset.ParsedMonoScript
import com.reu.engine.asset.ParsedTransform
import com.reu.engine.asset.ParsedUnityObject
import com.reu.engine.asset.UnityObjectDecoders
import java.io.File
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

data class ParsedSceneContent(
  val containerFile: File,
  val sourcePath: String,
  val sceneDisplayName: String,
  val bigEndian: Boolean,
  val objects: List<ParsedUnityObject>,
)

class SceneEngine {
  fun buildSceneSummary(
    objects: List<ParsedUnityObject>,
    discoveredSceneNames: List<String> = emptyList(),
  ): SceneGraphSummary {
    val sceneSources =
      discoveredSceneNames.ifEmpty {
        objects.map { it.sourceFile }.filter { it.contains("level") || it.endsWith(".unity") }.distinct()
      }
    val gameObjects = objects.filter { it.classId == 1 }
    val cameras = objects.count { it.classId == 20 }
    val lights = objects.count { it.classId == 108 }

    val rootNodes =
      gameObjects.take(500).mapIndexed { index, go ->
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
          shaderRef = "Extracted / Parsed Material",
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

  /**
   * Reconstructs a real Unity YAML (.unity) scene file from a parsed `levelN` SerializedFile,
   * preserving GameObjects, Transform/RectTransform parent-child hierarchies, Cameras, Canvases,
   * Renderers, and MonoBehaviour component script names resolved via MonoScript catalog.
   */
  fun exportSceneToUnityYaml(
    scene: ParsedSceneContent,
    monoScriptsByPathId: Map<Long, ParsedMonoScript>,
    outputDir: File,
  ): Pair<File, List<SceneNode>> {
    if (!outputDir.exists()) outputDir.mkdirs()
    val byPathId = scene.objects.associateBy { it.pathId }
    val gameObjects = mutableMapOf<Long, ParsedGameObject>()
    val transforms = mutableMapOf<Long, ParsedTransform>()
    val monoBehaviours = mutableMapOf<Long, ParsedMonoBehaviour>()

    for (obj in scene.objects) {
      val bytes = UnityObjectDecoders.readObjectSlice(scene.containerFile, obj) ?: continue
      when (obj.classId) {
        1 -> UnityObjectDecoders.parseGameObject(obj.pathId, bytes, scene.bigEndian)?.let { gameObjects[obj.pathId] = it }
        4 -> UnityObjectDecoders.parseTransform(obj.pathId, false, bytes, scene.bigEndian)?.let { transforms[obj.pathId] = it }
        224 -> UnityObjectDecoders.parseTransform(obj.pathId, true, bytes, scene.bigEndian)?.let { transforms[obj.pathId] = it }
        114 -> UnityObjectDecoders.parseMonoBehaviour(obj.pathId, bytes, scene.bigEndian)?.let { monoBehaviours[obj.pathId] = it }
      }
    }

    val baseFileName =
      scene.sceneDisplayName
        .substringAfterLast('/')
        .removeSuffix(".unity")
        .let { FormatUtils.sanitizeFileName(it) }
    val outFile = File(outputDir, "$baseFileName.unity")

    val yaml =
      buildString {
        appendLine("%YAML 1.1")
        appendLine("%TAG !u! tag:unity3d.com,2011:")
        appendLine("# Reconstructed by REU SceneEngine from ${scene.sourcePath}")

        for (go in gameObjects.values) {
          appendLine("--- !u!1 &${go.pathId}")
          appendLine("GameObject:")
          appendLine("  m_ObjectHideFlags: 0")
          appendLine("  m_CorrespondingSourceObject: {fileID: 0}")
          appendLine("  m_PrefabInstance: {fileID: 0}")
          appendLine("  m_PrefabAsset: {fileID: 0}")
          appendLine("  serializedVersion: 6")
          appendLine("  m_Component:")
          for (compRef in go.componentRefs) {
            appendLine("  - component: {fileID: ${compRef.pathId}}")
          }
          appendLine("  m_Layer: ${go.layer}")
          appendLine("  m_Name: ${go.name}")
          appendLine("  m_TagString: Untagged")
          appendLine("  m_Icon: {fileID: 0}")
          appendLine("  m_NavMeshLayer: 0")
          appendLine("  m_StaticEditorFlags: 0")
          appendLine("  m_IsActive: ${if (go.isActive) 1 else 0}")
        }

        for (tr in transforms.values) {
          val tag = if (tr.isRectTransform) 224 else 4
          val typeName = if (tr.isRectTransform) "RectTransform" else "Transform"
          appendLine("--- !u!$tag &${tr.pathId}")
          appendLine("$typeName:")
          appendLine("  m_ObjectHideFlags: 0")
          appendLine("  m_GameObject: {fileID: ${tr.gameObject.pathId}}")
          appendLine("  m_LocalRotation: {x: ${tr.rotation[0]}, y: ${tr.rotation[1]}, z: ${tr.rotation[2]}, w: ${tr.rotation[3]}}")
          appendLine("  m_LocalPosition: {x: ${tr.position[0]}, y: ${tr.position[1]}, z: ${tr.position[2]}}")
          appendLine("  m_LocalScale: {x: ${tr.scale[0]}, y: ${tr.scale[1]}, z: ${tr.scale[2]}}")
          if (tr.childrenRefs.isEmpty()) {
            appendLine("  m_Children: []")
          } else {
            appendLine("  m_Children:")
            for (ch in tr.childrenRefs) {
              appendLine("  - {fileID: ${ch.pathId}}")
            }
          }
          appendLine("  m_Father: {fileID: ${tr.fatherRef.pathId}}")
          appendLine("  m_RootOrder: ${tr.rootOrder}")
        }

        for (obj in scene.objects) {
          if (obj.classId == 1 || obj.classId == 4 || obj.classId == 224) continue
          val mb = monoBehaviours[obj.pathId]
          appendLine("--- !u!${obj.classId} &${obj.pathId}")
          if (mb != null) {
            val scriptInfo = monoScriptsByPathId[mb.scriptRef.pathId]
            val scriptLabel =
              if (scriptInfo != null) {
                val ns = scriptInfo.namespaceName.takeIf { it.isNotBlank() }?.let { "$it." } ?: ""
                "$ns${scriptInfo.className} (${scriptInfo.assemblyName})"
              } else {
                "fileID=${mb.scriptRef.fileId}, pathID=${mb.scriptRef.pathId}"
              }
            appendLine("MonoBehaviour:")
            appendLine("  m_ObjectHideFlags: 0")
            appendLine("  m_GameObject: {fileID: ${mb.gameObject.pathId}}")
            appendLine("  m_Enabled: ${if (mb.enabled) 1 else 0}")
            appendLine("  m_EditorHideFlags: 0")
            appendLine("  m_Script: {fileID: ${mb.scriptRef.pathId}} # $scriptLabel")
            appendLine("  m_Name: ${mb.name}")
          } else {
            appendLine("${obj.unityTypeName}:")
            appendLine("  m_ObjectHideFlags: 0")
            appendLine("  m_Name: ${obj.name}")
          }
        }
      }

    outFile.writeText(yaml)

    // Build real parent-child SceneNode hierarchy from Transform tree
    val transformByGo = transforms.values.associateBy { it.gameObject.pathId }
    fun toNode(go: ParsedGameObject): SceneNode {
      val tr = transformByGo[go.pathId]
      val parentGoId =
        tr?.fatherRef?.pathId?.takeIf { it != 0L }?.let { parentTrId ->
          transforms[parentTrId]?.gameObject?.pathId
        }
      val compNames =
        go.componentRefs.mapNotNull { ref ->
          val cObj = byPathId[ref.pathId] ?: return@mapNotNull null
          val mb = monoBehaviours[cObj.pathId]
          if (mb != null) {
            monoScriptsByPathId[mb.scriptRef.pathId]?.className ?: "MonoBehaviour"
          } else {
            cObj.unityTypeName
          }
        }
      val childNodes =
        tr?.childrenRefs?.mapNotNull { childTrRef ->
          val childTr = transforms[childTrRef.pathId] ?: return@mapNotNull null
          val childGo = gameObjects[childTr.gameObject.pathId] ?: return@mapNotNull null
          toNode(childGo)
        } ?: emptyList()

      return SceneNode(
        pathId = go.pathId,
        name = go.name,
        nodeType = if (tr?.isRectTransform == true) "UI GameObject" else "GameObject",
        parentPathId = parentGoId,
        components = compNames,
        children = childNodes,
      )
    }

    val rootNodes =
      gameObjects.values
        .filter { go ->
          val tr = transformByGo[go.pathId]
          tr == null || tr.fatherRef.pathId == 0L
        }
        .map { toNode(it) }

    return outFile to rootNodes
  }
}
