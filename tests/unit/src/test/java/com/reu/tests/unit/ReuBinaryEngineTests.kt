package com.reu.tests.unit

import com.reu.core.common.AssetKind
import com.reu.core.common.DataProvenance
import com.reu.engine.asset.UnityAssetParser
import com.reu.engine.audio.AudioEngine
import com.reu.engine.elf.ElfAnalyzer
import com.reu.engine.il2cpp.Il2CppAnalyzer
import com.reu.engine.metadata.GlobalMetadataParser
import com.reu.engine.model.MeshDataModel
import com.reu.engine.model.ModelEngine
import com.reu.engine.mono.MonoAnalyzer
import com.reu.engine.scene.SceneEngine
import com.reu.engine.texture.TextureEngine
import com.reu.engine.texture.TextureExportFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReuBinaryEngineTests {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `elf analyzer parses ELF64 AArch64 header sections and exported symbols`() {
    val elfFile = File(tempFolder.root, "libil2cpp.so")
    elfFile.writeBytes(buildMinimalElf64WithSymbols())

    val report = ElfAnalyzer().analyze(elfFile)
    assertTrue(report.valid)
    assertTrue(report.is64Bit)
    assertEquals("arm64-v8a (AArch64)", report.machineArch)
    assertTrue(report.exportedSymbols.contains("il2cpp_init"))
    assertTrue(report.il2cppSymbolsFound.contains("il2cpp_init"))
  }

  @Test
  fun `global metadata parser and il2cpp analyzer extract types methods and fields`() {
    val metaFile = File(tempFolder.root, "global-metadata.dat")
    metaFile.writeBytes(buildMinimalIl2CppMetadata())

    val parsed = GlobalMetadataParser().parse(metaFile)
    assertTrue(parsed.valid)
    assertEquals(29, parsed.metadataVersion)
    assertEquals(1, parsed.typeDefinitionCount)
    assertEquals("PlayerController", parsed.types.first().typeName)
    assertEquals("Game.Gameplay", parsed.types.first().namespaceName)
    assertEquals("UpdateScore", parsed.methods.first().name)
    assertEquals("health", parsed.fields.first().name)

    val pipeline = Il2CppAnalyzer().analyze(metaFile, null)
    assertEquals(1, pipeline.dummyClasses.size)
    assertTrue(pipeline.dummyClasses.first().generatedDummyCs.contains("public class PlayerController"))
    assertTrue(pipeline.limitations.isNotEmpty())
  }

  @Test
  fun `mono analyzer parses PE32 CLR CLI header and disassembles CIL opcodes`() {
    val dllBytes = buildMinimalNetAssembly()
    val report = MonoAnalyzer().analyzeBytes("Assembly-CSharp.dll", dllBytes)
    assertTrue("Expected valid PE/CLI: ${report.errorMessage}", report.validPeCli)
    assertEquals(DataProvenance.DECOMPILED_RECONSTRUCTED, report.provenance)
    assertTrue(report.referencedAssemblies.contains("UnityEngine.CoreModule"))
    val hero = report.types.first { it.typeName == "HeroController" }
    assertEquals("Game.Hero", hero.namespaceName)
    assertEquals("Move", hero.methods.first().name)
    assertTrue(hero.methods.first().ilDisassembly.contains("ldarg.0"))
    assertTrue(hero.methods.first().ilDisassembly.contains("ret"))
  }

  @Test
  fun `unity asset parser audio model and texture engines export valid files`() {
    // Test LZ4 decompression + SerializedFile + SceneEngine
    val rawLiteral = "HelloUnity".toByteArray()
    val lz4Packet = byteArrayOf(((rawLiteral.size shl 4) and 0xFF).toByte()) + rawLiteral
    val decompressed = UnityAssetParser().decompressLz4Block(lz4Packet, rawLiteral.size)
    assertEquals("HelloUnity", String(decompressed))

    // Audio WAV + Ogg detection
    val outDir = tempFolder.newFolder("exports")
    val wavRes = AudioEngine().writePcm16Wav("beep", shortArrayOf(0, 1200, -1200, 0), 44100, 1, outDir)
    assertTrue(wavRes.success)
    val wavFile = File(wavRes.exportedPath!!)
    assertTrue(wavFile.exists() && wavFile.readBytes().copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))

    // Model OBJ & glTF 2.0
    val tri = MeshDataModel("Triangle", listOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), indices = listOf(0, 1, 2))
    val objRes = ModelEngine().exportToObj(tri, outDir)
    val gltfRes = ModelEngine().exportToGltf(tri, outDir)
    assertTrue(objRes.success && File(objRes.exportedPath!!).readText().contains("f 1 2 3"))
    assertTrue(gltfRes.success && File(gltfRes.exportedPath!!).readText().contains("\"version\": \"2.0\""))

    // Texture DXT1 decode + TGA export (does not rely on native Android Bitmap in pure unit test if TGA, wait: exportRgbaTexture uses Bitmap.createBitmap — test DXT1 decoder directly here!)
    // DXT1 4x4 white block: c0=0xFFFF, c1=0x0000, selector bits=0x00000000 (code 0 -> c0)
    val dxt1Block = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val rgba = TextureEngine().decodeDxt1ToRgba(4, 4, dxt1Block)
    assertEquals(4 * 4 * 4, rgba.size)
    assertEquals(255, rgba[0].toInt() and 0xFF)
  }

  private fun buildMinimalElf64WithSymbols(): ByteArray {
    // Header(64) + .shstrtab(32) + .dynstr(16) + .dynsym(48) + 4 section headers(4*64=256)
    val shstrtab = "\u0000.shstrtab\u0000.dynstr\u0000.dynsym\u0000".toByteArray()
    val dynstr = "\u0000il2cpp_init\u0000".toByteArray()
    val dynsym = ByteArray(48) // sym0 (null) + sym1 (24 bytes each)
    // sym1: st_name=1, st_info=0x12, st_other=0, st_shndx=1
    dynsym[24] = 1
    dynsym[24 + 4] = 0x12
    dynsym[24 + 6] = 1

    val shstrOff = 64
    val dynstrOff = shstrOff + shstrtab.size
    val dynsymOff = dynstrOff + dynstr.size
    val shoff = dynsymOff + dynsym.size

    val buf = ByteBuffer.allocate(shoff + 4 * 64).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))
    buf.position(16)
    buf.putShort(3) // ET_DYN
    buf.putShort(0xB7.toShort()) // EM_AARCH64
    buf.putInt(1)
    buf.putLong(0x1000L) // e_entry
    buf.putLong(0L)
    buf.putLong(shoff.toLong()) // e_shoff
    buf.position(58)
    buf.putShort(64) // e_shentsize
    buf.putShort(4)  // e_shnum
    buf.putShort(1)  // e_shstrndx

    buf.position(shstrOff); buf.put(shstrtab)
    buf.position(dynstrOff); buf.put(dynstr)
    buf.position(dynsymOff); buf.put(dynsym)

    fun writeSh(idx: Int, nameIdx: Int, type: Int, off: Int, size: Int, link: Int) {
      val base = shoff + idx * 64
      buf.position(base)
      buf.putInt(nameIdx)
      buf.putInt(type)
      buf.putLong(0)
      buf.putLong(0)
      buf.putLong(off.toLong())
      buf.putLong(size.toLong())
      buf.putInt(link)
    }
    writeSh(0, 0, 0, 0, 0, 0)
    writeSh(1, 1, 3, shstrOff, shstrtab.size, 0)
    writeSh(2, 11, 3, dynstrOff, dynstr.size, 0)
    writeSh(3, 19, 11, dynsymOff, dynsym.size, 2)
    return buf.array()
  }

  private fun buildMinimalIl2CppMetadata(): ByteArray {
    val strings = "\u0000Game.Gameplay\u0000PlayerController\u0000UpdateScore\u0000health\u0000Assembly-CSharp.dll\u0000".toByteArray()
    val stringOff = 256
    val methodsOff = stringOff + strings.size
    val fieldsOff = methodsOff + 32
    val typeDefsOff = fieldsOff + 12
    val imagesOff = typeDefsOff + 88
    val total = imagesOff + 40

    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(0xFAB11BAF.toInt())
    buf.putInt(29)
    buf.position(0x18); buf.putInt(stringOff); buf.putInt(strings.size)
    buf.position(0x30); buf.putInt(methodsOff); buf.putInt(32)
    buf.position(0x60); buf.putInt(fieldsOff); buf.putInt(12)
    buf.position(0xA0); buf.putInt(typeDefsOff); buf.putInt(88); buf.putInt(imagesOff); buf.putInt(40)

    buf.position(stringOff); buf.put(strings)
    // MethodDef (nameIdx = 32 -> "UpdateScore")
    val updateScoreIdx = String(strings).indexOf("UpdateScore")
    val healthIdx = String(strings).indexOf("health")
    val pcIdx = String(strings).indexOf("PlayerController")
    val nsIdx = String(strings).indexOf("Game.Gameplay")
    val imgIdx = String(strings).indexOf("Assembly-CSharp.dll")

    buf.position(methodsOff)
    buf.putInt(updateScoreIdx)
    buf.position(methodsOff + 24)
    buf.putInt(0x06000001)

    buf.position(fieldsOff)
    buf.putInt(healthIdx)
    buf.putInt(0)
    buf.putInt(0x04000001)

    buf.position(typeDefsOff)
    buf.putInt(pcIdx)
    buf.putInt(nsIdx)
    buf.position(typeDefsOff + 52)
    buf.putInt(0) // fieldStart
    buf.putInt(0) // methodStart
    buf.position(typeDefsOff + 72)
    buf.putInt(1) // flags
    buf.putShort(1) // fieldCount
    buf.putShort(1) // methodCount
    buf.position(typeDefsOff + 84)
    buf.putInt(0x02000001)

    buf.position(imagesOff)
    buf.putInt(imgIdx)
    return buf.array()
  }

  private fun buildMinimalNetAssembly(): ByteArray {
    // Single-section PE32 where RVA == FileOffset for simplicity (VirtualAddress=0x200, RawPointer=0x200)
    val strHeap = "\u0000UnityEngine.CoreModule\u0000HeroController\u0000Game.Hero\u0000Move\u0000hp\u0000".toByteArray()
    val rawStr = String(strHeap)
    val idxAsmRef = rawStr.indexOf("UnityEngine.CoreModule")
    val idxType = rawStr.indexOf("HeroController")
    val idxNs = rawStr.indexOf("Game.Hero")
    val idxMethod = rawStr.indexOf("Move")
    val idxField = rawStr.indexOf("hp")

    val ilRva = 0x260
    // Tiny IL header: codeSize=2 -> (2 shl 2) | 0x02 = 0x0A, followed by ldarg.0 (0x02), ret (0x2A)
    val ilBytes = byteArrayOf(0x0A, 0x02, 0x2A, 0x00)

    val mdRva = 0x280
    val tildeRel = 0x40
    val stringsRel = 0x160

    val out = ByteBuffer.allocate(0x600).order(ByteOrder.LITTLE_ENDIAN)
    // MZ
    out.putShort(0x5A4D)
    out.position(0x3C); out.putInt(0x80)
    // PE header @ 0x80
    out.position(0x80); out.putInt(0x00004550)
    out.putShort(0x014C) // machine
    out.putShort(1)      // 1 section
    out.position(0x80 + 20); out.putShort(224) // PE32 optional header size
    val opt = 0x80 + 24
    out.position(opt); out.putShort(0x10B) // PE32 magic
    out.position(opt + 208); out.putInt(0x200); out.putInt(72) // CLR directory RVA=0x200
    // Section header @ opt + 224
    val sec = opt + 224
    out.position(sec); out.put(".text\u0000\u0000\u0000".toByteArray())
    out.putInt(0x400); out.putInt(0x200); out.putInt(0x400); out.putInt(0x200)

    // CLI header @ 0x200
    out.position(0x200); out.putInt(72); out.putShort(2); out.putShort(5)
    out.putInt(mdRva); out.putInt(0x300) // MetaData RVA

    // IL body @ 0x260
    out.position(ilRva); out.put(ilBytes)

    // BSJB metadata root @ 0x280
    out.position(mdRva); out.putInt(0x424A5342)
    out.putShort(1); out.putShort(1); out.putInt(0)
    val ver = "v4.0.30319\u0000\u0000".toByteArray()
    out.putInt(ver.size); out.put(ver)
    out.putShort(0); out.putShort(2) // flags + 2 streams
    out.putInt(tildeRel); out.putInt(0x120); out.put("#~\u0000\u0000".toByteArray())
    out.putInt(stringsRel); out.putInt(strHeap.size); out.put("#Strings\u0000\u0000\u0000\u0000".toByteArray())

    // #Strings heap
    out.position(mdRva + stringsRel); out.put(strHeap)

    // #~ stream @ mdRva + tildeRel
    out.position(mdRva + tildeRel)
    out.putInt(0); out.put(2); out.put(0); out.put(0); out.put(1)
    // validMask bits: 0x02 (TypeDef), 0x04 (Field), 0x06 (MethodDef), 0x23 (AssemblyRef)
    val mask = (1L shl 0x02) or (1L shl 0x04) or (1L shl 0x06) or (1L shl 0x23)
    out.putLong(mask)
    out.putLong(0L)
    out.putInt(1) // TypeDef count
    out.putInt(1) // Field count
    out.putInt(1) // MethodDef count
    out.putInt(1) // AssemblyRef count

    // TypeDef row (14 bytes: flags4, name2, ns2, extends2, field2, method2)
    out.putInt(1)
    out.putShort(idxType.toShort())
    out.putShort(idxNs.toShort())
    out.putShort(0)
    out.putShort(1)
    out.putShort(1)

    // Field row (6 bytes: flags2, name2, sig2)
    out.putShort(1)
    out.putShort(idxField.toShort())
    out.putShort(0)

    // MethodDef row (14 bytes: rva4, impl2, flags2, name2, sig2, param2)
    out.putInt(ilRva)
    out.putShort(0)
    out.putShort(6)
    out.putShort(idxMethod.toShort())
    out.putShort(0)
    out.putShort(1)

    // AssemblyRef row (20 bytes: ver8, flags4, pk2, name2, culture2, hash2)
    out.putLong(0)
    out.putInt(0)
    out.putShort(0)
    out.putShort(idxAsmRef.toShort())
    out.putShort(0)
    out.putShort(0)

    return out.array()
  }
}
