package com.reu.engine.apk

import com.reu.core.common.FormatUtils
import com.reu.core.common.ImportSourceType
import com.reu.core.jobs.JobControlToken
import com.reu.core.security.IntegrityVerifier
import com.reu.core.security.PathTraversalGuard
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable

@Serializable
data class ArchiveInspectionSummary(
  val sourceType: ImportSourceType,
  val totalEntries: Int,
  val extractedRelevantFiles: Int,
  val totalUncompressedBytes: Long,
  val sha256: String,
  val detectedPackageName: String?,
  val detectedVersionName: String?,
  val splitApks: List<String>,
  val relevantRelativePaths: List<String>,
  val warnings: List<String>,
)

class ApkInspector {
  suspend fun inspectAndExtractRelevant(
    sourceFile: File,
    sourceType: ImportSourceType,
    destinationDir: File,
    bufferSizeBytes: Int = 262_144,
    token: JobControlToken? = null,
    onProgress: suspend (Int, String) -> Unit = { _, _ -> },
  ): ArchiveInspectionSummary {
    if (!destinationDir.exists()) destinationDir.mkdirs()
    val sha256 = IntegrityVerifier.sha256(sourceFile, bufferSizeBytes)

    return when (sourceType) {
      ImportSourceType.INDIVIDUAL_FILES -> {
        val target = PathTraversalGuard.resolveSafeTarget(destinationDir, sourceFile.name)
        sourceFile.copyTo(target, overwrite = true)
        ArchiveInspectionSummary(
          sourceType = sourceType,
          totalEntries = 1,
          extractedRelevantFiles = 1,
          totalUncompressedBytes = sourceFile.length(),
          sha256 = sha256,
          detectedPackageName = null,
          detectedVersionName = null,
          splitApks = emptyList(),
          relevantRelativePaths = listOf(sourceFile.name),
          warnings = emptyList(),
        )
      }
      ImportSourceType.DIRECTORY -> {
        inspectExtractedDirectory(sourceFile, destinationDir, sha256, token, onProgress)
      }
      ImportSourceType.APK,
      ImportSourceType.XAPK,
      ImportSourceType.APKS,
      ImportSourceType.ZIP -> {
        inspectZipContainer(sourceFile, sourceType, destinationDir, sha256, bufferSizeBytes, token, onProgress)
      }
    }
  }

  private suspend fun inspectExtractedDirectory(
    dir: File,
    destinationDir: File,
    sha256: String,
    token: JobControlToken?,
    onProgress: suspend (Int, String) -> Unit,
  ): ArchiveInspectionSummary {
    val relevant = mutableListOf<String>()
    var totalEntries = 0
    var totalBytes = 0L
    val files = dir.walkTopDown().filter { it.isFile }.toList()
    files.forEachIndexed { idx, file ->
      token?.checkPoint()
      totalEntries++
      totalBytes += file.length()
      val rel = file.relativeTo(dir).path.replace('\\', '/')
      if (isUnityRelevantPath(rel)) {
        val out = PathTraversalGuard.resolveSafeTarget(destinationDir, rel)
        out.parentFile?.mkdirs()
        file.copyTo(out, overwrite = true)
        relevant.add(rel)
      }
      if (files.isNotEmpty() && idx % 10 == 0) {
        onProgress((idx * 100) / files.size, "Scanning $rel")
      }
    }
    return ArchiveInspectionSummary(
      sourceType = ImportSourceType.DIRECTORY,
      totalEntries = totalEntries,
      extractedRelevantFiles = relevant.size,
      totalUncompressedBytes = totalBytes,
      sha256 = sha256,
      detectedPackageName = null,
      detectedVersionName = null,
      splitApks = emptyList(),
      relevantRelativePaths = relevant,
      warnings = emptyList(),
    )
  }

