package com.reu.engine.asset

import com.reu.core.common.AssetKind
import com.reu.core.common.BinarySource
import com.reu.core.common.ByteArrayBinarySource
import com.reu.core.common.DataProvenance
import com.reu.core.common.FileBinarySource
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
data class ParsedUnityObject(
  val pathId: Long,
  val classId: Int,
  val unityTypeName: String,
  val assetKind: AssetKind,
  val name: String,
  val sourceFile: String,
  val byteOffset: Long,
  val byteSize: Long,
  val rawSliceBase64Hint: String? = null,
  val referencedPathIds: List<Long> = emptyList(),
  val provenance: DataProvenance = DataProvenance.ORIGINAL_RECOVERED,
)

@Serializable
data class SerializedFileSummary(
  val fileName: String,
  val formatVersion: Int,
  val unityVersion: String?,
  val targetPlatform: Int,
  val bigEndian: Boolean,
  val objectCount: Int,
  val objects: List<ParsedUnityObject>,
  val externalReferences: List<String>,
  val warnings: List<String>,
)

@Serializable
data class UnityFsBundleSummary(
  val fileName: String,
  val signature: String,
  val formatVersion: Int,
  val unityWebVersion: String,
  val unityRevision: String,
  val bundleSize: Long,
  val directoryEntries: List<String>,
  val warnings: List<String>,
)

class UnityAssetParser {
  fun parseUnityFs(file: File): UnityFsBundleSummary? {
    if (!file.exists() || file.length() < 32) return null
    FileBinarySource(file).use { src ->
      val sig = src.readNullTerminatedAscii(32)
      if (sig != "UnityFS") return null
      val formatVersion = src.readI32BE()
      val webVer = src.readNullTerminatedAscii(64)
      val unityRev = src.readNullTerminatedAscii(64)
      val bundleSize = src.readU64BE()
      val compBlocksSize = src.readI32BE()
      val uncompBlocksSize = src.readI32BE()
      val flags = src.readI32BE()
      val warnings = mutableListOf<String>()
      val entries = mutableListOf<String>()

      val compressionType = flags and 0x3F
      val blockInfoAtEnd = (flags and 0x80) != 0
      if (formatVersion >= 7) src.align(16)

      if (compBlocksSize in 1..(16 * 1024 * 1024) && uncompBlocksSize in 1..(32 * 1024 * 1024)) {
        val blockInfoPos =
          if (blockInfoAtEnd) (src.length - compBlocksSize).coerceAtLeast(src.position) else src.position
        if (blockInfoPos + compBlocksSize <= src.length) {
          src.position = blockInfoPos
          val compBytes = src.readBytes(compBlocksSize)
          val blockBytes =
            when (compressionType) {
              0 -> compBytes
              2, 3 -> decompressLz4Block(compBytes, uncompBlocksSize)
              else -> {
                warnings.add("Compression mode $compressionType requires optional native/LZMA module.")
                ByteArray(0)
              }
            }
          if (blockBytes.size >= 24) {
            ByteArrayBinarySource(blockBytes).use { bSrc ->
              bSrc.position = 16 // skip uncompressedDataHash
              val blockCount = bSrc.readI32BE().coerceIn(0, 8192)
              bSrc.position += blockCount.toLong() * 10L
              if (bSrc.position + 4 <= bSrc.length) {
                val dirCount = bSrc.readI32BE().coerceIn(0, 4096)
                for (i in 0 until dirCount) {
                  if (bSrc.position + 20 > bSrc.length) break
                  bSrc.readU64BE() // offset
                  bSrc.readU64BE() // size
                  bSrc.readI32BE() // flags
                  val path = bSrc.readNullTerminatedAscii(256)
                  if (path.isNotBlank()) entries.add(path)
                }
              }
            }
          }
        }
      }

      return UnityFsBundleSummary(
        fileName = file.name,
        signature = sig,
        formatVersion = formatVersion,
        unityWebVersion = webVer,
        unityRevision = unityRev,
        bundleSize = bundleSize,
        directoryEntries = entries,
        warnings = warnings,
      )
    }
  }

