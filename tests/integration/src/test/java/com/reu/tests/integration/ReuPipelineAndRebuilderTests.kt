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
import com.reu.engine.apk.ApkInspector
import com.reu.engine.detector.UnityBuildDetector
import com.reu.engine.export.ExportEngine
import com.reu.engine.reconstruction.ProjectRebuilder
import com.reu.integrations.tools.ToolModuleManager
import java.io.File
import java.io.FileOutputStream
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
}
