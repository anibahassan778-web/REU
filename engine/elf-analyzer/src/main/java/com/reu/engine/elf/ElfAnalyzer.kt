package com.reu.engine.elf

import com.reu.core.common.FileBinarySource
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class ElfSectionInfo(
  val name: String,
  val type: Long,
  val addressHex: String,
  val fileOffset: Long,
  val sizeBytes: Long,
)

@Serializable
data class ElfAnalysisReport(
  val fileName: String,
  val valid: Boolean,
  val is64Bit: Boolean,
  val endianness: String,
  val machineArch: String,
  val entryPointHex: String,
  val sections: List<ElfSectionInfo>,
  val exportedSymbols: List<String>,
  val il2cppSymbolsFound: List<String>,
  val errorMessage: String? = null,
)

class ElfAnalyzer {
  fun analyze(elfFile: File, maxSymbols: Int = 2048): ElfAnalysisReport {
    if (!elfFile.exists() || elfFile.length() < 52) {
      return ElfAnalysisReport(
        fileName = elfFile.name,
        valid = false,
        is64Bit = false,
        endianness = "Unknown",
        machineArch = "Unknown",
        entryPointHex = "0x0",
        sections = emptyList(),
        exportedSymbols = emptyList(),
        il2cppSymbolsFound = emptyList(),
        errorMessage = "File missing or smaller than minimum ELF header (52 bytes).",
      )
    }

    FileBinarySource(elfFile).use { src ->
      val ident = src.readBytes(16)
      if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) {
        return ElfAnalysisReport(
          fileName = elfFile.name,
          valid = false,
          is64Bit = false,
          endianness = "Unknown",
          machineArch = "Unknown",
          entryPointHex = "0x0",
          sections = emptyList(),
          exportedSymbols = emptyList(),
          il2cppSymbolsFound = emptyList(),
          errorMessage = "Invalid ELF magic bytes.",
        )
      }

      val elfClass = ident[4].toInt() and 0xFF
      val elfData = ident[5].toInt() and 0xFF
      val is64 = elfClass == 2
      val isLe = elfData == 1

      src.position = 18
      val machine = if (isLe) src.readU16LE() else src.readU16BE()
      src.position = 24
      val entry = if (is64) (if (isLe) src.readU64LE() else src.readU64BE()) else (if (isLe) src.readU32LE() else src.readU32BE())
      if (is64) {
        src.position = 40
      } else {
        src.position = 32
      }
      val shoff = if (is64) (if (isLe) src.readU64LE() else src.readU64BE()) else (if (isLe) src.readU32LE() else src.readU32BE())
      src.position = if (is64) 58L else 46L
      val shentsize = if (isLe) src.readU16LE() else src.readU16BE()
      val shnum = if (isLe) src.readU16LE() else src.readU16BE()
      val shstrndx = if (isLe) src.readU16LE() else src.readU16BE()

      val rawSections = mutableListOf<RawSection>()
      if (shoff > 0 && shnum in 1..4096 && shentsize >= 40 && shoff + shentsize.toLong() * shnum <= src.length) {
        for (i in 0 until shnum) {
          src.position = shoff + i.toLong() * shentsize
          val nameIdx = if (isLe) src.readU32LE() else src.readU32BE()
          val type = if (isLe) src.readU32LE() else src.readU32BE()
          if (is64) {
            src.readU64LE() // flags
            val addr = if (isLe) src.readU64LE() else src.readU64BE()
            val off = if (isLe) src.readU64LE() else src.readU64BE()
            val sz = if (isLe) src.readU64LE() else src.readU64BE()
            val link = if (isLe) src.readU32LE().toInt() else src.readU32BE().toInt()
            rawSections.add(RawSection(nameIdx.toInt(), type, addr, off, sz, link))
          } else {
            src.readU32LE() // flags
            val addr = if (isLe) src.readU32LE() else src.readU32BE()
            val off = if (isLe) src.readU32LE() else src.readU32BE()
            val sz = if (isLe) src.readU32LE() else src.readU32BE()
            val link = if (isLe) src.readU32LE().toInt() else src.readU32BE().toInt()
            rawSections.add(RawSection(nameIdx.toInt(), type, addr, off, sz, link))
          }
        }
      }

      val shStrTab =
        if (shstrndx in rawSections.indices && rawSections[shstrndx].size in 1..(4L * 1024 * 1024) &&
          rawSections[shstrndx].offset + rawSections[shstrndx].size <= src.length
        ) {
          src.position = rawSections[shstrndx].offset
          src.readBytes(rawSections[shstrndx].size.toInt())
        } else {
          ByteArray(0)
        }

      val sections =
        rawSections.map { rs ->
          ElfSectionInfo(
            name = readCString(shStrTab, rs.nameIdx),
            type = rs.type,
            addressHex = "0x" + rs.addr.toULong().toString(16),
            fileOffset = rs.offset,
            sizeBytes = rs.size,
          )
        }

      val exported = linkedSetOf<String>()
      for (rs in rawSections) {
        if ((rs.type == 11L || rs.type == 2L) && rs.link in rawSections.indices && exported.size < maxSymbols) {
          val strSec = rawSections[rs.link]
          if (rs.size in 1..(8L * 1024 * 1024) && strSec.size in 1..(8L * 1024 * 1024) &&
            rs.offset + rs.size <= src.length && strSec.offset + strSec.size <= src.length
          ) {
            src.position = strSec.offset
            val strBytes = src.readBytes(strSec.size.toInt())
            val symEntSize = if (is64) 24 else 16
            var off = 0L
            while (off + symEntSize <= rs.size && exported.size < maxSymbols) {
              src.position = rs.offset + off
              val stName = (if (isLe) src.readU32LE() else src.readU32BE()).toInt()
              val stShndx =
                if (is64) {
                  src.position = rs.offset + off + 6
                  if (isLe) src.readU16LE() else src.readU16BE()
                } else {
                  src.position = rs.offset + off + 14
                  if (isLe) src.readU16LE() else src.readU16BE()
                }
              if (stName > 0 && stShndx != 0) {
                val symName = readCString(strBytes, stName)
                if (symName.isNotBlank()) exported.add(symName)
              }
              off += symEntSize
            }
          }
        }
      }

      val il2cppHits = exported.filter { it.contains("il2cpp", ignoreCase = true) || it.startsWith("JNI_") }

      return ElfAnalysisReport(
        fileName = elfFile.name,
        valid = true,
        is64Bit = is64,
        endianness = if (isLe) "Little-Endian" else "Big-Endian",
        machineArch = mapMachine(machine),
        entryPointHex = "0x" + entry.toULong().toString(16),
        sections = sections,
        exportedSymbols = exported.toList(),
        il2cppSymbolsFound = il2cppHits,
      )
    }
  }

  private fun mapMachine(machine: Int): String =
    when (machine) {
      0x03 -> "x86"
      0x28 -> "armeabi-v7a (ARM32)"
      0x3E -> "x86_64"
      0xB7 -> "arm64-v8a (AArch64)"
      0xF3 -> "RISC-V"
      else -> "Machine(0x${machine.toString(16)})"
    }

  private fun readCString(buf: ByteArray, offset: Int): String {
    if (offset < 0 || offset >= buf.size) return ""
    var end = offset
    while (end < buf.size && buf[end] != 0.toByte() && end - offset < 512) end++
    return String(buf, offset, end - offset, Charsets.UTF_8)
  }

  private data class RawSection(
    val nameIdx: Int,
    val type: Long,
    val addr: Long,
    val offset: Long,
    val size: Long,
    val link: Int,
  )
}
