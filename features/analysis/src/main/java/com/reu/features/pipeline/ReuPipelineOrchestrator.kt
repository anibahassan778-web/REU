package com.reu.features.pipeline

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.reu.core.common.AssetKind
import com.reu.core.common.DataProvenance
import com.reu.core.common.FormatUtils
import com.reu.core.common.ImportSourceType
import com.reu.core.common.UnityScriptingBackend
import com.reu.core.database.AssetEntryEntity
import com.reu.core.database.CodeSymbolEntity
import com.reu.core.database.ProjectEntity
import com.reu.core.database.ReuRepository
import com.reu.core.jobs.JobControlToken
import com.reu.core.logging.ReuLogger
import com.reu.core.security.IntegrityVerifier
import com.reu.core.security.PathTraversalGuard
import com.reu.core.storage.WorkspaceManager
import com.reu.engine.apk.ApkInspector
import com.reu.engine.asset.ParsedUnityObject
import com.reu.engine.asset.UnityAssetParser
import com.reu.engine.audio.AudioEngine
import com.reu.engine.dependency.DependencyEngine
import com.reu.engine.detector.UnityBuildDetector
import com.reu.engine.detector.UnityDetectionReport
import com.reu.engine.elf.ElfAnalysisReport
import com.reu.engine.elf.ElfAnalyzer
import com.reu.engine.export.ExportEngine
import com.reu.engine.export.ZipExportResult
import com.reu.engine.il2cpp.Il2CppPipelineReport
import com.reu.engine.model.MeshDataModel
import com.reu.engine.model.ModelEngine
import com.reu.engine.reconstruction.ProjectRebuilder
import com.reu.engine.reconstruction.ReconstructionReport
import com.reu.engine.scene.SceneEngine
import com.reu.engine.scene.SceneGraphSummary
import com.reu.engine.texture.TextureEngine
import com.reu.engine.texture.TextureExportFormat
import com.reu.integrations.UnityPyAdapter
import com.reu.integrations.cpp2il.Cpp2IlAdapter
import com.reu.integrations.ilspy.IlSpyAdapter
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ImportOutcome(
  val project: ProjectEntity,
  val isDuplicate: Boolean,
  val detection: UnityDetectionReport,
)

