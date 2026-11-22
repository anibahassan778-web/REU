package com.reu.tests.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.reu.core.common.AssetKind
import com.reu.core.common.DataProvenance
import com.reu.core.common.ImportSourceType
import com.reu.core.common.UnityScriptingBackend
import com.reu.core.database.AssetEntryEntity
import com.reu.core.database.CodeSymbolEntity
import com.reu.core.database.ProjectEntity
import com.reu.core.database.ReuDatabase
import com.reu.core.database.ReuRepository
import com.reu.core.logging.ReuLogger
import com.reu.core.security.IntegrityVerifier
import com.reu.core.jobs.JobControlToken
import com.reu.engine.apk.ApkInspector
import com.reu.engine.asset.UnityAssetParser
import com.reu.engine.audio.AudioEngine
import com.reu.engine.detector.UnityBuildDetector
import com.reu.engine.export.ExportEngine
import com.reu.engine.model.ModelEngine
import com.reu.engine.reconstruction.ProjectRebuilder
import com.reu.engine.reconstruction.RebuildMode
import com.reu.engine.scene.SceneEngine
import com.reu.engine.texture.TextureEngine
import com.reu.features.pipeline.UnityFullAssetExtractor
import com.reu.integrations.tools.ToolModuleManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReuPipelineAndRebuilderTests {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var db: ReuDatabase
  private lateinit var repository: ReuRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, ReuDatabase::class.java).allowMainThreadQueries().build()
    repository = ReuRepository(db)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `end to end APK inspection blocks zip slip detects Unity IL2CPP rebuilds project and exports zip`() = runTest {
    val apkFile = File(tempFolder.root, "sample_game.apk")
    ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
      zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zos.write("<manifest package=\"com.studio.unitytest\"/>".toByteArray())
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("lib/arm64-v8a/libil2cpp.so"))
      zos.write(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("assets/bin/Data/Managed/Metadata/global-metadata.dat"))
      zos.write(byteArrayOf(0xAF.toByte(), 0x1B, 0xB1.toByte(), 0xFA.toByte(), 29, 0, 0, 0))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("assets/bin/Data/globalgamemanagers"))
      zos.write("UnityHeader\u00002022.3.20f1\u0000".toByteArray())
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("../../evil_traversal.txt"))
      zos.write("blocked".toByteArray())
      zos.closeEntry()
    }

    val extractedDir = tempFolder.newFolder("extracted")
    val summary = ApkInspector().inspectAndExtractRelevant(apkFile, ImportSourceType.APK, extractedDir)
    assertEquals("com.studio.unitytest", summary.detectedPackageName)
    assertTrue(summary.warnings.any { it.contains("Blocked unsafe archive entry") })
    assertFalse(File(tempFolder.root, "evil_traversal.txt").exists())

    val detection = UnityBuildDetector().detect(extractedDir)
    assertTrue(detection.isUnityBuild)
    assertEquals("2022.3.20f1", detection.unityVersion)
    assertEquals(UnityScriptingBackend.IL2CPP, detection.scriptingBackend)
    assertEquals(listOf("arm64-v8a"), detection.architectures)

    val project =
      ProjectEntity(
        id = "proj1",
        displayName = "SampleUnity",
        packageName = summary.detectedPackageName,
        sourceType = ImportSourceType.APK,
        sourceFileName = apkFile.name,
        sourcePath = apkFile.absolutePath,
        sourceSizeBytes = apkFile.length(),
        sourceSha256 = summary.sha256,
        workspacePath = tempFolder.root.absolutePath,
        unityVersion = detection.unityVersion,
        unityVersionEvidence = detection.unityVersionEvidence,
        scriptingBackend = detection.scriptingBackend,
        architecturesJson = "[\"arm64-v8a\"]",
        detectionReportJson = null,
        createdAtMs = 1L,
        updatedAtMs = 1L,
      )
    val dummySymbol =
      CodeSymbolEntity(
        id = "sym1",
        projectId = "proj1",
        assemblyName = "IL2CPP_Dummy",
        namespaceName = "Game",
        typeName = "Player",
        memberSignature = "1 method",
        symbolKind = "Il2CppDummyType",
        rvaAddress = "0x02000001",
        sourcePreview = "public class Player {}",
        provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
      )
    val sampleAssetFile = File(tempFolder.root, "tex.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    val assetEntry =
      AssetEntryEntity(
        id = "a1",
        projectId = "proj1",
        pathId = 100L,
        name = "tex",
        originalPath = "assets/tex.png",
        kind = AssetKind.TEXTURE,
        unityTypeName = "Texture2D",
        sourceArchivePath = "resources.assets",
        byteOffset = 0,
        byteSize = 4,
        provenance = DataProvenance.ORIGINAL_RECOVERED,
        extractedFilePath = sampleAssetFile.absolutePath,
        previewMetadataJson = null,
        extractionStatus = "Extracted",
      )

    val rebuildDir = tempFolder.newFolder("RecoveredProject")
    val rebuildReport =
      ProjectRebuilder().rebuildProject(
        project = project,
        assets = listOf(assetEntry),
        symbols = listOf(dummySymbol),
        edges = emptyList(),
        logs = listOf("[INFO] Rebuilt test project"),
        outputRoot = rebuildDir,
      )
    assertTrue(rebuildReport.validationPassed)
    assertTrue(File(rebuildDir, "Assets/Scripts/IL2CPP_Dummy/Player.cs").exists())
    assertTrue(File(rebuildDir, "Assets/Textures/tex.png").exists())
    assertTrue(File(rebuildDir, "Packages/manifest.json").exists())
    assertTrue(File(rebuildDir, "ProjectSettings/ProjectVersion.txt").readText().contains("2022.3.20f1"))
    assertTrue(File(rebuildDir, "REU_Report/analysis.json").exists())
    assertTrue(File(rebuildDir, "REU_Report/compatibility.json").exists())

    val exportZip = File(tempFolder.root, "out.zip")
    val zipRes = ExportEngine().exportDirectoryToZip(rebuildDir, exportZip)
    assertTrue(zipRes.success && zipRes.fileCount >= 8 && zipRes.sha256.length == 64)
  }

  @Test
  fun `tool module manager enforces SHA-256 verification and rejects executable DEX packages`() = runTest {
    val toolsDir = tempFolder.newFolder("tools")
    val manager = ToolModuleManager(repository, toolsDir, ReuLogger())
    manager.ensureSeedCatalog()
    val all = repository.getAllTools()
    assertTrue(all.size >= 6)

    // Install optional schema pack
    val installRes = manager.installOrUpdateOptionalModule("reu-typetree-pack-2022")
    assertTrue(installRes.isSuccess)
    assertTrue(repository.getTool("reu-typetree-pack-2022")?.isInstalled == true)

    // Reject offline zip containing .dex (Play integrity policy)
    val badZip = File(tempFolder.root, "malicious_mod.zip")
    ZipOutputStream(FileOutputStream(badZip)).use { zos ->
      zos.putNextEntry(ZipEntry("classes.dex"))
      zos.write(byteArrayOf(1, 2, 3))
      zos.closeEntry()
    }
    val rejected = manager.installOfflinePackage(badZip, null)
    assertTrue(rejected.isFailure)

    // Reject SHA-256 mismatch, then accept valid declarative offline module
    val goodZip = File(tempFolder.root, "custom_pack.zip")
    ZipOutputStream(FileOutputStream(goodZip)).use { zos ->
      zos.putNextEntry(ZipEntry("manifest.json"))
      zos.write(
        """
        {
          "id": "custom-shader-pack",
          "name": "Custom Shader Pack",
          "version": "1.0.1",
          "description": "Verified offline JSON pack",
          "supportedArchs": "universal-data",
          "minAndroidApi": 24,
          "dependencies": [],
          "sha256Checksum": "self",
          "licenseName": "MIT",
          "downloadUrl": "offline://custom_pack.zip",
          "compatibilityNote": "Safe data pack"
        }
        """.trimIndent().toByteArray(),
      )
      zos.closeEntry()
    }
    val mismatch = manager.installOfflinePackage(goodZip, "0000")
    assertTrue(mismatch.isFailure)

    val realSha = IntegrityVerifier.sha256(goodZip)
    val ok = manager.installOfflinePackage(goodZip, realSha)
    assertTrue(ok.isSuccess)
    assertEquals("custom-shader-pack", ok.getOrNull()?.id)
  }

  @Test
  fun `code-only rebuild omits assets and excludes included library assemblies`() {
    val project =
      ProjectEntity(
        id = "proj_code_only",
        displayName = "PongGame",
        packageName = "com.example.pong",
        sourceType = ImportSourceType.APK,
        sourceFileName = "pong.apk",
        sourcePath = "/tmp/pong.apk",
        sourceSizeBytes = 1024L,
        sourceSha256 = "0".repeat(64),
        workspacePath = tempFolder.root.absolutePath,
        unityVersion = "2021.3.8f1",
        unityVersionEvidence = "globalgamemanagers",
        scriptingBackend = UnityScriptingBackend.MONO,
        architecturesJson = "[\"armeabi-v7a\"]",
        detectionReportJson = null,
        createdAtMs = 1L,
        updatedAtMs = 1L,
      )
    val gameSymbol =
      CodeSymbolEntity(
        id = "sym_game",
        projectId = "proj_code_only",
        assemblyName = "Assembly-CSharp.dll",
        namespaceName = "<global>",
        typeName = "Paddle",
        memberSignature = "1 method",
        symbolKind = "MonoClass",
        rvaAddress = "0x2050",
        sourcePreview = "public class Paddle : MonoBehaviour {}",
        provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
      )
    val libSymbol =
      CodeSymbolEntity(
        id = "sym_lib",
        projectId = "proj_code_only",
        assemblyName = "Newtonsoft.Json.dll",
        namespaceName = "Newtonsoft.Json",
        typeName = "JsonConvert",
        memberSignature = "1 method",
        symbolKind = "MonoClass",
        rvaAddress = "0x3000",
        sourcePreview = "public static class JsonConvert {}",
        provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
      )
    val audioFile = File(tempFolder.root, "hit.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    val audioAsset =
      AssetEntryEntity(
        id = "aud1",
        projectId = "proj_code_only",
        pathId = 1L,
        name = "hit",
        originalPath = "resources.assets",
        kind = AssetKind.AUDIO,
        unityTypeName = "AudioClip",
        sourceArchivePath = "resources.assets",
        byteOffset = 0L,
        byteSize = 4L,
        provenance = DataProvenance.ORIGINAL_RECOVERED,
        extractedFilePath = audioFile.absolutePath,
        previewMetadataJson = null,
        extractionStatus = "Extracted",
      )

    val outDir = tempFolder.newFolder("CodeOnlyOut")
    val report =
      ProjectRebuilder().rebuildProject(
        project = project,
        assets = listOf(audioAsset),
        symbols = listOf(gameSymbol, libSymbol),
        edges = emptyList(),
        logs = emptyList(),
        outputRoot = outDir,
        mode = RebuildMode.CODE_ONLY,
        referencedLibraries = listOf("Mono.Security.dll"),
      )

    assertEquals(RebuildMode.CODE_ONLY, report.mode)
    assertEquals(1, report.scriptsWritten)
    assertEquals(0, report.assetsCopied)
    assertEquals(2, report.excludedLibraryCount)
    assertTrue(File(outDir, "Scripts/Assembly-CSharp.dll/Paddle.cs").exists())
    assertFalse(File(outDir, "Scripts/Newtonsoft.Json.dll").exists())
    assertFalse(File(outDir, "Assets").exists())
    assertFalse(File(outDir, "Packages").exists())
    assertFalse(File(outDir, "ProjectSettings").exists())
  }

  @Test
  fun `full project extraction unpacks UnityFS bundle and exports textures sprites audio fonts shaders and scenes`() = runTest {
    val serializedLevel0 = buildSyntheticSerializedLevel0()
    val unityFsBytes = buildUnityFsBundle("level0", serializedLevel0)

    val apkFile = File(tempFolder.root, "synthetic_full.apk")
    ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
      zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zos.write("<manifest package=\"com.studio.fulltest\"/>".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("assets/bin/Data/data.unity3d"))
      zos.write(unityFsBytes)
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("assets/bin/Data/Managed/Assembly-CSharp.dll"))
      zos.write(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("assets/bin/Data/Managed/Newtonsoft.Json.dll"))
      zos.write(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))
      zos.closeEntry()
    }

    val extractedDir = tempFolder.newFolder("extracted_full")
    val apkSummary = ApkInspector().inspectAndExtractRelevant(apkFile, ImportSourceType.APK, extractedDir)
    val detection = UnityBuildDetector().detect(extractedDir)
    assertEquals(UnityScriptingBackend.MONO, detection.scriptingBackend)
    assertTrue(detection.assetBundles.any { it.endsWith("data.unity3d") })

    val project =
      ProjectEntity(
        id = "proj_full",
        displayName = "SyntheticFullGame",
        packageName = apkSummary.detectedPackageName,
        sourceType = ImportSourceType.APK,
        sourceFileName = apkFile.name,
        sourcePath = apkFile.absolutePath,
        sourceSizeBytes = apkFile.length(),
        sourceSha256 = apkSummary.sha256,
        workspacePath = tempFolder.root.absolutePath,
        unityVersion = detection.unityVersion ?: "2021.3.8f1",
        unityVersionEvidence = detection.unityVersionEvidence ?: "Bundle",
        scriptingBackend = UnityScriptingBackend.MONO,
        architecturesJson = "[\"armeabi-v7a\"]",
        detectionReportJson = null,
        createdAtMs = 1L,
        updatedAtMs = 1L,
      )

    val outAssetsDir = tempFolder.newFolder("extracted_assets")
    val outcome =
      UnityFullAssetExtractor(
        assetParser = UnityAssetParser(),
        textureEngine = TextureEngine(),
        audioEngine = AudioEngine(),
        modelEngine = ModelEngine(),
        sceneEngine = SceneEngine(),
      ).extractAll(
        projectId = project.id,
        extractedDir = extractedDir,
        outAssetsDir = outAssetsDir,
        bundlePaths = detection.assetBundles,
        rawAssetAndLevelPaths = detection.assetFiles,
        streamingAssetPaths = detection.streamingAssets,
        token = JobControlToken("full_test"),
        onProgress = { _, _ -> },
      )

    val gameSym =
      CodeSymbolEntity(
        id = "sym_1",
        projectId = project.id,
        assemblyName = "Assembly-CSharp.dll",
        namespaceName = "<global>",
        typeName = "Paddle",
        memberSignature = "1 method",
        symbolKind = "MonoClass",
        rvaAddress = "0x2000",
        sourcePreview = "public class Paddle {}",
        provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
      )
    val libSym =
      CodeSymbolEntity(
        id = "sym_2",
        projectId = project.id,
        assemblyName = "Newtonsoft.Json.dll",
        namespaceName = "Newtonsoft.Json",
        typeName = "JsonConvert",
        memberSignature = "1 method",
        symbolKind = "MonoClass",
        rvaAddress = "0x3000",
        sourcePreview = "public class JsonConvert {}",
        provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
      )

    val rebuiltDir = tempFolder.newFolder("rebuilt_full")
    val report =
      ProjectRebuilder().rebuildProject(
        project = project,
        assets = outcome.assetEntities,
        symbols = listOf(gameSym, libSym),
        edges = emptyList(),
        logs = listOf("[INFO] Full extraction verified"),
        outputRoot = rebuiltDir,
        mode = RebuildMode.FULL_PROJECT,
        referencedLibraries = listOf("UnityEngine.CoreModule.dll"),
      )

    assertTrue(report.validationPassed)
    assertEquals(0, report.missingOrPlaceholderCount)
    assertTrue(File(rebuiltDir, "Assets/Scripts/Assembly-CSharp.dll/Paddle.cs").exists())
    assertFalse(File(rebuiltDir, "Assets/Scripts/Newtonsoft.Json.dll").exists())

    val texPng = File(rebuiltDir, "Assets/Textures/Logo.png")
    assertTrue("Missing Logo.png", texPng.exists())
    assertTrue(texPng.readBytes().copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))

    val spritePng = File(rebuiltDir, "Assets/Sprites/LogoSprite.png")
    assertTrue("Missing LogoSprite.png", spritePng.exists())
    assertTrue(spritePng.readBytes().copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))

    val audioWav = File(rebuiltDir, "Assets/Audio/Ding.wav")
    assertTrue("Missing Ding.wav", audioWav.exists())
    assertEquals("RIFF", String(audioWav.readBytes().copyOfRange(0, 4)))

    val shaderFile = File(rebuiltDir, "Assets/Shaders/Sprites_Default.shader")
    assertTrue("Missing Sprites_Default.shader", shaderFile.exists())
    assertTrue(shaderFile.readText().contains("Shader \"Sprites/Default\""))

    val fontFile = File(rebuiltDir, "Assets/Fonts/PixelFont.ttf")
    assertTrue("Missing PixelFont.ttf", fontFile.exists())
    assertTrue(fontFile.readBytes().copyOfRange(0, 4).contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)))

    val sceneFile = File(rebuiltDir, "Assets/Scenes/level0.unity")
    assertTrue("Missing level0.unity", sceneFile.exists())
    assertTrue(sceneFile.readText().contains("Player"))
  }

  private fun writeUnityStringLE(out: ByteArrayOutputStream, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
    out.write(bytes)
    while (out.size() % 4 != 0) out.write(0)
  }

  private fun buildSyntheticSerializedLevel0(): ByteArray {
    fun leInt(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun leLong(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    fun leFloat(v: Float) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    // Build 7 object blobs
    val texBytes =
      ByteArrayOutputStream().apply {
        writeUnityStringLE(this, "Logo")
        write(leInt(0)) // m_ForcedFallbackFormat
        write(ByteArray(4)) // downscale + flags
        write(leInt(2)) // width
        write(leInt(2)) // height
        write(leInt(16)) // completeImageSize
        write(leInt(0)) // mipsStripped
        write(leInt(4)) // RGBA32
        write(leInt(1)) // mipCount
        write(ByteArray(16)) // flags/priority/imageCount/dim
        write(ByteArray(24)) // GLTextureSettings
        write(leInt(0)); write(leInt(1)) // lightmap/colorSpace
        write(leInt(0)) // m_PlatformBlob len
        write(leInt(16)) // image data len
        write(ByteArray(16) { -1 }) // 2x2 white RGBA
        write(leLong(0)); write(leInt(0)); writeUnityStringLE(this, "")
      }.toByteArray()

    val spriteBytes =
      ByteArrayOutputStream().apply {
        writeUnityStringLE(this, "LogoSprite")
        write(leFloat(0f)); write(leFloat(0f)); write(leFloat(2f)); write(leFloat(2f)) // rect
        write(ByteArray(24)) // offset + border
        write(leFloat(100f)) // pixelsToUnits
        write(ByteArray(16)) // pivot/extrude/isPolygon
        write(ByteArray(24)) // RenderDataKey
        write(leInt(0)) // tags count
        write(leInt(0)); write(leLong(0)) // SpriteAtlas PPtr
        write(leInt(0)); write(leLong(1)) // texture PPtr -> pathId=1 ("Logo")
      }.toByteArray()

    val audioBytes =
      ByteArrayOutputStream().apply {
        writeUnityStringLE(this, "Ding")
        write(leInt(0)) // loadType
        write(leInt(1)) // channels
        write(leInt(22050)) // frequency
        write(leInt(16)) // bitsPerSample
        write(leFloat(0.01f)) // length
        write(ByteArray(12)) // tracker/subsound/flags
        write(leInt(0)) // compressionFormat=0 (PCM)
        writeUnityStringLE(this, "") // streamSource
        write(leLong(0)) // offset
        write(leLong(0)) // streamSize=0 -> inline!
        write(leInt(8)) // inline len
        write(ByteArray(8) { 0x10 }) // PCM16 samples
      }.toByteArray()

    val goBytes =
      ByteArrayOutputStream().apply {
        write(leInt(1)) // 1 component
        write(leInt(0)); write(leLong(5)) // Transform pid=5
        write(leInt(0)) // layer
        writeUnityStringLE(this, "Player")
        write(ByteArray(4)) // tag + active
      }.toByteArray()

    val transformBytes =
      ByteArrayOutputStream().apply {
        write(leInt(0)); write(leLong(4)) // GameObject pid=4
        write(leFloat(0f)); write(leFloat(0f)); write(leFloat(0f)); write(leFloat(1f)) // rot
        write(leFloat(1f)); write(leFloat(2f)); write(leFloat(0f)) // pos
        write(leFloat(1f)); write(leFloat(1f)); write(leFloat(1f)) // scale
        write(leInt(0)) // children
        write(leInt(0)); write(leLong(0)) // father
        write(leInt(0)) // rootOrder
      }.toByteArray()

    val shaderBytes =
      ByteArrayOutputStream().apply {
        write(leInt(1)) // m_Props count placeholder
        writeUnityStringLE(this, "_MainTex")
        writeUnityStringLE(this, "Sprites/Default")
      }.toByteArray()

    val fontPayload = ByteArray(512).apply {
      this[0] = 0x00; this[1] = 0x01; this[2] = 0x00; this[3] = 0x00
      // numTables=8 (0x0008), searchRange=128 (0x0080), entrySelector=3 (0x0003), rangeShift=0 (0x0000)
      this[4] = 0x00; this[5] = 0x08
      this[6] = 0x00; this[7] = 0x80.toByte()
      this[8] = 0x00; this[9] = 0x03
      this[10] = 0x00; this[11] = 0x00
    }
    val fontBytes =
      ByteArrayOutputStream().apply {
        writeUnityStringLE(this, "PixelFont")
        write(leInt(0)) // dummy int
        write(leInt(fontPayload.size))
        write(fontPayload)
      }.toByteArray()

    val objects = listOf(
      Triple(1L, 0, texBytes),
      Triple(2L, 1, spriteBytes),
      Triple(3L, 2, audioBytes),
      Triple(4L, 3, goBytes),
      Triple(5L, 4, transformBytes),
      Triple(6L, 5, shaderBytes),
      Triple(7L, 6, fontBytes),
    )
    val classIds = listOf(28, 213, 83, 1, 4, 48, 128)

    val meta = ByteArrayOutputStream().apply {
      write("2021.3.8f1\u0000".toByteArray())
      write(leInt(13)) // Android platform
      write(0) // enableTypeTree=false
      write(leInt(classIds.size))
      for (cid in classIds) {
        write(leInt(cid))
        write(0) // isStripped
        write(ByteArray(2)) // scriptTypeIndex
        write(ByteArray(16)) // typeHash
      }
      write(leInt(objects.size))
      var curOff = 0L
      for ((pid, tidx, blob) in objects) {
        while (size() % 4 != 0) write(0)
        write(leLong(pid))
        write(leLong(curOff))
        write(leInt(blob.size))
        write(leInt(tidx))
        curOff += (blob.size + 7) and 7.inv()
      }
      write(leInt(0)); write(leInt(0)) // scripts & external refs
    }.toByteArray()

    val dataStart = ((48 + meta.size + 15) and 15.inv()).toLong()
    val dataOut = ByteArrayOutputStream()
    for ((_, _, blob) in objects) {
      dataOut.write(blob)
      while (dataOut.size() % 8 != 0) dataOut.write(0)
    }
    val totalSize = dataStart + dataOut.size()

    return ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.BIG_ENDIAN).apply {
      putInt(meta.size)
      putInt(totalSize.toInt())
      putInt(22) // v22
      putInt(dataStart.toInt())
      put(0); put(0); put(0); put(0) // littleEndian
      putInt(meta.size)
      putLong(totalSize)
      putLong(dataStart)
      putLong(0L)
      put(meta)
      position(dataStart.toInt())
      put(dataOut.toByteArray())
    }.array()
  }

  private fun buildUnityFsBundle(nodeName: String, payload: ByteArray): ByteArray {
    val dirInfo = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN).apply {
      position(16)
      putInt(1)
      putInt(payload.size)
      putInt(payload.size)
      putShort(0) // uncompressed
      putInt(1)
      putLong(0L)
      putLong(payload.size.toLong())
      putInt(4) // SerializedFile flag
      put("$nodeName\u0000".toByteArray())
    }
    val dirBytes = dirInfo.array().copyOf(dirInfo.position())
    return ByteBuffer.allocate(256 + dirBytes.size + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
      put("UnityFS\u0000".toByteArray())
      putInt(7)
      put("5.x.x\u00002021.3.8f1\u0000".toByteArray())
      putLong(0L)
      putInt(dirBytes.size)
      putInt(dirBytes.size)
      putInt(0x200)
      while (position() % 16 != 0) put(0)
      put(dirBytes)
      while (position() % 16 != 0) put(0)
      put(payload)
    }.let { buf -> buf.array().copyOf(buf.position()) }
  }
}
