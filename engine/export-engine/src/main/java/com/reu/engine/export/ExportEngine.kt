package com.reu.engine.export

import com.reu.core.jobs.JobControlToken
import com.reu.core.security.IntegrityVerifier
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ZipExportResult(
  val success: Boolean,
  val zipFile: File,
  val fileCount: Int,
  val zipSizeBytes: Long,
  val sha256: String,
)

class ExportEngine {
  suspend fun exportDirectoryToZip(
    sourceDir: File,
    destinationZip: File,
    token: JobControlToken? = null,
    onProgress: suspend (Int, String) -> Unit = { _, _ -> },
  ): ZipExportResult {
    require(sourceDir.exists() && sourceDir.isDirectory) { "Source directory does not exist: ${sourceDir.path}" }
    destinationZip.parentFile?.mkdirs()

    val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
    val buf = ByteArray(65_536)
    var count = 0

    ZipOutputStream(BufferedOutputStream(FileOutputStream(destinationZip))).use { zos ->
      for ((idx, f) in files.withIndex()) {
        token?.checkPoint()
        val rel = f.relativeTo(sourceDir).path.replace('\\', '/')
        zos.putNextEntry(ZipEntry(rel))
        f.inputStream().use { input ->
          var read = input.read(buf)
          while (read > 0) {
            zos.write(buf, 0, read)
            read = input.read(buf)
          }
        }
        zos.closeEntry()
        count++
        if (files.isNotEmpty() && idx % 10 == 0) {
          onProgress(((idx + 1) * 100) / files.size, "Archiving $rel")
        }
      }
    }

    val sha = IntegrityVerifier.sha256(destinationZip)
    onProgress(100, "Exported $count files to ${destinationZip.name}")
    return ZipExportResult(
      success = true,
      zipFile = destinationZip,
      fileCount = count,
      zipSizeBytes = destinationZip.length(),
      sha256 = sha,
    )
  }
}
