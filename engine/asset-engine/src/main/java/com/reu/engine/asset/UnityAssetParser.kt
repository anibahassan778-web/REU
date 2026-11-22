package com.reu.engine.asset

import com.reu.core.common.AssetKind
import com.reu.core.common.BinarySource
import com.reu.core.common.ByteArrayBinarySource
import com.reu.core.common.DataProvenance
import com.reu.core.common.FileBinarySource
import com.reu.core.security.PathTraversalGuard
import java.io.File
import java.io.FileOutputStream
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

data class UnityFsUnpackResult(
  val bundleSummary: UnityFsBundleSummary,
  val unpackedFiles: List<File>,
)

private data class UnityFsBlockInfo(
  val uncompressedSize: Int,
  val compressedSize: Int,
  val flags: Int,
)

private data class UnityFsDirEntry(
  val offset: Long,
  val size: Long,
  val flags: Int,
  val path: String,
)

class UnityAssetParser {
  fun parseUnityFs(file: File): UnityFsBundleSummary? {
    if (!file.exists() || file.length() < 32) return null
    FileBinarySource(file).use { src ->
      val meta = readUnityFsHeaderAndDirectory(src) ?: return null
      return meta.summary.copy(fileName = file.name)
    }
  }

  /**
   * Unpacks a UnityFS AssetBundle / data.unity3d container into [outputDir], decompressing LZ4/LZ4HC
   * data blocks and streaming each directory node safely to disk via [PathTraversalGuard].
   */
  fun unpackUnityFs(file: File, outputDir: File): UnityFsUnpackResult? {
    if (!file.exists() || file.length() < 32) return null
    FileBinarySource(file).use { src ->
      val meta = readUnityFsHeaderAndDirectory(src) ?: return null
      val (summaryRaw, blocks, dirEntries, blocksDataStart) = meta
      val summary = summaryRaw.copy(fileName = file.name)
      if (blocks.isEmpty() || dirEntries.isEmpty()) {
        return UnityFsUnpackResult(summary, emptyList())
      }
      if (!outputDir.exists()) outputDir.mkdirs()

      // Build decompressed bundle stream into temporary disk file so multi-MB bundles never blow RAM
      val tempStreamFile = File(outputDir, ".reu_bundle_stream.tmp")
      val warnings = summary.warnings.toMutableList()
      try {
        src.position = blocksDataStart
        FileOutputStream(tempStreamFile).use { fos ->
          for ((idx, blk) in blocks.withIndex()) {
            if (blk.compressedSize <= 0 || src.position + blk.compressedSize > src.length) {
              warnings.add("Truncated UnityFS block #$idx")
              break
            }
            val comp = src.readBytes(blk.compressedSize)
            val compMode = blk.flags and 0x3F
            val raw =
              when (compMode) {
                0 -> comp
                2, 3 -> decompressLz4Block(comp, blk.uncompressedSize)
                else -> {
                  warnings.add("Unsupported UnityFS block #$idx compression mode=$compMode")
                  ByteArray(0)
                }
              }
            if (raw.isEmpty() && blk.uncompressedSize > 0) {
              warnings.add("Failed to decompress UnityFS block #$idx")
              break
            }
            fos.write(raw)
          }
        }

        val totalUnpacked = tempStreamFile.length()
        val unpackedFiles = mutableListOf<File>()
        val copyBuf = ByteArray(256 * 1024)
        FileBinarySource(tempStreamFile).use { streamSrc ->
          for (entry in dirEntries) {
            if (entry.size < 0 || entry.offset < 0 || entry.offset + entry.size > totalUnpacked) {
              warnings.add("Directory node '${entry.path}' out of stream bounds (${entry.offset}+${entry.size} > $totalUnpacked)")
              continue
            }
            val target = PathTraversalGuard.resolveSafeTarget(outputDir, entry.path)
            target.parentFile?.mkdirs()
            streamSrc.position = entry.offset
            var remaining = entry.size
            FileOutputStream(target).use { out ->
              while (remaining > 0L) {
                val chunkLen = minOf(remaining, copyBuf.size.toLong()).toInt()
                val chunk = streamSrc.readBytes(chunkLen)
                out.write(chunk)
                remaining -= chunk.size
              }
            }
            unpackedFiles.add(target)
          }
        }

        return UnityFsUnpackResult(
          bundleSummary = summary.copy(warnings = warnings),
          unpackedFiles = unpackedFiles,
        )
      } finally {
        tempStreamFile.delete()
      }
    }
  }

  private data class UnityFsParsedMeta(
    val summary: UnityFsBundleSummary,
    val blocks: List<UnityFsBlockInfo>,
    val entries: List<UnityFsDirEntry>,
    val blocksDataStart: Long,
  )

