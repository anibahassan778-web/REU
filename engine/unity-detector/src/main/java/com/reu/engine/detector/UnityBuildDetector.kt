package com.reu.engine.detector

import com.reu.core.common.UnityScriptingBackend
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
data class UnityDetectionReport(
  val isUnityBuild: Boolean,
  val unityVersion: String?,
  val unityVersionEvidence: String?,
  val scriptingBackend: UnityScriptingBackend,
  val architectures: List<String>,
  val nativeLibraries: List<String>,
  val managedAssemblies: List<String>,
  val metadataFiles: List<String>,
  val assetFiles: List<String>,
  val assetBundles: List<String>,
  val levelFiles: List<String>,
  val streamingAssets: List<String>,
  val hasGlobalGameManagers: Boolean,
  val detectionNotes: List<String>,
)

class UnityBuildDetector {
  private val unityVersionRegex = Regex("\\b((?:5|201[7-9]|202[0-4]|6000)\\.\\d+\\.\\d+[abfp]\\d+)\\b")

  fun detect(workspaceExtractedDir: File): UnityDetectionReport {
    if (!workspaceExtractedDir.exists()) {
      return UnityDetectionReport(
        isUnityBuild = false,
        unityVersion = null,
        unityVersionEvidence = null,
        scriptingBackend = UnityScriptingBackend.UNKNOWN,
        architectures = emptyList(),
        nativeLibraries = emptyList(),
        managedAssemblies = emptyList(),
        metadataFiles = emptyList(),
        assetFiles = emptyList(),
        assetBundles = emptyList(),
        levelFiles = emptyList(),
        streamingAssets = emptyList(),
        hasGlobalGameManagers = false,
        detectionNotes = listOf("Extracted directory does not exist."),
      )
    }

    val allFiles = workspaceExtractedDir.walkTopDown().filter { it.isFile }.toList()
    val nativeLibs = mutableListOf<String>()
    val assemblies = mutableListOf<String>()
    val metadataFiles = mutableListOf<String>()
    val assetFiles = mutableListOf<String>()
    val assetBundles = mutableListOf<String>()
    val levelFiles = mutableListOf<String>()
    val streamingAssets = mutableListOf<String>()
    val archSet = linkedSetOf<String>()
    val notes = mutableListOf<String>()

    var hasI2cppLib = false
    var hasMonoLib = false
    var hasUnityLib = false
    var hasAssemblyCSharp = false
    var hasGlobalMetadata = false
    var hasGgm = false

    for (file in allFiles) {
      val rel = file.relativeTo(workspaceExtractedDir).path.replace('\\', '/')
      val lower = rel.lowercase()
      val name = file.name.lowercase()

      if (lower.startsWith("lib/") || lower.contains("/lib/")) {
        val parts = rel.split('/')
        val libIdx = parts.indexOfFirst { it.equals("lib", ignoreCase = true) }
        if (libIdx >= 0 && libIdx + 1 < parts.size) {
          val abi = parts[libIdx + 1]
          if (abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            archSet.add(abi)
          }
        }
      }

      when {
        name.endsWith(".so") -> {
          nativeLibs.add(rel)
          if (name == "libil2cpp.so") hasI2cppLib = true
          if (name.startsWith("libmono")) hasMonoLib = true
          if (name == "libunity.so") hasUnityLib = true
        }
        name.endsWith(".dll") -> {
          assemblies.add(rel)
          if (name == "assembly-csharp.dll") hasAssemblyCSharp = true
        }
        name == "global-metadata.dat" -> {
          metadataFiles.add(rel)
          hasGlobalMetadata = true
        }
        name.startsWith("globalgamemanagers") -> {
          hasGgm = true
          assetFiles.add(rel)
        }
        name.endsWith(".assets") || name.endsWith(".ress") || name.endsWith(".resource") || name == "data.unity3d" -> {
          assetFiles.add(rel)
        }
        name.endsWith(".bundle") || name.endsWith(".unity3d") -> {
          assetBundles.add(rel)
        }
        Regex("^level[0-9]+$").matches(name) -> {
          levelFiles.add(rel)
        }
        lower.contains("streamingassets/") -> {
          streamingAssets.add(rel)
        }
      }
    }

    val backend =
      when {
        (hasI2cppLib || hasGlobalMetadata) && (hasMonoLib || hasAssemblyCSharp) -> UnityScriptingBackend.HYBRID
        hasI2cppLib || hasGlobalMetadata -> UnityScriptingBackend.IL2CPP
        hasMonoLib || hasAssemblyCSharp -> UnityScriptingBackend.MONO
        else -> UnityScriptingBackend.UNKNOWN
      }

    // Search strictly ordered evidence sources for Unity engine version; never guess!
    val evidenceCandidates =
      allFiles.sortedBy { f ->
        val n = f.name.lowercase()
        when {
          n == "globalgamemanagers" -> 0
          n == "data.unity3d" -> 1
          n.endsWith(".bundle") -> 2
          n == "resources.assets" -> 3
          n.startsWith("level") -> 4
          n == "unityengine.coremodule.dll" -> 5
          n == "libunity.so" -> 6
          else -> 10
        }
      }

    var detectedVersion: String? = null
    var versionEvidence: String? = null
    for (candidate in evidenceCandidates) {
      val found = scanHeaderForUnityVersion(candidate)
      if (found != null) {
        detectedVersion = found
        versionEvidence = candidate.relativeTo(workspaceExtractedDir).path.replace('\\', '/')
        break
      }
    }

    val isUnity =
      hasUnityLib ||
        hasI2cppLib ||
        hasMonoLib ||
        hasGlobalMetadata ||
        hasGgm ||
        hasAssemblyCSharp ||
        assetBundles.isNotEmpty() ||
        assetFiles.isNotEmpty()

    if (detectedVersion == null && isUnity) {
      notes.add("Unity artifacts detected, but no explicit Unity version string could be verified from headers.")
    }
    if (backend == UnityScriptingBackend.IL2CPP && !hasGlobalMetadata) {
      notes.add("libil2cpp.so detected without global-metadata.dat; metadata may be split or stored externally.")
    }

    return UnityDetectionReport(
      isUnityBuild = isUnity,
      unityVersion = detectedVersion,
      unityVersionEvidence = versionEvidence,
      scriptingBackend = backend,
      architectures = archSet.toList(),
      nativeLibraries = nativeLibs.sorted(),
      managedAssemblies = assemblies.sorted(),
      metadataFiles = metadataFiles.sorted(),
      assetFiles = assetFiles.sorted(),
      assetBundles = assetBundles.sorted(),
      levelFiles = levelFiles.sorted(),
      streamingAssets = streamingAssets.sorted(),
      hasGlobalGameManagers = hasGgm,
      detectionNotes = notes,
    )
  }

  private fun scanHeaderForUnityVersion(file: File): String? {
    if (!file.exists() || file.length() < 16) return null
    val maxScanBytes = minOf(file.length(), 512L * 1024L).toInt()
    val buf = ByteArray(maxScanBytes)
    file.inputStream().use { input ->
      val read = input.read(buf)
      if (read <= 0) return null
      val text = String(buf, 0, read, StandardCharsets.ISO_8859_1)
      return unityVersionRegex.find(text)?.groupValues?.get(1)
    }
  }
}