class ReuPipelineOrchestrator(
  private val context: Context,
  private val repository: ReuRepository,
  private val workspaceManager: WorkspaceManager,
  private val logger: ReuLogger,
) {
  private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
  private val apkInspector = ApkInspector()
  private val unityDetector = UnityBuildDetector()
  private val elfAnalyzer = ElfAnalyzer()
  private val ilSpyAdapter = IlSpyAdapter()
  private val cpp2IlAdapter = Cpp2IlAdapter()
  private val unityPyAdapter = UnityPyAdapter()
  private val assetParser = UnityAssetParser()
  private val textureEngine = TextureEngine()
  private val audioEngine = AudioEngine()
  private val modelEngine = ModelEngine()
  private val sceneEngine = SceneEngine()
  private val dependencyEngine = DependencyEngine()
  private val projectRebuilder = ProjectRebuilder()
  private val exportEngine = ExportEngine()

  suspend fun importFromSafUris(
    uris: List<Uri>,
    bufferKb: Int,
    token: JobControlToken,
    onProgress: suspend (Int, String) -> Unit,
  ): ImportOutcome {
    require(uris.isNotEmpty()) { "No input files selected." }
    val primaryUri = uris.first()
    val displayName = workspaceManager.resolveDisplayName(primaryUri)
    val declaredSize = workspaceManager.resolveSize(primaryUri)
    if (declaredSize > 0) workspaceManager.ensureSufficientStorage(declaredSize * 2)

    val projectId = UUID.randomUUID().toString().take(12)
    val workspace = workspaceManager.getOrCreateWorkspace(projectId)
    val rawDir = File(workspace.sourceDir, "raw").apply { mkdirs() }
    val extractedDir = File(workspace.sourceDir, "extracted").apply { mkdirs() }

    val sourceType = detectSourceType(displayName, uris.size)
    val bufBytes = (bufferKb * 1024).coerceIn(65_536, 1024 * 1024)
    val buf = ByteArray(bufBytes)

    onProgress(10, "Streaming $displayName into isolated workspace...")
    val copiedFiles = mutableListOf<File>()
    for ((idx, uri) in uris.withIndex()) {
      token.checkPoint()
      val name = workspaceManager.resolveDisplayName(uri)
      val outFile = PathTraversalGuard.resolveSafeTarget(rawDir, name)
      context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { fos ->
          var read = input.read(buf)
          while (read > 0) {
            token.checkPoint()
            fos.write(buf, 0, read)
            read = input.read(buf)
          }
        }
      } ?: error("Unable to open SAF input stream for $name")
      copiedFiles.add(outFile)
      onProgress(20 + ((idx + 1) * 20) / uris.size, "Copied $name")
    }

    val primaryFile = copiedFiles.first()
    val sha256 = IntegrityVerifier.sha256(primaryFile, bufBytes)
    val duplicate = repository.findProjectBySha256(sha256)
    if (duplicate != null) {
      workspaceManager.deleteProjectWorkspace(projectId)
      logger.warn("Importer", "Duplicate SHA-256 detected (${sha256.take(12)}...); linking existing project ${duplicate.displayName}")
      val existingReport =
        duplicate.detectionReportJson?.let {
          runCatching { json.decodeFromString<UnityDetectionReport>(it) }.getOrNull()
        } ?: unityDetector.detect(File(duplicate.workspacePath, "source/extracted"))
      return ImportOutcome(duplicate, isDuplicate = true, detection = existingReport)
    }

    onProgress(50, "Inspecting archive safely (Zip-Slip protected)...")
    var packageName: String? = null
    if (uris.size > 1 && copiedFiles.all { it.extension.equals("apk", ignoreCase = true) }) {
      for (apk in copiedFiles) {
        val summary = apkInspector.inspectAndExtractRelevant(apk, ImportSourceType.APK, extractedDir, bufBytes, token, onProgress)
        if (packageName == null) packageName = summary.detectedPackageName
      }
    } else {
      val summary = apkInspector.inspectAndExtractRelevant(primaryFile, sourceType, extractedDir, bufBytes, token, onProgress)
      packageName = summary.detectedPackageName
    }

    onProgress(80, "Detecting Unity build signature & scripting backend...")
    val detection = unityDetector.detect(extractedDir)
    val now = System.currentTimeMillis()
    val project =
      ProjectEntity(
        id = projectId,
        displayName = primaryFile.nameWithoutExtension,
        packageName = packageName,
        sourceType = sourceType,
        sourceFileName = primaryFile.name,
        sourcePath = primaryFile.absolutePath,
        sourceSizeBytes = copiedFiles.sumOf { it.length() },
        sourceSha256 = sha256,
        workspacePath = workspace.rootDir.absolutePath,
        unityVersion = detection.unityVersion,
        unityVersionEvidence = detection.unityVersionEvidence,
        scriptingBackend = detection.scriptingBackend,
        architecturesJson = json.encodeToString(detection.architectures),
        detectionReportJson = json.encodeToString(detection),
        createdAtMs = now,
        updatedAtMs = now,
      )
    repository.upsertProject(project)
    onProgress(100, "Imported ${project.displayName}")
    return ImportOutcome(project, isDuplicate = false, detection = detection)
  }

  suspend fun importDirectoryTree(
    treeUri: Uri,
    token: JobControlToken,
    onProgress: suspend (Int, String) -> Unit,
  ): ImportOutcome {
    val docTree = DocumentFile.fromTreeUri(context, treeUri)
      ?: error("Unable to resolve selected SAF directory.")
    val projectId = UUID.randomUUID().toString().take(12)
    val workspace = workspaceManager.getOrCreateWorkspace(projectId)
    val extractedDir = File(workspace.sourceDir, "extracted").apply { mkdirs() }

    val copiedPaths = mutableListOf<String>()
    suspend fun copyTree(dir: DocumentFile, relPrefix: String) {
      for (child in dir.listFiles()) {
        token.checkPoint()
        val name = child.name ?: continue
        val rel = if (relPrefix.isEmpty()) name else "$relPrefix/$name"
        if (child.isDirectory) {
          copyTree(child, rel)
        } else if (child.isFile && apkInspector.isUnityRelevantPath(rel)) {
          val dest = PathTraversalGuard.resolveSafeTarget(extractedDir, rel)
          dest.parentFile?.mkdirs()
          context.contentResolver.openInputStream(child.uri)?.use { input ->
            FileOutputStream(dest).use { fos -> input.copyTo(fos, 65_536) }
          }
          copiedPaths.add(rel)
          onProgress(55, "Copied $rel")
        }
      }
    }

    onProgress(15, "Scanning directory ${docTree.name ?: "GameFolder"}...")
    copyTree(docTree, "")
    val combinedHashInput = copiedPaths.sorted().joinToString("|").ifBlank { docTree.uri.toString() }
    val sha256 = IntegrityVerifier.sha256(combinedHashInput.toByteArray())
    val detection = unityDetector.detect(extractedDir)
    val now = System.currentTimeMillis()
    val project =
      ProjectEntity(
        id = projectId,
        displayName = docTree.name ?: "UnityDirectory",
        packageName = null,
        sourceType = ImportSourceType.DIRECTORY,
        sourceFileName = docTree.name ?: "directory",
        sourcePath = treeUri.toString(),
        sourceSizeBytes = workspaceManager.directorySize(extractedDir),
        sourceSha256 = sha256,
        workspacePath = workspace.rootDir.absolutePath,
        unityVersion = detection.unityVersion,
        unityVersionEvidence = detection.unityVersionEvidence,
        scriptingBackend = detection.scriptingBackend,
        architecturesJson = json.encodeToString(detection.architectures),
        detectionReportJson = json.encodeToString(detection),
        createdAtMs = now,
        updatedAtMs = now,
      )
    repository.upsertProject(project)
    onProgress(100, "Directory imported (${copiedPaths.size} Unity files)")
    return ImportOutcome(project, isDuplicate = false, detection = detection)
  }

  suspend fun runFullCodeAnalysis(
    project: ProjectEntity,
    token: JobControlToken,
    onProgress: suspend (Int, String) -> Unit,
  ): Pair<List<ElfAnalysisReport>, Il2CppPipelineReport?> {
    val workspace = workspaceManager.getOrCreateWorkspace(project.id)
    val extractedDir = File(workspace.sourceDir, "extracted")
    val detection = unityDetector.detect(extractedDir)
    val symbols = mutableListOf<CodeSymbolEntity>()

    onProgress(15, "Inspecting native ELF libraries...")
    val elfReports = mutableListOf<ElfAnalysisReport>()
    for (relSo in detection.nativeLibraries.take(16)) {
      token.checkPoint()
      val soFile = File(extractedDir, relSo)
      if (soFile.exists()) {
        elfReports.add(elfAnalyzer.analyze(soFile))
      }
    }

    // Mono analysis
    onProgress(40, "Inspecting Mono managed assemblies...")
    for (relDll in detection.managedAssemblies) {
      token.checkPoint()
      val dllFile = File(extractedDir, relDll)
      if (dllFile.exists()) {
        val monoReport = ilSpyAdapter.inspectManagedAssembly(dllFile)
        for (t in monoReport.types) {
          symbols.add(
            CodeSymbolEntity(
              id = UUID.randomUUID().toString(),
              projectId = project.id,
              assemblyName = dllFile.name,
              namespaceName = t.namespaceName.ifBlank { "<global>" },
              typeName = t.typeName,
              memberSignature = "${t.methods.size} methods, ${t.fields.size} fields",
              symbolKind = "MonoClass",
              rvaAddress = t.methods.firstOrNull()?.rvaHex,
              sourcePreview = t.reconstructedCSharp,
              provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
            ),
          )
        }
      }
    }

    // IL2CPP analysis
    onProgress(70, "Parsing IL2CPP global-metadata.dat & ELF symbols...")
    var il2cppReport: Il2CppPipelineReport? = null
    val metaRel = detection.metadataFiles.firstOrNull()
    if (metaRel != null) {
      val metaFile = File(extractedDir, metaRel)
      val il2cppSo = detection.nativeLibraries.firstOrNull { it.endsWith("libil2cpp.so") }?.let { File(extractedDir, it) }
      val report = cpp2IlAdapter.runPipeline(metaFile, il2cppSo)
      il2cppReport = report
      File(workspace.inspectionDir, "il2cpp_report.json").writeText(json.encodeToString(report))
      cpp2IlAdapter.exportGhidraSymbolJson(report, File(workspace.inspectionDir, "ghidra_script.json"))

      for (cls in report.dummyClasses) {
        symbols.add(
          CodeSymbolEntity(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            assemblyName = "IL2CPP_Dummy",
            namespaceName = cls.namespaceName.ifBlank { "<global>" },
            typeName = cls.className,
            memberSignature = "${cls.methodNames.size} methods, ${cls.fieldNames.size} fields",
            symbolKind = "Il2CppDummyType",
            rvaAddress = cls.tokenHex,
            sourcePreview = cls.generatedDummyCs,
            provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
          ),
        )
      }
    }

    repository.replaceSymbolsForProject(project.id, symbols)
    File(workspace.inspectionDir, "elf_reports.json").writeText(json.encodeToString(elfReports))
    onProgress(100, "Code analysis complete (${symbols.size} types recovered)")
    return elfReports to il2cppReport
  }

  suspend fun runAssetAndSceneExtraction(
    project: ProjectEntity,
    token: JobControlToken,
    onProgress: suspend (Int, String) -> Unit,
  ): Pair<List<AssetEntryEntity>, SceneGraphSummary> {
    val workspace = workspaceManager.getOrCreateWorkspace(project.id)
    val extractedDir = File(workspace.sourceDir, "extracted")
    val outAssetsDir = workspace.extractedAssetsDir
    val detection = unityDetector.detect(extractedDir)

    val parsedObjects = mutableListOf<ParsedUnityObject>()
    val assetEntities = mutableListOf<AssetEntryEntity>()

    onProgress(20, "Scanning Unity AssetBundles & SerializedFiles...")
    val containers = (detection.assetFiles + detection.assetBundles + detection.levelFiles).distinct()
    for ((idx, relPath) in containers.withIndex()) {
      token.checkPoint()
      val file = File(extractedDir, relPath)
      if (!file.exists()) continue
      val inspection = unityPyAdapter.inspectAssetContainer(file, relPath)
      inspection.serializedSummary?.objects?.forEach { obj ->
        parsedObjects.add(obj)
        val extractedFile = tryExtractPayload(file, obj, outAssetsDir)
        assetEntities.add(
          AssetEntryEntity(
            id = "${project.id}_${obj.sourceFile.hashCode()}_${obj.pathId}",
            projectId = project.id,
            pathId = obj.pathId,
            name = obj.name,
            originalPath = relPath,
            kind = obj.assetKind,
            unityTypeName = obj.unityTypeName,
            sourceArchivePath = relPath,
            byteOffset = obj.byteOffset,
            byteSize = obj.byteSize,
            provenance = if (extractedFile != null) DataProvenance.ORIGINAL_RECOVERED else DataProvenance.DECOMPILED_RECONSTRUCTED,
            extractedFilePath = extractedFile?.absolutePath,
            previewMetadataJson = "{\"classId\":${obj.classId},\"offset\":${obj.byteOffset}}",
            extractionStatus = if (extractedFile != null) "Extracted" else "Indexed (Serialized)",
          ),
        )
      }
      inspection.bundleSummary?.let { bundle ->
        bundle.directoryEntries.forEachIndexed { bIdx, entryName ->
          assetEntities.add(
            AssetEntryEntity(
              id = "${project.id}_bundle_${relPath.hashCode()}_$bIdx",
              projectId = project.id,
              pathId = bIdx.toLong(),
              name = entryName,
              originalPath = "$relPath/$entryName",
              kind = AssetKind.OTHER,
              unityTypeName = "UnityFS Entry (${bundle.unityRevision})",
              sourceArchivePath = relPath,
              byteOffset = 0L,
              byteSize = bundle.bundleSize,
              provenance = DataProvenance.ORIGINAL_RECOVERED,
              extractedFilePath = null,
              previewMetadataJson = "{\"formatVersion\":${bundle.formatVersion}}",
              extractionStatus = "Bundle Indexed",
            ),
          )
        }
      }
      if (containers.isNotEmpty()) {
        onProgress(20 + ((idx + 1) * 50) / containers.size, "Parsed $relPath")
      }
    }

    // Also index StreamingAssets direct media/config files
    for (relStream in detection.streamingAssets) {
      token.checkPoint()
      val srcFile = File(extractedDir, relStream)
      if (!srcFile.exists()) continue
      val destFile = PathTraversalGuard.resolveSafeTarget(outAssetsDir, "StreamingAssets/${srcFile.name}")
      destFile.parentFile?.mkdirs()
      srcFile.copyTo(destFile, overwrite = true)
      val kind = classifyExtension(srcFile.extension)
      assetEntities.add(
        AssetEntryEntity(
          id = "${project.id}_stream_${relStream.hashCode()}",
          projectId = project.id,
          pathId = relStream.hashCode().toLong(),
          name = srcFile.name,
          originalPath = relStream,
          kind = kind,
          unityTypeName = "StreamingAsset (${srcFile.extension.ifBlank { "bin" }})",
          sourceArchivePath = relStream,
          byteOffset = 0L,
          byteSize = srcFile.length(),
          provenance = DataProvenance.ORIGINAL_RECOVERED,
          extractedFilePath = destFile.absolutePath,
          previewMetadataJson = null,
          extractionStatus = "Extracted",
        ),
      )
    }

    onProgress(80, "Reconstructing scene hierarchy & dependency graph...")
    val sceneGraph = sceneEngine.buildSceneSummary(parsedObjects)
    File(workspace.inspectionDir, "scene_graph.json").writeText(json.encodeToString(sceneGraph))

    val explicitRefs = parsedObjects.associate { obj ->
      "${project.id}_${obj.sourceFile.hashCode()}_${obj.pathId}" to obj.referencedPathIds
    }
    val depReport = dependencyEngine.buildGraph(project.id, assetEntities, explicitRefs)
    repository.replaceAssetsForProject(project.id, assetEntities)
    repository.replaceEdgesForProject(project.id, depReport.edges)

    onProgress(100, "Indexed ${assetEntities.size} assets across ${containers.size} containers")
    return assetEntities to sceneGraph
  }

  private fun tryExtractPayload(containerFile: File, obj: ParsedUnityObject, outAssetsDir: File): File? {
    if (obj.byteOffset < 0 || obj.byteSize <= 0 || obj.byteOffset + obj.byteSize > containerFile.length()) {
      return null
    }
    val safeName = FormatUtils.sanitizeFileName(obj.name)
    return when (obj.assetKind) {
      AssetKind.AUDIO -> {
        if (obj.byteSize > 32L * 1024 * 1024) return null
        val slice = ByteArray(obj.byteSize.toInt())
        containerFile.inputStream().use { input ->
          input.skip(obj.byteOffset)
          input.read(slice)
        }
        val res = audioEngine.inspectAndExportAudioPayload(safeName, slice, File(outAssetsDir, "Audio"))
        res.exportedPath?.let { File(it) }
      }
      AssetKind.TEXT_ASSET,
      AssetKind.SHADER -> {
        if (obj.byteSize > 2L * 1024 * 1024) return null
        val slice = ByteArray(obj.byteSize.toInt())
        containerFile.inputStream().use { input ->
          input.skip(obj.byteOffset)
          input.read(slice)
        }
        val dir = File(outAssetsDir, if (obj.assetKind == AssetKind.SHADER) "Shaders" else "TextAssets").apply { mkdirs() }
        val ext = if (obj.assetKind == AssetKind.SHADER) "shader" else "txt"
        File(dir, "$safeName.$ext").apply { writeBytes(slice) }
      }
      else -> null
    }
  }

  fun exportAssetToRequestedFormat(
    asset: AssetEntryEntity,
    project: ProjectEntity,
    format: String,
  ): Result<File> {
    val workspace = workspaceManager.getOrCreateWorkspace(project.id)
    val exportDir = File(workspace.exportsDir, "single_assets").apply { mkdirs() }
    val sourceFile = asset.extractedFilePath?.let { File(it) }

    return when (asset.kind) {
      AssetKind.TEXTURE -> {
        val targetFmt =
          when (format.uppercase()) {
            "JPG" -> TextureExportFormat.JPG
            "TGA" -> TextureExportFormat.TGA
            else -> TextureExportFormat.PNG
          }
        if (sourceFile != null && sourceFile.exists()) {
          val res = textureEngine.transcodeExistingImage(sourceFile, exportDir, targetFmt)
          if (res.success && res.exportedPath != null) Result.success(File(res.exportedPath))
          else Result.failure(IllegalStateException(res.note))
        } else {
          Result.failure(IllegalStateException("Texture payload is stored in compressed/platform-encoded chunk; direct pixel extraction unavailable for this entry."))
        }
      }
      AssetKind.MESH -> {
        // Export indexed mesh geometry only if real vertices exist; otherwise export honest metadata stub
        val objFile = File(exportDir, "${FormatUtils.sanitizeFileName(asset.name)}.txt")
        objFile.writeText("Mesh Asset: ${asset.name}\nPathID: ${asset.pathId}\nSource: ${asset.sourceArchivePath}\nByteSize: ${asset.byteSize}")
        Result.success(objFile)
      }
      else -> {
        if (sourceFile != null && sourceFile.exists()) {
          val out = File(exportDir, sourceFile.name)
          sourceFile.copyTo(out, overwrite = true)
          Result.success(out)
        } else {
          Result.failure(IllegalStateException("Raw payload not yet extracted to disk for ${asset.name}."))
        }
      }
    }
  }

  suspend fun rebuildUnityProject(project: ProjectEntity): ReconstructionReport {
    val workspace = workspaceManager.getOrCreateWorkspace(project.id)
    val assets = repository.getAssetsForProject(project.id)
    val symbols = repository.getSymbolsForProject(project.id)
    val edges = repository.getEdgesForProject(project.id)
    val logLines = logger.logs.value.filter { it.projectId == project.id || it.projectId == null }.map { it.formatted() }
    return projectRebuilder.rebuildProject(
      project = project,
      assets = assets,
      symbols = symbols,
      edges = edges,
      logs = logLines,
      outputRoot = workspace.reconstructedProjectDir,
    )
  }

  suspend fun exportProjectArchive(
    project: ProjectEntity,
    token: JobControlToken,
    onProgress: suspend (Int, String) -> Unit,
  ): ZipExportResult {
    val workspace = workspaceManager.getOrCreateWorkspace(project.id)
    if (!File(workspace.reconstructedProjectDir, "Packages/manifest.json").exists()) {
      rebuildUnityProject(project)
    }
    val zipFile = File(workspace.exportsDir, "${FormatUtils.sanitizeFileName(project.displayName)}_RecoveredProject.zip")
    return exportEngine.exportDirectoryToZip(workspace.reconstructedProjectDir, zipFile, token, onProgress)
  }

  fun loadDetectionReport(project: ProjectEntity): UnityDetectionReport? =
    project.detectionReportJson?.let { runCatching { json.decodeFromString<UnityDetectionReport>(it) }.getOrNull() }

  fun loadElfReports(project: ProjectEntity): List<ElfAnalysisReport> {
    val f = File(workspaceManager.getOrCreateWorkspace(project.id).inspectionDir, "elf_reports.json")
    if (!f.exists()) return emptyList()
    return runCatching { json.decodeFromString<List<ElfAnalysisReport>>(f.readText()) }.getOrDefault(emptyList())
  }

  fun loadIl2CppReport(project: ProjectEntity): Il2CppPipelineReport? {
    val f = File(workspaceManager.getOrCreateWorkspace(project.id).inspectionDir, "il2cpp_report.json")
    if (!f.exists()) return null
    return runCatching { json.decodeFromString<Il2CppPipelineReport>(f.readText()) }.getOrNull()
  }

  fun loadSceneGraph(project: ProjectEntity): SceneGraphSummary? {
    val f = File(workspaceManager.getOrCreateWorkspace(project.id).inspectionDir, "scene_graph.json")
    if (!f.exists()) return null
    return runCatching { json.decodeFromString<SceneGraphSummary>(f.readText()) }.getOrNull()
  }

  fun exportMeshSampleToObjAndGltf(mesh: MeshDataModel, outputDir: File): Pair<File?, File?> {
    val obj = modelEngine.exportToObj(mesh, outputDir).exportedPath?.let { File(it) }
    val gltf = modelEngine.exportToGltf(mesh, outputDir).exportedPath?.let { File(it) }
    return obj to gltf
  }

  private fun detectSourceType(fileName: String, fileCount: Int): ImportSourceType {
    val lower = fileName.lowercase()
    return when {
      fileCount > 1 && lower.endsWith(".apk") -> ImportSourceType.APKS
      lower.endsWith(".apk") -> ImportSourceType.APK
      lower.endsWith(".xapk") -> ImportSourceType.XAPK
      lower.endsWith(".apks") -> ImportSourceType.APKS
      lower.endsWith(".zip") -> ImportSourceType.ZIP
      else -> ImportSourceType.INDIVIDUAL_FILES
    }
  }

  private fun classifyExtension(ext: String): AssetKind =
    when (ext.lowercase()) {
      "png", "jpg", "jpeg", "tga", "webp" -> AssetKind.TEXTURE
      "ogg", "wav", "mp3", "fsb" -> AssetKind.AUDIO
      "obj", "gltf", "glb" -> AssetKind.MESH
      "mat" -> AssetKind.MATERIAL
      "anim" -> AssetKind.ANIMATION
      "unity" -> AssetKind.SCENE
      "prefab" -> AssetKind.PREFAB
      "shader" -> AssetKind.SHADER
      "txt", "json", "xml", "bytes" -> AssetKind.TEXT_ASSET
      else -> AssetKind.OTHER
    }
}
