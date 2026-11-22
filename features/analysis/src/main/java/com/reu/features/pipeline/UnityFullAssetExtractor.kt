package com.reu.features.pipeline

import com.reu.core.common.AssetKind
import com.reu.core.common.DataProvenance
import com.reu.core.common.FormatUtils
import com.reu.core.database.AssetEntryEntity
import com.reu.core.jobs.JobControlToken
import com.reu.core.security.PathTraversalGuard
import com.reu.engine.asset.ParsedMonoScript
import com.reu.engine.asset.ParsedUnityObject
import com.reu.engine.asset.SerializedFileSummary
import com.reu.engine.asset.UnityAssetParser
import com.reu.engine.asset.UnityObjectDecoders
import com.reu.engine.audio.AudioEngine
import com.reu.engine.model.ModelEngine
import com.reu.engine.scene.ParsedSceneContent
import com.reu.engine.scene.SceneEngine
import com.reu.engine.scene.SceneGraphSummary
import com.reu.engine.scene.SceneNode
import com.reu.engine.texture.TextureEngine
import com.reu.engine.texture.TextureExportFormat
import java.io.File

data class FullExtractionOutcome(
  val parsedObjects: List<ParsedUnityObject>,
  val assetEntities: List<AssetEntryEntity>,
  val sceneGraph: SceneGraphSummary,
  val containersScanned: Int,
)