  private suspend fun inspectZipContainer(
    archiveFile: File,
    sourceType: ImportSourceType,
    destinationDir: File,
    sha256: String,
    bufferSizeBytes: Int,
    token: JobControlToken?,
    onProgress: suspend (Int, String) -> Unit,
  ): ArchiveInspectionSummary {
    val relevantPaths = mutableListOf<String>()
    val splitApks = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    var totalEntries = 0
    var totalUncompressed = 0L
    var pkgName: String? = null
    var verName: String? = null
    val buf = ByteArray(bufferSizeBytes.coerceIn(8192, 1024 * 1024))

    ZipInputStream(BufferedInputStream(archiveFile.inputStream(), buf.size)).use { zis ->
      var entry: ZipEntry? = zis.nextEntry
      while (entry != null) {
        token?.checkPoint()
        totalEntries++
        if (totalEntries > PathTraversalGuard.DEFAULT_MAX_ARCHIVE_ENTRIES) {
          warnings.add("Archive entry limit (${PathTraversalGuard.DEFAULT_MAX_ARCHIVE_ENTRIES}) reached; truncated scan.")
          break
        }
        val name = entry.name
        if (!PathTraversalGuard.isPathSafe(name)) {
          warnings.add("Blocked unsafe archive entry path: $name")
          zis.closeEntry()
          entry = zis.nextEntry
          continue
        }
        if (!entry.isDirectory) {
          val lower = name.lowercase()
          if (lower.endsWith(".apk")) {
            splitApks.add(name)
            val nestedApk = PathTraversalGuard.resolveSafeTarget(destinationDir, "_splits/${FormatUtils.sanitizeFileName(name)}")
            nestedApk.parentFile?.mkdirs()
            val copied = copyBoundedEntry(zis, nestedApk, buf, PathTraversalGuard.DEFAULT_MAX_ENTRY_BYTES)
            totalUncompressed += copied
            val nestedSummary =
              inspectZipContainer(
                archiveFile = nestedApk,
                sourceType = ImportSourceType.APK,
                destinationDir = destinationDir,
                sha256 = sha256,
                bufferSizeBytes = bufferSizeBytes,
                token = token,
                onProgress = onProgress,
              )
            relevantPaths.addAll(nestedSummary.relevantRelativePaths)
            if (pkgName == null) pkgName = nestedSummary.detectedPackageName
            if (verName == null) verName = nestedSummary.detectedVersionName
            warnings.addAll(nestedSummary.warnings)
          } else if (isUnityRelevantPath(name) || lower == "androidmanifest.xml" || lower == "manifest.json") {
            val outFile = PathTraversalGuard.resolveSafeTarget(destinationDir, name)
            outFile.parentFile?.mkdirs()
            val copied = copyBoundedEntry(zis, outFile, buf, PathTraversalGuard.DEFAULT_MAX_ENTRY_BYTES)
            totalUncompressed += copied
            relevantPaths.add(name)
            if (lower == "androidmanifest.xml") {
              pkgName = extractPackageHintFromBinaryManifest(outFile) ?: pkgName
            } else if (lower == "manifest.json") {
              val jsonTxt = runCatching { outFile.readText() }.getOrNull()
              if (jsonTxt != null) {
                pkgName = Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"").find(jsonTxt)?.groupValues?.get(1) ?: pkgName
                verName = Regex("\"version_name\"\\s*:\\s*\"([^\"]+)\"").find(jsonTxt)?.groupValues?.get(1) ?: verName
              }
            }
          } else {
            val reportedSize = entry.size
            if (reportedSize > 0) totalUncompressed += reportedSize
          }
        }
        if (totalEntries % 25 == 0) {
          onProgress(50, "Scanned $totalEntries entries (${relevantPaths.size} Unity files)")
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    onProgress(100, "Archive inspection complete (${relevantPaths.size} relevant files)")
    return ArchiveInspectionSummary(
      sourceType = sourceType,
      totalEntries = totalEntries,
      extractedRelevantFiles = relevantPaths.size,
      totalUncompressedBytes = totalUncompressed,
      sha256 = sha256,
      detectedPackageName = pkgName,
      detectedVersionName = verName,
      splitApks = splitApks.distinct(),
      relevantRelativePaths = relevantPaths.distinct(),
      warnings = warnings,
    )
  }

  private fun copyBoundedEntry(
    zis: ZipInputStream,
    outFile: File,
    buf: ByteArray,
    maxBytes: Long,
  ): Long {
    var total = 0L
    FileOutputStream(outFile).use { fos ->
      var read = zis.read(buf)
      while (read > 0) {
        total += read
        if (total > maxBytes) {
          throw SecurityException("Archive entry exceeds safety limit of $maxBytes bytes: ${outFile.name}")
        }
        fos.write(buf, 0, read)
        read = zis.read(buf)
      }
    }
    return total
  }

  fun isUnityRelevantPath(entryPath: String): Boolean {
    val p = entryPath.replace('\\', '/').lowercase()
    return p.contains("libunity.so") ||
      p.contains("libil2cpp.so") ||
      p.contains("libmono") ||
      p.contains("global-metadata.dat") ||
      p.endsWith(".dll") ||
      p.endsWith(".assets") ||
      p.endsWith(".ress") ||
      p.endsWith(".resource") ||
      p.endsWith(".bundle") ||
      p.endsWith(".unity3d") ||
      p.contains("/assets/bin/data/") ||
      p.startsWith("assets/bin/data/") ||
      p.contains("/assets/aa/") ||
      p.startsWith("assets/aa/") ||
      p.contains("globalgamemanagers") ||
      Regex("(^|/)level[0-9]+$").containsMatchIn(p) ||
      p.contains("streamingassets/") ||
      p.endsWith(".so")
  }

  private fun extractPackageHintFromBinaryManifest(manifestFile: File): String? {
    if (!manifestFile.exists() || manifestFile.length() > 2 * 1024 * 1024) return null
    val bytes = manifestFile.readBytes()
    // Quick plain-text XML check first
    if (bytes.size > 5 && bytes[0] == '<'.code.toByte()) {
      val text = String(bytes, StandardCharsets.UTF_8)
      return Regex("package=\"([A-Za-z0-9_.]+)\"").find(text)?.groupValues?.get(1)
    }
    // Binary AXML UTF-16LE string scan for reverse-DNS package identifier near manifest strings
    val utf16 = String(bytes, StandardCharsets.UTF_16LE)
    val candidates = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,5}").findAll(utf16).map { it.value }.toList()
    return candidates.firstOrNull {
      !it.startsWith("android.") &&
        !it.startsWith("com.android.") &&
        !it.startsWith("androidx.") &&
        !it.startsWith("com.google.")
    }
  }
}
