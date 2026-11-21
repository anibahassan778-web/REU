package com.reu.core.security

import java.io.File

/**
 * Prevents Zip-Slip / archive path traversal attacks and enforces strict bounds
 * when inspecting or extracting untrusted APK/XAPK/ZIP archives.
 */
object PathTraversalGuard {
  const val DEFAULT_MAX_ENTRY_BYTES = 512L * 1024L * 1024L // 512 MB per entry cap
  const val DEFAULT_MAX_ARCHIVE_ENTRIES = 100_000

  fun resolveSafeTarget(destinationRoot: File, entryName: String): File {
    require(entryName.isNotBlank()) { "Archive entry name is blank" }
    require(!entryName.contains('\u0000')) { "Archive entry contains null byte: $entryName" }

    val sanitized = entryName.replace('\\', '/').trimStart('/')
    require(!sanitized.startsWith("..")) { "Path traversal attempt rejected: $entryName" }

    val rootCanonical = destinationRoot.canonicalFile
    val candidate = File(rootCanonical, sanitized).canonicalFile
    val rootPathWithSep =
      if (rootCanonical.path.endsWith(File.separator)) rootCanonical.path
      else rootCanonical.path + File.separator

    if (candidate != rootCanonical && !candidate.path.startsWith(rootPathWithSep)) {
      throw SecurityException("Zip-Slip path traversal blocked for entry '$entryName' -> '${candidate.path}'")
    }
    return candidate
  }

  fun isPathSafe(entryName: String): Boolean {
    if (entryName.isBlank() || entryName.contains('\u0000')) return false
    val normalized = entryName.replace('\\', '/')
    if (normalized.startsWith("/")) return false
    val segments = normalized.split('/')
    var depth = 0
    for (seg in segments) {
      if (seg.isEmpty() || seg == ".") continue
      if (seg == "..") {
        depth--
        if (depth < 0) return false
      } else {
        depth++
      }
    }
    return true
  }
}