  private fun readUnityFsHeaderAndDirectory(src: BinarySource): UnityFsParsedMeta? {
    src.position = 0L
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

    val compressionType = flags and 0x3F
    val blockInfoAtEnd = (flags and 0x80) != 0
    val blocksInfoNeedPadding = (flags and 0x200) != 0
    if (formatVersion >= 7 || (flags and 0x100) != 0) src.align(16)

    val headerEnd = src.position
    val blocks = mutableListOf<UnityFsBlockInfo>()
    val dirEntries = mutableListOf<UnityFsDirEntry>()
    var blocksDataStart = headerEnd

    if (compBlocksSize in 1..(16 * 1024 * 1024) && uncompBlocksSize in 1..(32 * 1024 * 1024)) {
      val blockInfoPos =
        if (blockInfoAtEnd) (src.length - compBlocksSize).coerceAtLeast(headerEnd) else headerEnd
      if (blockInfoPos + compBlocksSize <= src.length) {
        src.position = blockInfoPos
        val compBytes = src.readBytes(compBlocksSize)
        if (!blockInfoAtEnd) {
          if (blocksInfoNeedPadding) src.align(16)
          blocksDataStart = src.position
        } else {
          blocksDataStart = headerEnd
        }

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
            val blockCount = bSrc.readI32BE().coerceIn(0, 16_384)
            for (i in 0 until blockCount) {
              if (bSrc.position + 10 > bSrc.length) break
              val us = bSrc.readI32BE()
              val cs = bSrc.readI32BE()
              val bf = bSrc.readU16BE()
              blocks.add(UnityFsBlockInfo(us, cs, bf))
            }
            if (bSrc.position + 4 <= bSrc.length) {
              val dirCount = bSrc.readI32BE().coerceIn(0, 8192)
              for (i in 0 until dirCount) {
                if (bSrc.position + 20 > bSrc.length) break
                val off = bSrc.readU64BE()
                val sz = bSrc.readU64BE()
                val fl = bSrc.readI32BE()
                val path = bSrc.readNullTerminatedAscii(512)
                if (path.isNotBlank()) {
                  dirEntries.add(UnityFsDirEntry(off, sz, fl, path))
                }
              }
            }
          }
        }
      }
    }

    val summary =
      UnityFsBundleSummary(
        fileName = (src as? FileBinarySource)?.let { "" } ?: "",
        signature = sig,
        formatVersion = formatVersion,
        unityWebVersion = webVer,
        unityRevision = unityRev,
        bundleSize = bundleSize,
        directoryEntries = dirEntries.map { it.path },
        warnings = warnings,
      )
    return UnityFsParsedMeta(summary, blocks, dirEntries, blocksDataStart)
  }

  fun parseSerializedFile(file: File, relativePath: String, maxObjects: Int = 20_000): SerializedFileSummary {
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
        src.readBytes(3)
      }
      if (version >= 22) {
        metadataSize = src.readU32BE()
        fileSize = src.readU64BE()
        dataOffset = src.readU64BE()
        src.readU64BE()
      }

      val unityVersion = if (version >= 7) src.readNullTerminatedAscii(64) else null
      val targetPlatform = if (version >= 8) readI32(src, bigEndian) else 0
      val enableTypeTree = if (version >= 13) src.readU8() != 0 else true

      val typeCount = readI32(src, bigEndian)
      val classIds = mutableListOf<Int>()
      if (typeCount in 1..8192) {
        for (i in 0 until typeCount) {
          if (src.position + 16 > src.length) break
          val classId = readI32(src, bigEndian)
          classIds.add(classId)
          if (version >= 16) src.readU8() // isStrippedType
          if (version >= 17) src.position += 2 // scriptTypeIndex
          if (version >= 13) {
            val hasScriptHash = (version < 16 && classId < 0) || (version >= 16 && classId == 114)
            src.position += if (hasScriptHash) 32 else 16
          }
          if (enableTypeTree) {
            skipTypeTreeNodes(src, version, bigEndian)
            if (version >= 21) {
              val depCount = readI32(src, bigEndian)
              if (depCount in 0..4096) src.position += depCount * 4L
            }
          }
        }
      }

      val objects = mutableListOf<ParsedUnityObject>()
      val externalRefs = mutableListOf<String>()
      var objCount = 0
      if (src.position + 4 <= src.length) {
        objCount = readI32(src, bigEndian).coerceIn(0, 200_000)
        for (i in 0 until objCount) {
          if (version >= 14) src.align(4)
          if (src.position + 20 > src.length) break
          val pathId = if (version >= 14) readU64(src, bigEndian) else readI32(src, bigEndian).toLong()
          val byteStart = if (version >= 22) readU64(src, bigEndian) else readU32(src, bigEndian)
          val byteSize = readU32(src, bigEndian)
          val typeId = readI32(src, bigEndian)
          val classId =
            if (version >= 16 && typeId in classIds.indices) {
              classIds[typeId]
            } else {
              typeId
            }

          if (version < 16) src.position += 2
          if (version in 11..16) src.position += 2
          if (version in 15..16) src.readU8()

          if (i < maxObjects) {
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

        // Read ScriptTypes and ExternalFile references if still within metadata block
        runCatching {
          if (version >= 11 && src.position + 4 <= src.length) {
            val scriptCnt = readI32(src, bigEndian)
            if (scriptCnt in 0..8192) {
              val entryLen = if (version >= 14) 12L else 8L
              if (src.position + scriptCnt * entryLen <= src.length) {
                src.position += scriptCnt * entryLen
              }
            }
          }
          if (src.position + 4 <= src.length) {
            val extCnt = readI32(src, bigEndian)
            if (extCnt in 0..1024) {
              for (e in 0 until extCnt) {
                if (src.position >= src.length) break
                if (version >= 6) src.readNullTerminatedAscii(256) // tempEmpty
                if (version >= 5) src.position = (src.position + 20L).coerceAtMost(src.length) // guid + type
                val extPath = src.readNullTerminatedAscii(512)
                if (extPath.isNotBlank()) externalRefs.add(extPath)
              }
            }
          }
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
        externalReferences = externalRefs,
        warnings = warnings,
      )
    }
  }

  private fun skipTypeTreeNodes(src: BinarySource, version: Int, bigEndian: Boolean) {
    if (version >= 10 || version == 12) {
      if (src.position + 8 > src.length) return
      val nodeCount = readI32(src, bigEndian)
      val stringBufSize = readI32(src, bigEndian)
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
    val saved = src.position
    return try {
      if (classId == 1) {
        // GameObject: componentCount(4) + components(12*cnt) + layer(4) + m_Name(string)
        src.position = absOffset
        val compCnt = readI32(src, bigEndian)
        if (compCnt !in 0..256) return null
        val nameOffset = absOffset + 4L + compCnt * 12L + 4L
        if (nameOffset + 4 > src.length) return null
        src.position = nameOffset
        val len = readI32(src, bigEndian)
        if (len in 1..160 && nameOffset + 4 + len <= src.length) {
          val candidate = String(src.readBytes(len), StandardCharsets.UTF_8)
          if (candidate.all { !it.isISOControl() }) candidate else null
        } else null
      } else {
        val namedClasses = setOf(21, 28, 43, 49, 62, 74, 83, 91, 115, 128, 134, 142, 213)
        if (classId !in namedClasses) return null
        src.position = absOffset
        val len = readI32(src, bigEndian)
        if (len in 1..160 && absOffset + 4 + len <= src.length) {
          val candidate = String(src.readBytes(len), StandardCharsets.UTF_8)
          if (candidate.all { !it.isISOControl() }) candidate else null
        } else null
      }
    } catch (_: Throwable) {
      null
    } finally {
      src.position = saved
    }
  }

  private fun readI32(src: BinarySource, bigEndian: Boolean): Int =
    if (bigEndian) src.readI32BE() else src.readI32LE()

  private fun readU32(src: BinarySource, bigEndian: Boolean): Long =
    if (bigEndian) src.readU32BE() else src.readU32LE()

  private fun readU64(src: BinarySource, bigEndian: Boolean): Long =
    if (bigEndian) src.readU64BE() else src.readU64LE()

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
      62 -> "PhysicsMaterial2D" to AssetKind.MATERIAL
      74 -> "AnimationClip" to AssetKind.ANIMATION
      82 -> "AudioSource" to AssetKind.SCENE
      83 -> "AudioClip" to AssetKind.AUDIO
      91 -> "AnimatorController" to AssetKind.ANIMATION
      108 -> "Light" to AssetKind.SCENE
      114 -> "MonoBehaviour" to AssetKind.SCRIPTABLE_OBJECT
      115 -> "MonoScript" to AssetKind.OTHER
      128 -> "Font" to AssetKind.OTHER
      134 -> "PhysicMaterial" to AssetKind.MATERIAL
      141 -> "BuildSettings" to AssetKind.OTHER
      142 -> "AssetBundle" to AssetKind.OTHER
      150 -> "PreloadData" to AssetKind.OTHER
      212 -> "SpriteRenderer" to AssetKind.SCENE
      213 -> "Sprite" to AssetKind.TEXTURE
      222 -> "CanvasRenderer" to AssetKind.SCENE
      223 -> "Canvas" to AssetKind.SCENE
      224 -> "RectTransform" to AssetKind.SCENE
      else -> "UnityClass_$classId" to AssetKind.OTHER
    }

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
