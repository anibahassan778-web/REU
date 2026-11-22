package com.reu.engine.asset

import com.reu.core.common.ByteArrayBinarySource
import com.reu.engine.model.MeshDataModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class UnityPPtr(val fileId: Int, val pathId: Long)

data class ParsedTexture2D(
  val name: String,
  val width: Int,
  val height: Int,
  val completeImageSize: Int,
  val textureFormat: Int,
  val mipCount: Int,
  val inlineImageData: ByteArray,
  val streamOffset: Long,
  val streamSize: Long,
  val streamPath: String,
)

data class ParsedSprite(
  val name: String,
  val rectX: Float,
  val rectY: Float,
  val rectW: Float,
  val rectH: Float,
  val pixelsToUnits: Float,
  val textureRef: UnityPPtr,
)

data class ParsedAudioClip(
  val name: String,
  val loadType: Int,
  val channels: Int,
  val frequency: Int,
  val bitsPerSample: Int,
  val lengthSeconds: Float,
  val compressionFormat: Int,
  val streamSource: String,
  val streamOffset: Long,
  val streamSize: Long,
  val inlineData: ByteArray,
)

data class ParsedFont(
  val name: String,
  val fontData: ByteArray,
  val isOpenType: Boolean,
)

data class ParsedMonoScript(
  val pathId: Long,
  val name: String,
  val executionOrder: Int,
  val className: String,
  val namespaceName: String,
  val assemblyName: String,
)

data class ParsedGameObject(
  val pathId: Long,
  val name: String,
  val layer: Int,
  val tag: Int,
  val isActive: Boolean,
  val componentRefs: List<UnityPPtr>,
)

data class ParsedTransform(
  val pathId: Long,
  val isRectTransform: Boolean,
  val gameObject: UnityPPtr,
  val rotation: FloatArray,
  val position: FloatArray,
  val scale: FloatArray,
  val childrenRefs: List<UnityPPtr>,
  val fatherRef: UnityPPtr,
  val rootOrder: Int,
)

data class ParsedMonoBehaviour(
  val pathId: Long,
  val gameObject: UnityPPtr,
  val enabled: Boolean,
  val scriptRef: UnityPPtr,
  val name: String,
)

object UnityObjectDecoders {
  fun readObjectSlice(containerFile: File, obj: ParsedUnityObject, maxBytes: Int = 32 * 1024 * 1024): ByteArray? {
    if (!containerFile.exists() || obj.byteOffset < 0 || obj.byteSize <= 0 || obj.byteSize > maxBytes) return null
    if (obj.byteOffset + obj.byteSize > containerFile.length()) return null
    return RandomAccessFile(containerFile, "r").use { raf ->
      raf.seek(obj.byteOffset)
      val out = ByteArray(obj.byteSize.toInt())
      raf.readFully(out)
      out
    }
  }

  fun resolveStreamedBytes(
    containerFile: File,
    streamPath: String,
    offset: Long,
    size: Long,
    inlineFallback: ByteArray = ByteArray(0),
  ): ByteArray {
    if (inlineFallback.isNotEmpty()) return inlineFallback
    if (streamPath.isBlank() || size <= 0 || size > 64L * 1024L * 1024L || offset < 0) return ByteArray(0)
    val cleanRel = streamPath.substringAfterLast('/')
    val candidates =
      listOfNotNull(
        File(containerFile.parentFile, cleanRel),
        File(containerFile.parentFile, streamPath),
        containerFile.parentFile?.parentFile?.let { File(it, cleanRel) },
      )
    val streamFile = candidates.firstOrNull { it.exists() && it.isFile && offset + size <= it.length() }
      ?: return ByteArray(0)
    return RandomAccessFile(streamFile, "r").use { raf ->
      raf.seek(offset)
      val buf = ByteArray(size.toInt())
      raf.readFully(buf)
      buf
    }
  }