  fun parseSerializedFile(file: File, relativePath: String, maxObjects: Int = 2000): SerializedFileSummary {
    if (!file.exists() || file.length() < 24) {
      return SerializedFileSummary(
        fileName = relativePath,
        formatVersion = 0,
        unityVersion = null,
        targetPlatform = 0,
        bigEndian = true,
        objectCount = 0,
        objects = emptyList(),
        externalReferences = emptyList(),
        warnings = listOf("File too small for Unity SerializedFile header."),
      )
    }

    FileBinarySource(file).use { src ->
      val warnings = mutableListOf<String>()
      var metadataSize = src.readU32BE()
      var fileSize = src.readU32BE()
      val version = src.readI32BE()
      var dataOffset = src.readU32BE()

      if (version < 4 || version > 30) {
        return SerializedFileSummary(
          fileName = relativePath,
          formatVersion = version,
          unityVersion = null,
          targetPlatform = 0,
          bigEndian = true,
          objectCount = 0,
          objects = emptyList(),
          externalReferences = emptyList(),
          warnings = listOf("Not a standard Unity SerializedFile (version=$version)."),
        )
      }

      var bigEndian = true
      if (version >= 9) {
        bigEndian = src.readU8() != 0
        src.readBytes(3) // reserved
      }
      if (version >= 22) {
        metadataSize = src.readU32BE()
        fileSize = src.readU64BE()
        dataOffset = src.readU64BE()
        src.readU64BE() // unknown
      }

      val unityVersion = if (version >= 7) src.readNullTerminatedAscii(64) else null
      val targetPlatform = if (version >= 8) (if (bigEndian) src.readI32BE() else src.readI32LE()) else 0
      val enableTypeTree = if (version >= 13) src.readU8() != 0 else true

      val typeCount = if (bigEndian) src.readI32BE() else src.readI32LE()
      val classIds = mutableListOf<Int>()
      if (typeCount in 1..4096) {
        for (i in 0 until typeCount) {
          if (src.position + 16 > src.length) break
          val classId = if (bigEndian) src.readI32BE() else src.readI32LE()
          classIds.add(classId)
          if (version >= 16) src.readU8() // isStrippedType
          if (version >= 17) src.position += 2 // scriptTypeIndex
          if (version >= 13) {
            val hasScriptHash = (version < 16 && classId < 0) || (version >= 16 && classId == 114)
            src.position += if (hasScriptHash) 32 else 16
          }
          if (enableTypeTree) {
            skipTypeTreeNodes(src, version, bigEndian)
          }
          if (version >= 21) {
            val depCount = if (bigEndian) src.readI32BE() else src.readI32LE()
            if (depCount in 0..4096) src.position += depCount * 4L
          }
        }
      }

      val objects = mutableListOf<ParsedUnityObject>()
      var objCount = 0
      if (src.position + 4 <= src.length) {
        objCount = (if (bigEndian) src.readI32BE() else src.readI32LE()).coerceIn(0, 100_000)
        for (i in 0 until minOf(objCount, maxObjects)) {
          if (version >= 14) src.align(4)
          if (src.position + 24 > src.length) break
          val pathId =
            if (version >= 14) {
              if (bigEndian) src.readU64BE() else src.readU64LE()
            } else {
              (if (bigEndian) src.readI32BE() else src.readI32LE()).toLong()
            }
          val byteStart =
            if (version >= 22) {
              if (bigEndian) src.readU64BE() else src.readU64LE()
            } else {
              if (bigEndian) src.readU32BE() else src.readU32LE()
            }
          val byteSize = if (bigEndian) src.readU32BE() else src.readU32LE()
          val typeId = if (bigEndian) src.readI32BE() else src.readI32LE()
          val classId =
            if (version >= 16 && typeId in classIds.indices) {
              classIds[typeId]
            } else {
              typeId
            }

          if (version < 16) src.position += 2 // classId duplicate
          if (version in 11..16) src.position += 2 // isDestroyed
          if (version in 15..16) src.readU8() // stripped

          val absOffset = dataOffset + byteStart
          val (typeName, kind) = mapUnityClassId(classId)
          val name = tryReadObjectName(src, absOffset, byteSize, bigEndian, classId)
            ?: "${typeName}_${pathId}"

          objects.add(
            ParsedUnityObject(
              pathId = pathId,
              classId = classId,
              unityTypeName = typeName,
              assetKind = kind,
              name = name,
              sourceFile = relativePath,
              byteOffset = absOffset,
              byteSize = byteSize,
            ),
          )
        }
      }

      if (fileSize > src.length) {
        warnings.add("Reported SerializedFile size ($fileSize) exceeds local file size (${src.length}).")
      }

      return SerializedFileSummary(
        fileName = relativePath,
        formatVersion = version,
        unityVersion = unityVersion?.takeIf { it.isNotBlank() },
        targetPlatform = targetPlatform,
        bigEndian = bigEndian,
        objectCount = objCount,
        objects = objects,
        externalReferences = emptyList(),
        warnings = warnings,
      )
    }
  }

