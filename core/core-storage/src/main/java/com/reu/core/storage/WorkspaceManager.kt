package com.reu.core.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.reu.core.common.FormatUtils
import com.reu.core.security.PathTraversalGuard
import java.io.File
import java.io.IOException

data class StorageStats(
  val workspaceBytes: Long,
  val tempBytes: Long,
  val exportBytes: Long,
  val availableDeviceBytes: Long,
)

data class ProjectWorkspace(
  val projectId: String,
  val rootDir: File,
  val sourceDir: File,
  val inspectionDir: File,
  val recoveredCodeDir: File,
  val extractedAssetsDir: File,
  val reconstructedProjectDir: File,
  val exportsDir: File,
)

class WorkspaceManager(private val context: Context) {
  val workspacesRoot: File
    get() = File(context.filesDir, "workspaces").apply { if (!exists()) mkdirs() }

  val toolsRoot: File
    get() = File(context.filesDir, "tools").apply { if (!exists()) mkdirs() }

  val exportsRoot: File
    get() = File(context.filesDir, "exports").apply { if (!exists()) mkdirs() }

  val tempRoot: File
    get() = File(context.cacheDir, "temp").apply { if (!exists()) mkdirs() }

  val logsRoot: File
    get() = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }

  fun getOrCreateWorkspace(projectId: String): ProjectWorkspace {
    val safeId = FormatUtils.sanitizeFileName(projectId)
    val root = PathTraversalGuard.resolveSafeTarget(workspacesRoot, safeId).apply { mkdirs() }
    return ProjectWorkspace(
      projectId = safeId,
      rootDir = root,
      sourceDir = File(root, "source").apply { mkdirs() },
      inspectionDir = File(root, "inspection").apply { mkdirs() },
      recoveredCodeDir = File(root, "recovered_code").apply { mkdirs() },
      extractedAssetsDir = File(root, "extracted_assets").apply { mkdirs() },
      reconstructedProjectDir = File(root, "RecoveredProject").apply { mkdirs() },
      exportsDir = File(root, "exports").apply { mkdirs() },
    )
  }

  fun deleteProjectWorkspace(projectId: String): Boolean {
    val safeId = FormatUtils.sanitizeFileName(projectId)
    val root = PathTraversalGuard.resolveSafeTarget(workspacesRoot, safeId)
    return if (root.exists()) root.deleteRecursively() else true
  }

  fun clearTempCache(): Long {
    val freed = directorySize(tempRoot)
    tempRoot.deleteRecursively()
    tempRoot.mkdirs()
    return freed
  }

  private fun availableStorageBytes(): Long {
    val statBytes = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    return if (statBytes > 0L) statBytes else context.filesDir.usableSpace
  }

  fun getStorageStats(): StorageStats {
    return StorageStats(
      workspaceBytes = directorySize(workspacesRoot),
      tempBytes = directorySize(tempRoot),
      exportBytes = directorySize(exportsRoot),
      availableDeviceBytes = availableStorageBytes(),
    )
  }

  fun ensureSufficientStorage(requiredBytes: Long, safetyMarginBytes: Long = 16L * 1024L * 1024L) {
    val available = availableStorageBytes()
    if (requiredBytes > 0 && available < requiredBytes + safetyMarginBytes) {
      throw IOException(
        "Insufficient storage: need ${FormatUtils.formatBytes(requiredBytes + safetyMarginBytes)}, " +
          "available ${FormatUtils.formatBytes(available)}",
      )
    }
  }

  fun resolveDisplayName(uri: Uri): String {
    if (uri.scheme == "file") {
      return File(uri.path ?: "import.bin").name
    }
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      cursor ->
      if (cursor.moveToFirst()) {
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0) {
          val name = cursor.getString(idx)
          if (!name.isNullOrBlank()) return name
        }
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "imported_build.bin"
  }

  fun resolveSize(uri: Uri): Long {
    if (uri.scheme == "file") {
      val f = File(uri.path ?: return -1L)
      return if (f.exists()) f.length() else -1L
    }
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
      cursor ->
      if (cursor.moveToFirst()) {
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
      }
    }
    return -1L
  }

  fun directorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    var sum = 0L
    dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
    return sum
  }
}