  fun parseBuildSettingsScenes(objBytes: ByteArray, bigEndian: Boolean = false): List<String> =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val count = src.i32(bigEndian).coerceIn(0, 512)
        val scenes = mutableListOf<String>()
        for (i in 0 until count) {
          val s = src.readUnityString(bigEndian)
          if (s.isNotBlank()) scenes.add(s)
        }
        scenes
      }
    }.getOrDefault(emptyList())

  fun parseTexture2D(objBytes: ByteArray, bigEndian: Boolean = false): ParsedTexture2D? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian)
        if (src.position + 80 > src.length) return null
        src.i32(bigEndian) // m_ForcedFallbackFormat
        src.position += 4 // m_DownscaleFallback + flags (aligned 4)
        val width = src.i32(bigEndian)
        val height = src.i32(bigEndian)
        val completeImageSize = src.i32(bigEndian)
        src.i32(bigEndian) // m_MipsStripped
        val textureFormat = src.i32(bigEndian)
        val mipCount = src.i32(bigEndian)
        src.position += 16 // bool flags (4B) + m_StreamingMipmapsPriority (4B) + m_ImageCount (4B) + m_TextureDimension (4B)
        src.position += 24 // GLTextureSettings (6 * 4B)
        src.i32(bigEndian) // m_LightmapFormat
        src.i32(bigEndian) // m_ColorSpace

        // Unity 2020.2+: m_PlatformBlob byte[]
        if (src.position + 4 <= src.length) {
          val savedPos = src.position
          val maybeLen = src.i32(bigEndian)
          if (maybeLen == 0) {
            // Check if next int32 is imageDataLen + data + StreamingInfo
            // In Unity 2021.3, m_PlatformBlob has len=0 (4B), then image_data has len (4B)
          } else if (maybeLen in 1..65536 && src.position + maybeLen + 4 <= src.length) {
            // Could be inline image data directly OR platform blob — distinguish by checking StreamingInfo after align4
            val afterFirst = src.position + ((maybeLen + 3) and 3.inv())
            if (afterFirst + 16 == src.length && completeImageSize == maybeLen) {
              // Format without m_PlatformBlob (Unity <= 2020.1)
              src.position = savedPos
            } else {
              src.position = afterFirst.toLong()
            }
          } else {
            src.position = savedPos
          }
        }

        var inlineData = ByteArray(0)
        if (src.position + 4 <= src.length) {
          val dataLen = src.i32(bigEndian)
          if (dataLen in 1..(64 * 1024 * 1024) && src.position + dataLen <= src.length) {
            inlineData = src.readBytes(dataLen)
            src.align(4)
          }
        }

        var streamOffset = 0L
        var streamSize = 0L
        var streamPath = ""
        if (src.position + 16 <= src.length) {
          streamOffset = src.u64(bigEndian)
          streamSize = src.u32(bigEndian)
          streamPath = src.readUnityString(bigEndian)
        }

        ParsedTexture2D(
          name = name,
          width = width,
          height = height,
          completeImageSize = completeImageSize,
          textureFormat = textureFormat,
          mipCount = mipCount,
          inlineImageData = inlineData,
          streamOffset = streamOffset,
          streamSize = streamSize,
          streamPath = streamPath,
        )
      }
    }.getOrNull()

  fun parseSprite(objBytes: ByteArray, bigEndian: Boolean = false): ParsedSprite? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian)
        val rectX = src.f32(bigEndian)
        val rectY = src.f32(bigEndian)
        val rectW = src.f32(bigEndian)
        val rectH = src.f32(bigEndian)
        src.position += 24 // offset (8) + border (16)
        val pixelsToUnits = src.f32(bigEndian)
        src.position += 16 // pivot (8) + extrude (4) + isPolygon + align4 (4)
        src.position += 24 // m_RenderDataKey (GUID 16 + int64 8)
        val tagCount = src.i32(bigEndian).coerceIn(0, 64)
        for (i in 0 until tagCount) {
          src.readUnityString(bigEndian)
        }
        src.readPPtr(bigEndian) // m_SpriteAtlas
        val texRef = src.readPPtr(bigEndian) // m_RD.texture
        ParsedSprite(
          name = name,
          rectX = rectX,
          rectY = rectY,
          rectW = rectW,
          rectH = rectH,
          pixelsToUnits = pixelsToUnits,
          textureRef = texRef,
        )
      }
    }.getOrNull()

  fun parseAudioClip(objBytes: ByteArray, bigEndian: Boolean = false): ParsedAudioClip? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian)
        if (src.position + 36 > src.length) return null
        val loadType = src.i32(bigEndian)
        val channels = src.i32(bigEndian)
        val frequency = src.i32(bigEndian)
        val bitsPerSample = src.i32(bigEndian)
        val length = src.f32(bigEndian)
        val afterLengthPos = src.position
        // Unity 2017+: m_IsTrackerFormat(4) + m_SubsoundIndex(4) + preload/background/legacy3D(4) = 12 bytes before compressionFormat
        // Unity 5.x: 8 bytes before compressionFormat
        var matched = false
        for (gap in intArrayOf(12, 8)) {
          if (afterLengthPos + gap + 8 <= src.length) {
            src.position = afterLengthPos + gap
            val cf = src.i32(bigEndian)
            val strLen = src.i32(bigEndian)
            if (cf in 0..9 && strLen in 0..256 && src.position + ((strLen + 3) and 3.inv()) + 16 <= src.length) {
              src.position = afterLengthPos + gap
              matched = true
              break
            }
          }
        }
        if (!matched) src.position = afterLengthPos + 12
        val compressionFormat = src.i32(bigEndian)
        val streamSource = src.readUnityString(bigEndian)
        val streamOffset = src.u64(bigEndian)
        val streamSize = src.u64(bigEndian)
        val inlineData =
          if (streamSize == 0L && src.position + 4 <= src.length) {
            val inlineLen = src.i32(bigEndian)
            if (inlineLen in 1..minOf(32 * 1024 * 1024, (src.length - src.position).toInt())) {
              src.readBytes(inlineLen)
            } else ByteArray(0)
          } else ByteArray(0)

        ParsedAudioClip(
          name = name,
          loadType = loadType,
          channels = channels,
          frequency = frequency,
          bitsPerSample = bitsPerSample,
          lengthSeconds = length,
          compressionFormat = compressionFormat,
          streamSource = streamSource,
          streamOffset = streamOffset,
          streamSize = streamSize,
          inlineData = inlineData,
        )
      }
    }.getOrNull()

  fun parseTextAsset(objBytes: ByteArray, fallbackName: String, bigEndian: Boolean = false): Pair<String, ByteArray>? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian).ifBlank { fallbackName }
        val scriptLen = src.i32(bigEndian)
        if (scriptLen < 0 || src.position + scriptLen > src.length) return null
        name to src.readBytes(scriptLen)
      }
    }.getOrNull()

  fun parseFont(objBytes: ByteArray, fallbackName: String, bigEndian: Boolean = false): ParsedFont? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian).ifBlank { fallbackName }
        val startPos = src.position.toInt().coerceAtLeast(4)
        for (idx in startPos..(objBytes.size - 16)) {
          val b0 = objBytes[idx].toInt() and 0xFF
          val b1 = objBytes[idx + 1].toInt() and 0xFF
          val b2 = objBytes[idx + 2].toInt() and 0xFF
          val b3 = objBytes[idx + 3].toInt() and 0xFF
          val isTtf = (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00)
          val isOtf = (b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code)
          if (!isTtf && !isOtf) continue

          val numTables = readU16BEAt(objBytes, idx + 4)
          val searchRange = readU16BEAt(objBytes, idx + 6)
          val entrySelector = readU16BEAt(objBytes, idx + 8)
          val rangeShift = readU16BEAt(objBytes, idx + 10)
          if (numTables !in 4..64 || entrySelector !in 2..6) continue
          val maxPow2 = 1 shl entrySelector
          if (maxPow2 > numTables || maxPow2 * 2 <= numTables) continue
          if (searchRange != maxPow2 * 16) continue
          if (rangeShift != numTables * 16 - searchRange) continue
          if (idx + 12 + numTables * 16 > objBytes.size) continue

          val vecLen = readI32At(objBytes, idx - 4, bigEndian)
          if (vecLen in 512..(objBytes.size - idx)) {
            return ParsedFont(name, objBytes.copyOfRange(idx, idx + vecLen), isOtf)
          }
        }
        ParsedFont(name, ByteArray(0), false)
      }
    }.getOrNull()

  fun parseShaderToReadableSource(objBytes: ByteArray, fallbackName: String, bigEndian: Boolean = false): Pair<String, String> {
    // Unity stores ShaderLab properties first inside m_ParsedForm, followed by the shader m_Name string
    // (e.g., "Sprites/Default", "TextMeshPro/Mobile/Distance Field", "Hidden/...")
    val alignedStrings = extractAlignedLengthStrings(objBytes, bigEndian, minLen = 3, maxLen = 128)
    val asciiStrings = extractReadableStrings(objBytes, minLen = 3, maxStrings = 60)

    val shaderName =
      alignedStrings.firstOrNull { cand ->
        !cand.startsWith("_") &&
          !cand.startsWith("<") &&
          cand.contains('/') &&
          !cand.contains('=') &&
          !cand.endsWith('/') &&
          !cand.startsWith('/')
      }
        ?: alignedStrings.firstOrNull { cand ->
          cand in setOf("Standard", "Diffuse", "Specular", "Unlit", "Particles")
        }
        ?: asciiStrings.firstOrNull {
          it.contains('/') && !it.startsWith("_") && !it.contains('=') && it.length in 5..96
        }
        ?: fallbackName

    val props =
      (alignedStrings + asciiStrings)
        .filter { it.startsWith("_") && !it.startsWith("__") && !it.contains(' ') && it.length in 2..48 }
        .distinct()
        .take(24)

    val text =
      buildString {
        appendLine("// Reconstructed ShaderLab metadata from compiled Unity Shader (${objBytes.size} bytes)")
        appendLine("// Note: Compiled GPU shader bytecode cannot be decompiled directly to original HLSL/CG source;")
        appendLine("//       extracted original shader name and uniform properties below.")
        appendLine("Shader \"$shaderName\" {")
        appendLine("    Properties {")
        if (props.isEmpty()) {
          appendLine("        _MainTex (\"Texture\", 2D) = \"white\" {}")
        } else {
          for (p in props) {
            when {
              p.contains("Color", ignoreCase = true) -> appendLine("        $p (\"$p\", Color) = (1,1,1,1)")
              p.contains("Tex", ignoreCase = true) || p.contains("Map", ignoreCase = true) ->
                appendLine("        $p (\"$p\", 2D) = \"white\" {}")
              else -> appendLine("        $p (\"$p\", Float) = 0")
            }
          }
        }
        appendLine("    }")
        appendLine("    SubShader {")
        appendLine("        Pass {")
        appendLine("            // Compiled platform shader binary size: ${objBytes.size} bytes")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
      }
    return shaderName to text
  }

  fun parseMaterialToYaml(
    objBytes: ByteArray,
    fallbackName: String,
    pathId: Long,
    shaderNameLookup: (UnityPPtr) -> String?,
    textureNameLookup: (UnityPPtr) -> String?,
    bigEndian: Boolean = false,
  ): Pair<String, String> {
    val parsedName =
      runCatching {
        ByteArrayBinarySource(objBytes).use { it.readUnityString(bigEndian) }
      }.getOrNull()?.ifBlank { fallbackName } ?: fallbackName

    val summary =
      runCatching {
        ByteArrayBinarySource(objBytes).use { src ->
          src.readUnityString(bigEndian) // name
          val shaderPtr = src.readPPtr(bigEndian)
          val shaderName = shaderNameLookup(shaderPtr) ?: "Shader(file=${shaderPtr.fileId}, pathID=${shaderPtr.pathId})"
          val kwCount = src.i32(bigEndian).coerceIn(0, 64)
          val keywords = mutableListOf<String>()
          for (i in 0 until kwCount) {
            if (src.position + 4 > src.length) break
            keywords.add(src.readUnityString(bigEndian))
          }
          shaderName to keywords
        }
      }.getOrNull()

    val yaml =
      buildString {
        appendLine("%YAML 1.1")
        appendLine("%TAG !u! tag:unity3d.com,2011:")
        appendLine("--- !u!21 &2100000")
        appendLine("Material:")
        appendLine("  m_ObjectHideFlags: 0")
        appendLine("  m_Name: $parsedName")
        appendLine("  # Source PathID: $pathId | Binary Size: ${objBytes.size} bytes")
        if (summary != null) {
          appendLine("  m_ShaderHint: \"${summary.first}\"")
          appendLine("  m_ShaderKeywords: ${summary.second.joinToString(" ")}")
        }
        val tokens = extractReadableStrings(objBytes, minLen = 3, maxStrings = 32).filter { it.startsWith("_") }.distinct()
        if (tokens.isNotEmpty()) {
          appendLine("  m_SavedProperties:")
          appendLine("    m_ExtractedPropertyNames:")
          for (t in tokens) {
            appendLine("    - $t")
          }
        }
        appendLine("  m_LightmapFlags: 4")
      }
    return parsedName to yaml
  }

  fun parsePhysicsMaterial2DToYaml(objBytes: ByteArray, fallbackName: String, bigEndian: Boolean = false): Pair<String, String> {
    return runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian).ifBlank { fallbackName }
        val friction = if (src.position + 4 <= src.length) src.f32(bigEndian) else 0.4f
        val bounciness = if (src.position + 4 <= src.length) src.f32(bigEndian) else 0f
        name to
          """
          %YAML 1.1
          %TAG !u! tag:unity3d.com,2011:
          --- !u!62 &6200000
          PhysicsMaterial2D:
            m_ObjectHideFlags: 0
            m_Name: $name
            friction: $friction
            bounciness: $bounciness
          """.trimIndent()
      }
    }.getOrElse {
      fallbackName to "# PhysicsMaterial2D $fallbackName (${objBytes.size} bytes)"
    }
  }

  fun parseMesh(objBytes: ByteArray, fallbackName: String, bigEndian: Boolean = false): MeshDataModel? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian).ifBlank { fallbackName }
        val subMeshCount = src.i32(bigEndian)
        if (subMeshCount !in 1..64) return null
        // Each SubMesh (Unity 2017.3+): firstByte(4), indexCount(4), topology(4), baseVertex(4), firstVertex(4), vertexCount(4), localAABB(24) = 48 bytes
        val subSize = 48L * subMeshCount
        if (src.position + subSize > src.length) return null
        src.position += subSize

        // BlendShapeData: shapes[], channels[], fullWeights[], vertices[] -> 4 counts if empty = 16 bytes? Actually Shapes has 4 arrays (4 * 4B = 16B if empty)
        // Because Unity serialized layout varies subtly in BlendShape/BoneHashes, scan directly from submesh end OR locate indexFormat + indexBuffer + VertexData cleanly:
        for (probeOffset in src.position.toInt()..(minOf(objBytes.size - 24, src.position.toInt() + 128)) step 4) {
          val indexFormat = readI32At(objBytes, probeOffset, bigEndian)
          val indexBufLen = readI32At(objBytes, probeOffset + 4, bigEndian)
          if (indexFormat !in 0..1 || indexBufLen < 6 || indexBufLen > objBytes.size) continue
          val afterIdx = probeOffset + 8 + ((indexBufLen + 3) and 3.inv())
          if (afterIdx + 8 > objBytes.size) continue
          val vertexCount = readI32At(objBytes, afterIdx, bigEndian)
          val channelCount = readI32At(objBytes, afterIdx + 4, bigEndian)
          if (vertexCount !in 3..200_000 || channelCount !in 1..16) continue
          val afterChannels = afterIdx + 8 + channelCount * 4
          if (afterChannels + 4 > objBytes.size) continue
          val dataSize = readI32At(objBytes, afterChannels, bigEndian)
          if (dataSize < vertexCount * 12 || afterChannels + 4 + dataSize > objBytes.size) continue
          val stride = dataSize / vertexCount
          if (stride < 12 || stride > 256) continue

          val indices = mutableListOf<Int>()
          if (indexFormat == 0) {
            for (k in 0 until indexBufLen / 2) {
              val b0 = objBytes[probeOffset + 8 + k * 2].toInt() and 0xFF
              val b1 = objBytes[probeOffset + 8 + k * 2 + 1].toInt() and 0xFF
              indices.add(b0 or (b1 shl 8))
            }
          } else {
            for (k in 0 until indexBufLen / 4) {
              indices.add(readI32At(objBytes, probeOffset + 8 + k * 4, bigEndian))
            }
          }
          if (indices.any { it >= vertexCount }) continue

          val verts = ArrayList<Float>(vertexCount * 3)
          val vBase = afterChannels + 4
          for (v in 0 until vertexCount) {
            val base = vBase + v * stride
            verts.add(readF32At(objBytes, base, bigEndian))
            verts.add(readF32At(objBytes, base + 4, bigEndian))
            verts.add(readF32At(objBytes, base + 8, bigEndian))
          }
          return MeshDataModel(name = name, vertices = verts, indices = indices)
        }
        null
      }
    }.getOrNull()

  fun parseMonoScript(pathId: Long, objBytes: ByteArray, bigEndian: Boolean = false): ParsedMonoScript? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val name = src.readUnityString(bigEndian)
        val execOrder = src.i32(bigEndian)
        src.position += 16 // m_PropertiesHash (Hash128)
        val className = src.readUnityString(bigEndian)
        val nsName = src.readUnityString(bigEndian)
        val asmName = src.readUnityString(bigEndian)
        ParsedMonoScript(pathId, name, execOrder, className, nsName, asmName)
      }
    }.getOrNull()

  fun parseGameObject(pathId: Long, objBytes: ByteArray, bigEndian: Boolean = false): ParsedGameObject? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val compCount = src.i32(bigEndian).coerceIn(0, 256)
        val refs = List(compCount) { src.readPPtr(bigEndian) }
        val layer = src.i32(bigEndian)
        val name = src.readUnityString(bigEndian)
        val tag = if (src.position + 2 <= src.length) src.readU16LE() else 0
        val active = if (src.position < src.length) src.readU8() != 0 else true
        ParsedGameObject(pathId, name.ifBlank { "GameObject_$pathId" }, layer, tag, active, refs)
      }
    }.getOrNull()

  fun parseTransform(pathId: Long, isRect: Boolean, objBytes: ByteArray, bigEndian: Boolean = false): ParsedTransform? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val goPtr = src.readPPtr(bigEndian)
        val rot = FloatArray(4) { src.f32(bigEndian) }
        val pos = FloatArray(3) { src.f32(bigEndian) }
        val scale = FloatArray(3) { src.f32(bigEndian) }
        val childCount = src.i32(bigEndian).coerceIn(0, 1024)
        val children = List(childCount) { src.readPPtr(bigEndian) }
        val father = src.readPPtr(bigEndian)
        val rootOrder = if (src.position + 4 <= src.length) src.i32(bigEndian) else 0
        ParsedTransform(pathId, isRect, goPtr, rot, pos, scale, children, father, rootOrder)
      }
    }.getOrNull()

  fun parseMonoBehaviour(pathId: Long, objBytes: ByteArray, bigEndian: Boolean = false): ParsedMonoBehaviour? =
    runCatching {
      ByteArrayBinarySource(objBytes).use { src ->
        val goPtr = src.readPPtr(bigEndian)
        val enabled = src.u8Aligned4() != 0
        val scriptPtr = src.readPPtr(bigEndian)
        val name = src.readUnityString(bigEndian)
        ParsedMonoBehaviour(pathId, goPtr, enabled, scriptPtr, name)
      }
    }.getOrNull()

  private fun extractReadableStrings(data: ByteArray, minLen: Int = 3, maxStrings: Int = 50): List<String> {
    val result = mutableListOf<String>()
    val cur = StringBuilder()
    for (b in data) {
      val ch = b.toInt() and 0xFF
      if (ch in 32..126) {
        cur.append(ch.toChar())
      } else {
        if (cur.length >= minLen) {
          result.add(cur.toString())
          if (result.size >= maxStrings) return result
        }
        cur.clear()
      }
    }
    if (cur.length >= minLen && result.size < maxStrings) result.add(cur.toString())
    return result
  }

  private fun extractAlignedLengthStrings(
    data: ByteArray,
    bigEndian: Boolean,
    minLen: Int = 3,
    maxLen: Int = 128,
    maxResults: Int = 5000,
  ): List<String> {
    val result = mutableListOf<String>()
    var off = 0
    while (off + 8 <= data.size && result.size < maxResults) {
      val len = readI32At(data, off, bigEndian)
      if (len in minLen..maxLen && off + 4 + len <= data.size) {
        var printable = true
        for (i in 0 until len) {
          val ch = data[off + 4 + i].toInt() and 0xFF
          if (ch !in 32..126) {
            printable = false
            break
          }
        }
        if (printable) {
          result.add(String(data, off + 4, len, StandardCharsets.US_ASCII))
          off += 4 + ((len + 3) and 3.inv())
          continue
        }
      }
      off += 4
    }
    return result
  }

  private fun readU16BEAt(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

  private fun readI32At(buf: ByteArray, off: Int, bigEndian: Boolean): Int {
    val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    return ByteBuffer.wrap(buf, off, 4).order(order).int
  }

  private fun readF32At(buf: ByteArray, off: Int, bigEndian: Boolean): Float {
    val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    return ByteBuffer.wrap(buf, off, 4).order(order).float
  }

  private fun ByteArrayBinarySource.i32(bigEndian: Boolean): Int = if (bigEndian) readI32BE() else readI32LE()
  private fun ByteArrayBinarySource.u32(bigEndian: Boolean): Long = if (bigEndian) readU32BE() else readU32LE()
  private fun ByteArrayBinarySource.u64(bigEndian: Boolean): Long = if (bigEndian) readU64BE() else readU64LE()

  private fun ByteArrayBinarySource.f32(bigEndian: Boolean): Float =
    java.lang.Float.intBitsToFloat(i32(bigEndian))

  private fun ByteArrayBinarySource.u8Aligned4(): Int {
    val v = readU8()
    align(4)
    return v
  }

  private fun ByteArrayBinarySource.readUnityString(bigEndian: Boolean): String {
    if (position + 4 > length) return ""
    val len = i32(bigEndian)
    if (len <= 0 || len > 1_048_576 || position + len > length) return ""
    val bytes = readBytes(len)
    align(4)
    return String(bytes, StandardCharsets.UTF_8)
  }

  private fun ByteArrayBinarySource.readPPtr(bigEndian: Boolean): UnityPPtr {
    if (position + 12 > length) return UnityPPtr(0, 0L)
    val fid = i32(bigEndian)
    val pid = u64(bigEndian)
    return UnityPPtr(fid, pid)
  }
}
