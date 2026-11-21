package com.reu.core.common

import kotlinx.serialization.Serializable

/**
 * Mandatory provenance classification distinguishing authentic recovered bytes
 * from decompiled, generated placeholder, or missing artifacts.
 */
@Serializable
enum class DataProvenance {
  ORIGINAL_RECOVERED,
  DECOMPILED_RECONSTRUCTED,
  GENERATED_PLACEHOLDER,
  MISSING_UNRECOVERABLE,
}

@Serializable
enum class UnityScriptingBackend {
  MONO,
  IL2CPP,
  HYBRID,
  UNKNOWN,
}

@Serializable
enum class ImportSourceType {
  APK,
  XAPK,
  APKS,
  ZIP,
  DIRECTORY,
  INDIVIDUAL_FILES,
}

@Serializable
enum class AppLanguage {
  SYSTEM,
  ENGLISH,
  ARABIC,
}

@Serializable
enum class JobStatus {
  QUEUED,
  RUNNING,
  PAUSED,
  COMPLETED,
  FAILED,
  CANCELLED,
}

@Serializable
enum class JobType {
  IMPORT,
  DETECT_BUILD,
  ANALYZE_MONO,
  ANALYZE_IL2CPP,
  ANALYZE_ELF,
  EXTRACT_ASSETS,
  REBUILD_PROJECT,
  EXPORT_PROJECT,
  INSTALL_TOOL,
}

@Serializable
enum class AssetKind {
  TEXTURE,
  AUDIO,
  MESH,
  MATERIAL,
  ANIMATION,
  SCENE,
  PREFAB,
  SCRIPTABLE_OBJECT,
  SHADER,
  TEXT_ASSET,
  OTHER,
}

sealed class ReuResult<out T> {
  data class Success<T>(val data: T) : ReuResult<T>()

  data class Failure(
    val message: String,
    val cause: Throwable? = null,
    val recoverable: Boolean = true,
  ) : ReuResult<Nothing>()
}

object FormatUtils {
  fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
  }

  fun toHexString(bytes: ByteArray, maxBytes: Int = bytes.size): String {
    val count = minOf(bytes.size, maxBytes)
    val sb = StringBuilder(count * 2)
    for (i in 0 until count) {
      sb.append(String.format(java.util.Locale.US, "%02x", bytes[i]))
    }
    return sb.toString()
  }

  fun sanitizeFileName(raw: String): String {
    return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unnamed" }
  }
}
