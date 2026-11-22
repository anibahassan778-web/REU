package com.reu.engine.reconstruction

import com.reu.core.common.AssetKind
import com.reu.core.common.UnityScriptingBackend
import com.reu.core.database.AssetEntryEntity
import com.reu.core.database.CodeSymbolEntity
import com.reu.core.database.DependencyEdgeEntity
import com.reu.core.database.ProjectEntity
import com.reu.engine.mono.MonoAnalyzer
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class RebuildMode {
  CODE_ONLY,
  FULL_PROJECT,
}

@Serializable
data class ReconstructionReport(
  val projectName: String,
  val outputDirectory: String,
  val unityVersionUsed: String?,
  val scriptingBackend: UnityScriptingBackend,
  val mode: RebuildMode = RebuildMode.FULL_PROJECT,
  val scriptsWritten: Int,
  val assetsCopied: Int,
  val missingOrPlaceholderCount: Int,
  val excludedLibraryCount: Int = 0,
  val validationPassed: Boolean,
  val validationNotes: List<String>,
)

class ProjectRebuilder {
  private val json = Json { prettyPrint = true }

  fun rebuildProject(
    project: ProjectEntity,
    assets: List<AssetEntryEntity>,
    symbols: List<CodeSymbolEntity>,
    edges: List<DependencyEdgeEntity>,
    logs: List<String>,
    outputRoot: File,
    mode: RebuildMode = RebuildMode.FULL_PROJECT,
    referencedLibraries: List<String> = emptyList(),
  ): ReconstructionReport {
    if (outputRoot.exists()) outputRoot.deleteRecursively()
    outputRoot.mkdirs()

    // Filter out included/framework/third-party libraries so installing packages never causes duplicate conflicts
    val gameSymbols = symbols.filter { !MonoAnalyzer.isIncludedLibraryAssembly(it.assemblyName) }
    val excludedLibNames =
      (symbols.filter { MonoAnalyzer.isIncludedLibraryAssembly(it.assemblyName) }.map { it.assemblyName } + referencedLibraries)
        .distinct()
        .sorted()

    val groupedTypes = gameSymbols.groupBy { "${it.assemblyName}::${it.namespaceName}::${it.typeName}" }

    if (mode == RebuildMode.CODE_ONLY) {
      val scriptsRoot = File(outputRoot, "Scripts").apply { mkdirs() }
      val scriptsWritten = writeScriptsTree(groupedTypes, scriptsRoot, includeNamespaceFolders = true, includeHeaderBanner = false)

      val notes =
        listOf(
          "Code-only recovery completed: $scriptsWritten game script(s) written to Scripts/.",
          "Excluded ${excludedLibNames.size} engine/third-party library assemblies to avoid duplicate reinstallation errors.",
          "Assets, audio, scenes, and non-code files omitted as requested.",
        )

      return ReconstructionReport(
        projectName = project.displayName,
        outputDirectory = outputRoot.absolutePath,
        unityVersionUsed = project.unityVersion,
        scriptingBackend = project.scriptingBackend,
        mode = RebuildMode.CODE_ONLY,
        scriptsWritten = scriptsWritten,
        assetsCopied = 0,
        missingOrPlaceholderCount = 0,
        excludedLibraryCount = excludedLibNames.size,
        validationPassed = scriptsRoot.exists(),
        validationNotes = notes,
      )
    }

    val assetsDir = File(outputRoot, "Assets").apply { mkdirs() }
    val subdirs =
      listOf(
        "Scripts",
        "Scenes",
        "Prefabs",
        "Materials",
        "Textures",
        "Sprites",
        "Models",
        "Audio",
        "Animations",
        "Shaders",
        "Fonts",
        "Resources",
        "ScriptableObjects",
      )
    val dirMap = subdirs.associateWith { File(assetsDir, it).apply { mkdirs() } }
    val apkExtrasDir = File(outputRoot, "APK_Resources").apply { mkdirs() }
    val packagesDir = File(outputRoot, "Packages").apply { mkdirs() }
    val settingsDir = File(outputRoot, "ProjectSettings").apply { mkdirs() }
    val reportDir = File(outputRoot, "REU_Report").apply { mkdirs() }

    // 1. Export ONLY game scripts grouped by assembly (never re-export included libraries)
    val scriptsWritten = writeScriptsTree(groupedTypes, dirMap.getValue("Scripts"), includeNamespaceFolders = false, includeHeaderBanner = false)

    // 2. Copy all extracted assets into canonical Unity subfolders without overwriting collisions
    var assetsCopied = 0
    var indexedMetadataAndSceneObjects = 0
    val missingFiles = mutableListOf<String>()
    val usedDestinations = HashSet<String>()

    for (asset in assets) {
      val extracted = asset.extractedFilePath?.let { File(it) }
      if (extracted != null && extracted.exists() && extracted.isFile) {
        val origPath = asset.originalPath.orEmpty()
        val dest: File =
          if (asset.sourceArchivePath.startsWith("apk://") || origPath.startsWith("res/") || origPath == "AndroidManifest.xml") {
            val relSub = origPath.trimStart('/').replace("..", "_")
            File(apkExtrasDir, relSub).also { it.parentFile?.mkdirs() }
          } else {
            val folderName =
              when (asset.kind) {
                AssetKind.TEXTURE -> if (asset.unityTypeName == "Sprite") "Sprites" else "Textures"
                AssetKind.AUDIO -> "Audio"
                AssetKind.MESH -> "Models"
                AssetKind.MATERIAL -> "Materials"
                AssetKind.ANIMATION -> "Animations"
                AssetKind.SCENE -> "Scenes"
                AssetKind.PREFAB -> "Prefabs"
                AssetKind.SCRIPTABLE_OBJECT -> "ScriptableObjects"
                AssetKind.SHADER -> "Shaders"
                AssetKind.TEXT_ASSET -> "Resources"
                AssetKind.OTHER ->
                  when {
                    asset.unityTypeName == "Font" || extracted.extension.equals("ttf", true) || extracted.extension.equals("otf", true) -> "Fonts"
                    else -> "Resources"
                  }
              }
            val targetDir = dirMap.getValue(folderName)
            uniqueFile(targetDir, extracted.name, usedDestinations)
          }
        extracted.copyTo(dest, overwrite = true)
        assetsCopied++
      } else if (isMetadataOrDynamicPlaceholder(asset)) {
        indexedMetadataAndSceneObjects++
      } else {
        missingFiles.add("${asset.kind.name} (${asset.unityTypeName}): ${asset.name} [source=${asset.sourceArchivePath}, status=${asset.extractionStatus}]")
      }
    }

    // 3. Packages/manifest.json
    val extraPkgs = buildList {
      if (excludedLibNames.any { it.equals("Newtonsoft.Json.dll", ignoreCase = true) }) {
        add("\"com.unity.nuget.newtonsoft-json\": \"3.2.1\"")
      }
      if (excludedLibNames.any { it.contains("TextMeshPro", ignoreCase = true) }) {
        add("\"com.unity.textmeshpro\": \"3.0.6\"")
      }
      if (excludedLibNames.any { it.contains("Advertisement", ignoreCase = true) || it.contains("UnityEngine.Advertisements", ignoreCase = true) }) {
        add("\"com.unity.ads\": \"4.4.2\"")
      }
    }
    val extraPkgSuffix =
      if (extraPkgs.isEmpty()) "" else ",\n" + extraPkgs.joinToString(",\n") { "    $it" }
    File(packagesDir, "manifest.json").writeText(
      buildString {
        appendLine("{")
        appendLine("  \"dependencies\": {")
        appendLine("    \"com.unity.ugui\": \"1.0.0\",")
        appendLine("    \"com.unity.modules.audio\": \"1.0.0\",")
        appendLine("    \"com.unity.modules.imageconversion\": \"1.0.0\",")
        appendLine("    \"com.unity.modules.physics\": \"1.0.0\",")
        append("    \"com.unity.modules.physics2d\": \"1.0.0\"")
        appendLine(extraPkgSuffix)
        appendLine("  }")
        appendLine("}")
      },
    )

    // 4. ProjectSettings/ProjectVersion.txt ONLY when Unity version has evidence
    val notes = mutableListOf<String>()
    if (!project.unityVersion.isNullOrBlank()) {
      File(settingsDir, "ProjectVersion.txt").writeText(
        "m_EditorVersion: ${project.unityVersion}\n# Evidence: ${project.unityVersionEvidence ?: "Header scan"}\n",
      )
    } else {
      notes.add("ProjectVersion.txt omitted because Unity version could not be confirmed from binary evidence.")
    }

    // 5. REU_Report files
    File(reportDir, "analysis.json").writeText(
      """
      {
        "projectName": "${project.displayName}",
        "packageName": "${project.packageName ?: "unknown"}",
        "sha256": "${project.sourceSha256}",
        "unityVersion": ${project.unityVersion?.let { "\"$it\"" } ?: "null"},
        "scriptingBackend": "${project.scriptingBackend.name}",
        "gameSymbolCount": ${gameSymbols.size},
        "excludedLibraryCount": ${excludedLibNames.size},
        "assetCount": ${assets.size}
      }
      """.trimIndent(),
    )

    File(reportDir, "referenced_libraries.json").writeText(json.encodeToString(excludedLibNames))

    val missingEdges = edges.count { it.isMissing }
    File(reportDir, "recovery.json").writeText(
      """
      {
        "scriptsWritten": $scriptsWritten,
        "excludedLibraries": ${excludedLibNames.size},
        "assetsCopied": $assetsCopied,
        "indexedMetadataAndSceneObjects": $indexedMetadataAndSceneObjects,
        "missingOrUnextractedAssets": ${missingFiles.size},
        "brokenDependencyEdges": $missingEdges,
        "provenanceNotice": "Textures, sprites, fonts, text assets, and APK resources are ORIGINAL_RECOVERED; C# scripts, scenes, materials, meshes, and shaders are DECOMPILED_RECONSTRUCTED."
      }
      """.trimIndent(),
    )

    File(reportDir, "missing_files.json").writeText(json.encodeToString(missingFiles))

    val compatNote =
      when (project.scriptingBackend) {
        UnityScriptingBackend.MONO ->
          "Mono build: reconstructed C# classes & CIL bodies exported for game assemblies only; ${excludedLibNames.size} engine/library DLLs excluded; all extracted textures, sprites, fonts, shaders, materials, models, animations, and scenes copied into Assets/."
        UnityScriptingBackend.IL2CPP ->
          "IL2CPP build: dummy C# signature stubs exported from global-metadata.dat; native method bodies require manual re-implementation; extracted assets copied into Assets/."
        else -> "Mixed or unknown scripting backend; inspect missing_files.json."
      }
    File(reportDir, "compatibility.json").writeText(
      """
      {
        "unityEditorInstalledOnDevice": false,
        "targetUnityVersion": ${project.unityVersion?.let { "\"$it\"" } ?: "null"},
        "compatibilitySummary": "$compatNote"
      }
      """.trimIndent(),
    )

    File(reportDir, "recovery_log.txt").writeText(
      logs.joinToString("\n").ifBlank { "REU project rebuild executed cleanly at ${System.currentTimeMillis()}." },
    )

    val validationPassed =
      assetsDir.exists() && packagesDir.exists() && settingsDir.exists() && reportDir.exists() &&
        File(packagesDir, "manifest.json").exists() && File(reportDir, "analysis.json").exists()

    return ReconstructionReport(
      projectName = project.displayName,
      outputDirectory = outputRoot.absolutePath,
      unityVersionUsed = project.unityVersion,
      scriptingBackend = project.scriptingBackend,
      mode = RebuildMode.FULL_PROJECT,
      scriptsWritten = scriptsWritten,
      assetsCopied = assetsCopied,
      missingOrPlaceholderCount = missingFiles.size,
      excludedLibraryCount = excludedLibNames.size,
      validationPassed = validationPassed,
      validationNotes = notes + compatNote,
    )
  }