class UnityFullAssetExtractor(
  private val assetParser: UnityAssetParser,
  private val textureEngine: TextureEngine,
  private val audioEngine: AudioEngine,
  private val modelEngine: ModelEngine,
  private val sceneEngine: SceneEngine,
) {
  suspend fun extractAll(
    projectId: String,
    extractedDir: File,
    outAssetsDir: File,
    bundlePaths: List<String>,
    rawAssetAndLevelPaths: List<String>,
    streamingAssetPaths: List<String>,
    token: JobControlToken,
    onProgress: suspend (Int, String) -> Unit,
  ): FullExtractionOutcome {
    if (!outAssetsDir.exists()) outAssetsDir.mkdirs()
    val unpackedRoot = File(extractedDir, "_unpacked").apply { mkdirs() }
    val assetEntities = mutableListOf<AssetEntryEntity>()
    val parsedObjects = mutableListOf<ParsedUnityObject>()
    val usedOutputPaths = HashSet<String>()

    // 1. Unpack all UnityFS bundles (data.unity3d, *.bundle, *.unity3d)
    val allBundleCandidates =
      (bundlePaths + rawAssetAndLevelPaths.filter { it.endsWith("data.unity3d", ignoreCase = true) }).distinct()
    val unpackedFiles = mutableListOf<File>()
    for (relBundle in allBundleCandidates) {
      token.checkPoint()
      val bundleFile = File(extractedDir, relBundle)
      if (!bundleFile.exists()) continue
      val bundleOut = File(unpackedRoot, FormatUtils.sanitizeFileName(relBundle))
      val res = assetParser.unpackUnityFs(bundleFile, bundleOut)
      if (res != null) {
        unpackedFiles.addAll(res.unpackedFiles)
      }
    }

    onProgress(30, "Indexed ${unpackedFiles.size} unpacked UnityFS entries; parsing SerializedFiles...")

    // 2. Gather all SerializedFile candidates (direct + unpacked), excluding .resS/.resource stream containers
    val directContainers =
      rawAssetAndLevelPaths
        .mapNotNull { rel ->
          val f = File(extractedDir, rel)
          if (f.exists() && f.isFile && !isStreamOrBundleFile(f.name)) rel to f else null
        }
    val unpackedContainers =
      unpackedFiles
        .filter { it.exists() && it.isFile && !isStreamOrBundleFile(it.name) }
        .map { f -> "_unpacked/${f.relativeTo(unpackedRoot).path.replace('\\', '/')}" to f }

    val allSerializedFiles = (unpackedContainers + directContainers).distinctBy { it.second.canonicalPath }

    // 3. First pass: parse SerializedFile headers + global BuildSettings & MonoScript lookup tables
    val summaries = mutableListOf<Triple<String, File, SerializedFileSummary>>()
    val discoveredSceneNames = mutableListOf<String>()
    val monoScriptsByPathId = mutableMapOf<Long, ParsedMonoScript>()
    val shaderNamesByPathId = mutableMapOf<Long, String>()

    for ((idx, pair) in allSerializedFiles.withIndex()) {
      token.checkPoint()
      val (relPath, file) = pair
      val summary = assetParser.parseSerializedFile(file, relPath)
      if (summary.formatVersion <= 0 && summary.objects.isEmpty()) continue
      summaries.add(Triple(relPath, file, summary))

      for (obj in summary.objects) {
        when (obj.classId) {
          141 -> { // BuildSettings -> exact Assets/Scenes/<name>.unity order for level0..N
            UnityObjectDecoders.readObjectSlice(file, obj)?.let { slice ->
              val scenes = UnityObjectDecoders.parseBuildSettingsScenes(slice, summary.bigEndian)
              if (scenes.isNotEmpty() && discoveredSceneNames.isEmpty()) {
                discoveredSceneNames.addAll(scenes)
              }
            }
          }
          115 -> { // MonoScript
            UnityObjectDecoders.readObjectSlice(file, obj)?.let { slice ->
              UnityObjectDecoders.parseMonoScript(obj.pathId, slice, summary.bigEndian)?.let { ms ->
                monoScriptsByPathId[obj.pathId] = ms
              }
            }
          }
          48 -> { // Shader
            UnityObjectDecoders.readObjectSlice(file, obj)?.let { slice ->
              val (shaderName, _) = UnityObjectDecoders.parseShaderToReadableSource(slice, obj.name, summary.bigEndian)
              shaderNamesByPathId[obj.pathId] = shaderName
            }
          }
        }
      }
      if (allSerializedFiles.isNotEmpty()) {
        onProgress(35 + ((idx + 1) * 15) / allSerializedFiles.size, "Cataloged ${file.name}")
      }
    }

    // 4. Second pass: extract every Texture2D, Sprite, AudioClip, Mesh, Font, Shader, Material, TextAsset, Animation, and Scene
    val sceneNodesCollected = mutableListOf<SceneNode>()
    var entryCounter = 0

    for ((sIdx, triple) in summaries.withIndex()) {
      token.checkPoint()
      val (relPath, file, summary) = triple
      val decodedTexturesInFile = mutableMapOf<Long, Triple<Int, Int, ByteArray>>()
      val textureNamesByPathId = mutableMapOf<Long, String>()

      // Extract Texture2D objects first so Sprites in the same archive can crop from them
      val orderedObjects = summary.objects.sortedBy { if (it.classId == 28) 0 else 1 }

      for (obj in orderedObjects) {
        token.checkPoint()
        parsedObjects.add(obj)
        var resolvedName = obj.name
        var provenance = DataProvenance.ORIGINAL_RECOVERED
        var status = "Indexed (Serialized)"
        var extractedFile: File? = null

        val slice = UnityObjectDecoders.readObjectSlice(file, obj)
        if (slice != null) {
          when (obj.classId) {
            28 -> { // Texture2D
              val parsedTex = UnityObjectDecoders.parseTexture2D(slice, summary.bigEndian)
              if (parsedTex != null && parsedTex.width > 0 && parsedTex.height > 0) {
                resolvedName = parsedTex.name.ifBlank { obj.name }
                textureNamesByPathId[obj.pathId] = resolvedName
                val rawPixels =
                  UnityObjectDecoders.resolveStreamedBytes(
                    containerFile = file,
                    streamPath = parsedTex.streamPath,
                    offset = parsedTex.streamOffset,
                    size = parsedTex.streamSize,
                    inlineFallback = parsedTex.inlineImageData,
                  )
                val rgba = textureEngine.decodeUnityFormatToRgba(parsedTex.width, parsedTex.height, parsedTex.textureFormat, rawPixels)
                if (rgba != null) {
                  val topDown = textureEngine.flipRgbaVertical(parsedTex.width, parsedTex.height, rgba)
                  decodedTexturesInFile[obj.pathId] = Triple(parsedTex.width, parsedTex.height, topDown)
                  val texOutDir = File(outAssetsDir, "Textures").apply { mkdirs() }
                  val targetFile = uniqueTarget(texOutDir, "${FormatUtils.sanitizeFileName(resolvedName)}.png", usedOutputPaths)
                  val res =
                    textureEngine.exportRgbaTexture(
                      name = targetFile.nameWithoutExtension,
                      width = parsedTex.width,
                      height = parsedTex.height,
                      rgbaPixels = topDown,
                      outputDir = texOutDir,
                      format = TextureExportFormat.PNG,
                      unityFormatName = TextureEngine.unityTextureFormatName(parsedTex.textureFormat),
                    )
                  if (res.success && res.exportedPath != null) {
                    extractedFile = File(res.exportedPath)
                    status = "Extracted (${parsedTex.width}x${parsedTex.height} ${res.unityFormat})"
                  }
                } else {
                  status = "Indexed (${parsedTex.width}x${parsedTex.height} ${TextureEngine.unityTextureFormatName(parsedTex.textureFormat)})"
                }
              } else {
                status = "Dynamic/Empty Texture2D"
              }
            }

            213 -> { // Sprite
              val sprite = UnityObjectDecoders.parseSprite(slice, summary.bigEndian)
              if (sprite != null) {
                resolvedName = sprite.name.ifBlank { obj.name }
                val parentTex = decodedTexturesInFile[sprite.textureRef.pathId]
                if (parentTex != null) {
                  val (tw, th, topDown) = parentTex
                  val rx = sprite.rectX.toInt().coerceIn(0, tw - 1)
                  val ry = sprite.rectY.toInt().coerceIn(0, th - 1)
                  val rw = sprite.rectW.toInt().coerceIn(1, tw - rx)
                  val rh = sprite.rectH.toInt().coerceIn(1, th - ry)
                  val cropped = textureEngine.cropRgba(topDown, tw, th, rx, ry, rw, rh)
                  if (cropped != null) {
                    val sprOutDir = File(outAssetsDir, "Sprites").apply { mkdirs() }
                    val targetFile = uniqueTarget(sprOutDir, "${FormatUtils.sanitizeFileName(resolvedName)}.png", usedOutputPaths)
                    val res =
                      textureEngine.exportRgbaTexture(
                        name = targetFile.nameWithoutExtension,
                        width = rw,
                        height = rh,
                        rgbaPixels = cropped,
                        outputDir = sprOutDir,
                        format = TextureExportFormat.PNG,
                        unityFormatName = "Sprite Crop",
                      )
                    if (res.success && res.exportedPath != null) {
                      extractedFile = File(res.exportedPath)
                      status = "Extracted Sprite (${rw}x${rh})"
                    }
                  }
                }
              }
            }

            83 -> { // AudioClip
              val clip = UnityObjectDecoders.parseAudioClip(slice, summary.bigEndian)
              if (clip != null) {
                resolvedName = clip.name.ifBlank { obj.name }
                val audioBytes =
                  UnityObjectDecoders.resolveStreamedBytes(
                    containerFile = file,
                    streamPath = clip.streamSource,
                    offset = clip.streamOffset,
                    size = clip.streamSize,
                    inlineFallback = clip.inlineData,
                  )
                if (audioBytes.isNotEmpty()) {
                  val audioDir = File(outAssetsDir, "Audio").apply { mkdirs() }
                  val res =
                    audioEngine.inspectAndExportAudioPayload(
                      name = FormatUtils.sanitizeFileName(resolvedName),
                      payload = audioBytes,
                      outputDir = audioDir,
                      fallbackChannels = clip.channels.coerceIn(1, 8),
                      fallbackSampleRate = clip.frequency.coerceIn(8000, 192000),
                      fallbackBitsPerSample = if (clip.compressionFormat == 0) clip.bitsPerSample else 0,
                    )
                  if (res.success && res.exportedPath != null) {
                    extractedFile = File(res.exportedPath)
                    status = "Extracted (${res.detectedCodec}, ${res.sampleRate}Hz)"
                  }
                }
              }
            }

            43 -> { // Mesh
              val mesh = UnityObjectDecoders.parseMesh(slice, obj.name, summary.bigEndian)
              if (mesh != null) {
                resolvedName = mesh.name
                val modelsDir = File(outAssetsDir, "Models").apply { mkdirs() }
                val objRes = modelEngine.exportToObj(mesh, modelsDir)
                modelEngine.exportToGltf(mesh, modelsDir)
                if (objRes.success && objRes.exportedPath != null) {
                  extractedFile = File(objRes.exportedPath)
                  provenance = DataProvenance.DECOMPILED_RECONSTRUCTED
                  status = "Extracted (${objRes.vertexCount} verts, ${objRes.triangleCount} tris)"
                }
              }
            }

            128 -> { // Font
              val font = UnityObjectDecoders.parseFont(slice, obj.name, summary.bigEndian)
              if (font != null) {
                resolvedName = font.name
                if (font.fontData.isNotEmpty()) {
                  val fontDir = File(outAssetsDir, "Fonts").apply { mkdirs() }
                  val ext = if (font.isOpenType) "otf" else "ttf"
                  val dest = uniqueTarget(fontDir, "${FormatUtils.sanitizeFileName(resolvedName)}.$ext", usedOutputPaths)
                  dest.writeBytes(font.fontData)
                  extractedFile = dest
                  status = "Extracted (${font.fontData.size} B ${ext.uppercase()})"
                } else {
                  status = "Dynamic/Bitmap Font (no embedded TTF)"
                }
              }
            }

            48 -> { // Shader
              val (shaderName, shaderSource) = UnityObjectDecoders.parseShaderToReadableSource(slice, obj.name, summary.bigEndian)
              resolvedName = shaderName
              val shaderDir = File(outAssetsDir, "Shaders").apply { mkdirs() }
              val safeShaderName = FormatUtils.sanitizeFileName(shaderName.replace('/', '_'))
              val dest = uniqueTarget(shaderDir, "$safeShaderName.shader", usedOutputPaths)
              dest.writeText(shaderSource)
              extractedFile = dest
              provenance = DataProvenance.DECOMPILED_RECONSTRUCTED
              status = "Reconstructed ShaderLab"
            }

            21 -> { // Material
              val (matName, yaml) =
                UnityObjectDecoders.parseMaterialToYaml(
                  objBytes = slice,
                  fallbackName = obj.name,
                  pathId = obj.pathId,
                  shaderNameLookup = { ptr -> shaderNamesByPathId[ptr.pathId] },
                  textureNameLookup = { ptr -> textureNamesByPathId[ptr.pathId] },
                  bigEndian = summary.bigEndian,
                )
              resolvedName = matName
              val matDir = File(outAssetsDir, "Materials").apply { mkdirs() }
              val dest = uniqueTarget(matDir, "${FormatUtils.sanitizeFileName(matName)}.mat", usedOutputPaths)
              dest.writeText(yaml)
              extractedFile = dest
              provenance = DataProvenance.DECOMPILED_RECONSTRUCTED
              status = "Extracted Material YAML"
            }

            62, 134 -> { // PhysicsMaterial2D / PhysicMaterial
              val (matName, yaml) = UnityObjectDecoders.parsePhysicsMaterial2DToYaml(slice, obj.name, summary.bigEndian)
              resolvedName = matName
              val matDir = File(outAssetsDir, "Materials").apply { mkdirs() }
              val ext = if (obj.classId == 62) "physicsMaterial2D" else "physicMaterial"
              val dest = uniqueTarget(matDir, "${FormatUtils.sanitizeFileName(matName)}.$ext", usedOutputPaths)
              dest.writeText(yaml)
              extractedFile = dest
              provenance = DataProvenance.DECOMPILED_RECONSTRUCTED
              status = "Extracted PhysicsMaterial"
            }

            49 -> { // TextAsset
              val parsedTxt = UnityObjectDecoders.parseTextAsset(slice, obj.name, summary.bigEndian)
              if (parsedTxt != null) {
                resolvedName = parsedTxt.first
                val txtDir = File(outAssetsDir, "TextAssets").apply { mkdirs() }
                val dest = uniqueTarget(txtDir, "${FormatUtils.sanitizeFileName(resolvedName)}.txt", usedOutputPaths)
                dest.writeBytes(parsedTxt.second)
                extractedFile = dest
                status = "Extracted (${parsedTxt.second.size} B)"
              }
            }

            74, 91 -> { // AnimationClip / AnimatorController
              val animDir = File(outAssetsDir, "Animations").apply { mkdirs() }
              val ext = if (obj.classId == 74) "anim" else "controller"
              val dest = uniqueTarget(animDir, "${FormatUtils.sanitizeFileName(obj.name)}.$ext", usedOutputPaths)
              dest.writeText(
                """
                %YAML 1.1
                %TAG !u! tag:unity3d.com,2011:
                --- !u!${obj.classId} &${obj.classId}00000
                ${obj.unityTypeName}:
                  m_ObjectHideFlags: 0
                  m_Name: ${obj.name}
                  # Reconstructed metadata stub from $relPath (PathID ${obj.pathId}, ${slice.size} bytes)
                """.trimIndent(),
              )
              extractedFile = dest
              provenance = DataProvenance.DECOMPILED_RECONSTRUCTED
              status = "Reconstructed ${obj.unityTypeName}"
            }
          }
        }

        val uid = "${projectId}_${relPath.hashCode()}_${obj.pathId}_${entryCounter++}"
        assetEntities.add(
          AssetEntryEntity(
            id = uid,
            projectId = projectId,
            pathId = obj.pathId,
            name = resolvedName,
            originalPath = relPath,
            kind = obj.assetKind,
            unityTypeName = obj.unityTypeName,
            sourceArchivePath = relPath,
            byteOffset = obj.byteOffset,
            byteSize = obj.byteSize,
            provenance = provenance,
            extractedFilePath = extractedFile?.absolutePath,
            previewMetadataJson = "{\"classId\":${obj.classId},\"offset\":${obj.byteOffset}}",
            extractionStatus = status,
          ),
        )
      }

      // If this container is a scene (levelN), reconstruct its complete Unity YAML .unity file
      val levelMatch = Regex("^level([0-9]+)$").find(file.name)
      if (levelMatch != null) {
        val levelIdx = levelMatch.groupValues[1].toIntOrNull() ?: 0
        val sceneDisplay = discoveredSceneNames.getOrNull(levelIdx) ?: "${file.name}.unity"
        val scenesDir = File(outAssetsDir, "Scenes").apply { mkdirs() }
        val (sceneFile, hierarchy) =
          sceneEngine.exportSceneToUnityYaml(
            scene = ParsedSceneContent(file, relPath, sceneDisplay, summary.bigEndian, summary.objects),
            monoScriptsByPathId = monoScriptsByPathId,
            outputDir = scenesDir,
          )
        sceneNodesCollected.addAll(hierarchy)
        assetEntities.add(
          AssetEntryEntity(
            id = "${projectId}_scene_${file.name}_${entryCounter++}",
            projectId = projectId,
            pathId = 100_000L + levelIdx,
            name = sceneFile.name,
            originalPath = sceneDisplay,
            kind = AssetKind.SCENE,
            unityTypeName = "SceneAsset",
            sourceArchivePath = relPath,
            byteOffset = 0L,
            byteSize = sceneFile.length(),
            provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
            extractedFilePath = sceneFile.absolutePath,
            previewMetadataJson = "{\"levelIndex\":$levelIdx,\"scenePath\":\"$sceneDisplay\"}",
            extractionStatus = "Reconstructed Scene (.unity YAML)",
          ),
        )
      }

      if (summaries.isNotEmpty()) {
        onProgress(50 + ((sIdx + 1) * 35) / summaries.size, "Extracted ${file.name}")
      }
    }

    // 5. Extract StreamingAssets and APK UI/config resources (icons, AndroidManifest.xml, JSON configs, boot.config)
    for (relStream in streamingAssetPaths) {
      token.checkPoint()
      val srcFile = File(extractedDir, relStream)
      if (!srcFile.exists()) continue
      val destFile = PathTraversalGuard.resolveSafeTarget(outAssetsDir, "StreamingAssets/${srcFile.name}")
      destFile.parentFile?.mkdirs()
      srcFile.copyTo(destFile, overwrite = true)
      assetEntities.add(
        AssetEntryEntity(
          id = "${projectId}_stream_${relStream.hashCode()}_${entryCounter++}",
          projectId = projectId,
          pathId = relStream.hashCode().toLong(),
          name = srcFile.name,
          originalPath = relStream,
          kind = classifyExtension(srcFile.extension),
          unityTypeName = "StreamingAsset",
          sourceArchivePath = relStream,
          byteOffset = 0L,
          byteSize = srcFile.length(),
          provenance = DataProvenance.ORIGINAL_RECOVERED,
          extractedFilePath = destFile.absolutePath,
          previewMetadataJson = null,
          extractionStatus = "Extracted",
        ),
      )
    }

    val apkExtraFiles =
      extractedDir.walkTopDown().filter { file ->
        if (!file.isFile) return@filter false
        val rel = file.relativeTo(extractedDir).path.replace('\\', '/')
        if (rel.startsWith("_unpacked/") || rel.startsWith("_splits/")) return@filter false
        rel == "AndroidManifest.xml" ||
          rel.startsWith("res/") ||
          rel.endsWith(".json") ||
          rel.endsWith("boot.config") ||
          rel.endsWith("unity_app_guid") ||
          (rel.startsWith("assets/") && classifyExtension(file.extension) in setOf(AssetKind.TEXTURE, AssetKind.AUDIO, AssetKind.TEXT_ASSET))
      }.toList()

    for (extraFile in apkExtraFiles) {
      token.checkPoint()
      val rel = extraFile.relativeTo(extractedDir).path.replace('\\', '/')
      val dest = PathTraversalGuard.resolveSafeTarget(outAssetsDir, "APK_Resources/$rel")
      dest.parentFile?.mkdirs()
      extraFile.copyTo(dest, overwrite = true)
      val kind = if (rel.endsWith(".png", true) || rel.endsWith(".webp", true)) AssetKind.TEXTURE else AssetKind.OTHER
      assetEntities.add(
        AssetEntryEntity(
          id = "${projectId}_apk_${rel.hashCode()}_${entryCounter++}",
          projectId = projectId,
          pathId = rel.hashCode().toLong(),
          name = extraFile.name,
          originalPath = rel,
          kind = kind,
          unityTypeName = "APK Resource",
          sourceArchivePath = "apk://$rel",
          byteOffset = 0L,
          byteSize = extraFile.length(),
          provenance = DataProvenance.ORIGINAL_RECOVERED,
          extractedFilePath = dest.absolutePath,
          previewMetadataJson = null,
          extractionStatus = "Extracted (APK)",
        ),
      )
    }

    val baseGraph = sceneEngine.buildSceneSummary(parsedObjects, discoveredSceneNames)
    val finalSceneGraph =
      if (sceneNodesCollected.isNotEmpty()) {
        baseGraph.copy(rootNodes = sceneNodesCollected)
      } else {
        baseGraph
      }

    return FullExtractionOutcome(
      parsedObjects = parsedObjects,
      assetEntities = assetEntities,
      sceneGraph = finalSceneGraph,
      containersScanned = summaries.size + allBundleCandidates.size,
    )
  }

  private fun isStreamOrBundleFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".ress") ||
      lower.endsWith(".resource") ||
      lower.endsWith(".bundle") ||
      lower.endsWith(".unity3d") ||
      lower == "data.unity3d"
  }

  private fun uniqueTarget(dir: File, fileName: String, usedPaths: MutableSet<String>): File {
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0) fileName.substring(dot) else ""
    var candidate = File(dir, fileName)
    var i = 2
    while (!usedPaths.add(candidate.absolutePath)) {
      candidate = File(dir, "${base}_$i$ext")
      i++
    }
    return candidate
  }

  private fun classifyExtension(ext: String): AssetKind =
    when (ext.lowercase()) {
      "png", "jpg", "jpeg", "tga", "webp" -> AssetKind.TEXTURE
      "ogg", "wav", "mp3", "fsb" -> AssetKind.AUDIO
      "obj", "gltf", "glb" -> AssetKind.MESH
      "mat" -> AssetKind.MATERIAL
      "anim" -> AssetKind.ANIMATION
      "unity" -> AssetKind.SCENE
      "prefab" -> AssetKind.PREFAB
      "shader" -> AssetKind.SHADER
      "txt", "json", "xml", "bytes" -> AssetKind.TEXT_ASSET
      else -> AssetKind.OTHER
    }
}