  private fun skipTypeTreeNodes(src: BinarySource, version: Int, bigEndian: Boolean) {
    if (version >= 10 || version == 12) {
      if (src.position + 8 > src.length) return
      val nodeCount = if (bigEndian) src.readI32BE() else src.readI32LE()
      val stringBufSize = if (bigEndian) src.readI32BE() else src.readI32LE()
      val nodeSize = if (version >= 19) 32L else 24L
      if (nodeCount in 0..32768 && stringBufSize in 0..(4 * 1024 * 1024)) {
        val advance = nodeCount * nodeSize + stringBufSize
        if (src.position + advance <= src.length) src.position += advance
      }
    }
  }

  private fun tryReadObjectName(
    src: BinarySource,
    absOffset: Long,
    byteSize: Long,
    bigEndian: Boolean,
    classId: Int,
  ): String? {
    if (absOffset < 0 || absOffset + 8 > src.length || byteSize < 8) return null
    // Named Unity Object classes typically start with m_Name (int32 length + UTF8 bytes)
    val namedClasses = setOf(1, 21, 28, 43, 48, 49, 74, 83, 142, 213)
    if (classId !in namedClasses) return null
    val saved = src.position
    return try {
      src.position = absOffset
      val len = if (bigEndian) src.readI32BE() else src.readI32LE()
      if (len in 1..128 && absOffset + 4 + len <= src.length) {
        val bytes = src.readBytes(len)
        val candidate = String(bytes, StandardCharsets.UTF_8)
        if (candidate.all { !it.isISOControl() }) candidate else null
      } else {
        null
      }
    } catch (_: Throwable) {
      null
    } finally {
      src.position = saved
    }
  }

  fun mapUnityClassId(classId: Int): Pair<String, AssetKind> =
    when (classId) {
      1 -> "GameObject" to AssetKind.PREFAB
      4 -> "Transform" to AssetKind.SCENE
      20 -> "Camera" to AssetKind.SCENE
      21 -> "Material" to AssetKind.MATERIAL
      23 -> "MeshRenderer" to AssetKind.SCENE
      28 -> "Texture2D" to AssetKind.TEXTURE
      33 -> "MeshFilter" to AssetKind.SCENE
      43 -> "Mesh" to AssetKind.MESH
      48 -> "Shader" to AssetKind.SHADER
      49 -> "TextAsset" to AssetKind.TEXT_ASSET
      74 -> "AnimationClip" to AssetKind.ANIMATION
      83 -> "AudioClip" to AssetKind.AUDIO
      108 -> "Light" to AssetKind.SCENE
      114 -> "MonoBehaviour" to AssetKind.SCRIPTABLE_OBJECT
      115 -> "MonoScript" to AssetKind.OTHER
      142 -> "AssetBundle" to AssetKind.OTHER
      213 -> "Sprite" to AssetKind.TEXTURE
      else -> "UnityClass_$classId" to AssetKind.OTHER
    }

  /**
   * Standard LZ4 block decompressor used by UnityFS block info and compressed chunks.
   */
  fun decompressLz4Block(src: ByteArray, uncompressedSize: Int): ByteArray {
    if (uncompressedSize <= 0 || uncompressedSize > 64 * 1024 * 1024) return ByteArray(0)
    val dst = ByteArray(uncompressedSize)
    var sIdx = 0
    var dIdx = 0
    try {
      while (sIdx < src.size && dIdx < uncompressedSize) {
        val token = src[sIdx++].toInt() and 0xFF
        var litLen = token ushr 4
        if (litLen == 15) {
          var b: Int
          do {
            if (sIdx >= src.size) break
            b = src[sIdx++].toInt() and 0xFF
            litLen += b
          } while (b == 255)
        }
        val safeLit = minOf(litLen, src.size - sIdx, uncompressedSize - dIdx)
        System.arraycopy(src, sIdx, dst, dIdx, safeLit)
        sIdx += safeLit
        dIdx += safeLit
        if (sIdx >= src.size || dIdx >= uncompressedSize) break

        if (sIdx + 2 > src.size) break
        val offset = (src[sIdx].toInt() and 0xFF) or ((src[sIdx + 1].toInt() and 0xFF) shl 8)
        sIdx += 2
        if (offset <= 0 || offset > dIdx) break

        var matchLen = (token and 0x0F) + 4
        if ((token and 0x0F) == 15) {
          var b: Int
          do {
            if (sIdx >= src.size) break
            b = src[sIdx++].toInt() and 0xFF
            matchLen += b
          } while (b == 255)
        }
        var matchPos = dIdx - offset
        for (k in 0 until matchLen) {
          if (dIdx >= uncompressedSize) break
          dst[dIdx++] = dst[matchPos++]
        }
      }
    } catch (_: Throwable) {
      return ByteArray(0)
    }
    return dst
  }
}