  private fun writeScriptsTree(
    groupedTypes: Map<String, List<CodeSymbolEntity>>,
    scriptsRoot: File,
    includeNamespaceFolders: Boolean,
    includeHeaderBanner: Boolean,
  ): Int {
    var count = 0
    val used = HashSet<String>()
    for ((_, group) in groupedTypes.entries) {
      val first = group.first()
      val safeAsm = first.assemblyName.replace(Regex("[^A-Za-z0-9._-]"), "_")
      val asmDir = File(scriptsRoot, safeAsm).apply { mkdirs() }
      val nsSubPath =
        if (includeNamespaceFolders) {
          first.namespaceName
            .takeIf { it.isNotBlank() && it != "<global>" }
            ?.split('.')
            ?.joinToString("/") { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        } else null
      val targetDir = if (nsSubPath != null) File(asmDir, nsSubPath).apply { mkdirs() } else asmDir
      val safeType = first.typeName.substringBefore('`').replace(Regex("[^A-Za-z0-9._-]"), "_")
      val csFile = uniqueFile(targetDir, "$safeType.cs", used)
      val content =
        if (includeHeaderBanner) {
          "// Generated by REU ProjectRebuilder\n// Provenance: ${first.provenance.name}\n${first.sourcePreview}"
        } else {
          first.sourcePreview
        }
      csFile.writeText(content)
      count++
    }
    return count
  }

  private fun isMetadataOrDynamicPlaceholder(asset: AssetEntryEntity): Boolean {
    val type = asset.unityTypeName
    val status = asset.extractionStatus
    if (status.startsWith("Dynamic/", ignoreCase = true)) return true
    if (type in setOf(
        "GameObject",
        "Transform",
        "RectTransform",
        "MonoScript",
        "MonoBehaviour",
        "BuildSettings",
        "PreloadData",
        "AssetBundle",
        "CanvasRenderer",
        "Canvas",
        "SpriteRenderer",
        "Camera",
        "Light",
        "AudioSource",
        "MeshRenderer",
        "MeshFilter",
      )
    ) {
      return true
    }
    return type.startsWith("UnityClass_")
  }

  private fun uniqueFile(dir: File, desiredName: String, usedKeys: MutableSet<String>): File {
    val dotIdx = desiredName.lastIndexOf('.')
    val base = if (dotIdx > 0) desiredName.substring(0, dotIdx) else desiredName
    val ext = if (dotIdx > 0) desiredName.substring(dotIdx) else ""
    var candidateName = desiredName
    var n = 2
    while (!usedKeys.add("${dir.absolutePath}/$candidateName")) {
      candidateName = "${base}_$n$ext"
      n++
    }
    return File(dir, candidateName)
  }
}
