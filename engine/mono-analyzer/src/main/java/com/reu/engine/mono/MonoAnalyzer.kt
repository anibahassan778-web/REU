package com.reu.engine.mono

import com.reu.core.common.BinarySource
import com.reu.core.common.ByteArrayBinarySource
import com.reu.core.common.DataProvenance
import com.reu.core.common.FileBinarySource
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
data class MonoTypeModel(
  val namespaceName: String,
  val typeName: String,
  val flags: Long,
  val methods: List<MonoMethodModel>,
  val fields: List<String>,
  val reconstructedCSharp: String,
  val warnings: List<String>,
)

@Serializable
data class MonoMethodModel(
  val name: String,
  val rvaHex: String,
  val ilDisassembly: String,
)

@Serializable
data class MonoAssemblyReport(
  val assemblyFileName: String,
  val validPeCli: Boolean,
  val runtimeVersion: String,
  val referencedAssemblies: List<String>,
  val types: List<MonoTypeModel>,
  val warnings: List<String>,
  val provenance: DataProvenance,
  val errorMessage: String? = null,
)

class MonoAnalyzer {
  fun analyzeAssembly(dllFile: File): MonoAssemblyReport {
    if (!dllFile.exists() || dllFile.length() < 128) {
      return invalid(dllFile.name, "DLL missing or too small to be a valid .NET PE/CLI assembly.")
    }
    FileBinarySource(dllFile).use { src -> return parsePeCli(dllFile.name, src) }
  }

  fun analyzeBytes(fileName: String, bytes: ByteArray): MonoAssemblyReport {
    if (bytes.size < 128) return invalid(fileName, "Buffer too small for PE/CLI assembly.")
    ByteArrayBinarySource(bytes).use { src -> return parsePeCli(fileName, src) }
  }

  private fun parsePeCli(fileName: String, src: BinarySource): MonoAssemblyReport {
    val warnings = mutableListOf<String>()
    src.position = 0
    if (src.readU16LE() != 0x5A4D) {
      return invalid(fileName, "Missing MZ DOS header signature.")
    }
    src.position = 0x3C
    val peOffset = src.readU32LE()
    if (peOffset + 24 >= src.length) return invalid(fileName, "Invalid PE header offset.")
    src.position = peOffset
    if (src.readU32LE() != 0x00004550L) return invalid(fileName, "Missing PE\\0\\0 signature.")

    src.readU16LE() // machine
    val numSections = src.readU16LE()
    src.position = peOffset + 20
    val optHeaderSize = src.readU16LE()
    src.readU16LE() // characteristics

    val optStart = peOffset + 24
    src.position = optStart
    val magic = src.readU16LE()
    val isPe32Plus = magic == 0x20B
    val clrDirOffset = optStart + (if (isPe32Plus) 224L else 208L)
    if (clrDirOffset + 8 > src.length) return invalid(fileName, "PE optional header missing CLR directory.")

    src.position = clrDirOffset
    val clrRva = src.readU32LE()
    val clrSize = src.readU32LE()
    if (clrRva == 0L || clrSize < 72L) {
      return invalid(fileName, "Not a .NET managed assembly (CLI header RVA is zero).")
    }

    // Read PE section table
    val sections = mutableListOf<PeSection>()
    val secTableStart = optStart + optHeaderSize
    for (i in 0 until numSections.coerceAtMost(64)) {
      src.position = secTableStart + i * 40L
      src.readBytes(8) // name
      val vSize = src.readU32LE()
      val vAddr = src.readU32LE()
      val rawSize = src.readU32LE()
      val rawPtr = src.readU32LE()
      sections.add(PeSection(vAddr, vSize, rawPtr, rawSize))
    }

    val clrFileOffset = rvaToOffset(clrRva, sections)
      ?: return invalid(fileName, "Unable to resolve CLR header RVA 0x${clrRva.toString(16)}.")
    src.position = clrFileOffset + 8
    val metadataRva = src.readU32LE()
    val metadataSize = src.readU32LE()
    val mdOffset = rvaToOffset(metadataRva, sections)
      ?: return invalid(fileName, "Unable to resolve CLI metadata RVA 0x${metadataRva.toString(16)}.")

    src.position = mdOffset
    if (src.readU32LE() != 0x424A5342L) {
      return invalid(fileName, "Invalid CLR metadata root signature (expected BSJB).")
    }
    src.readU16LE(); src.readU16LE(); src.readU32LE()
    val verLen = src.readU32LE().toInt().coerceIn(0, 256)
    val verBytes = src.readBytes(verLen)
    val runtimeVersion = String(verBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
    src.align(4)
    src.readU16LE() // flags
    val streamCount = src.readU16LE()

    var stringsOffset = 0L; var stringsSize = 0L
    var tildeOffset = 0L; var tildeSize = 0L
    for (i in 0 until streamCount.coerceAtMost(16)) {
      val sOff = src.readU32LE()
      val sSize = src.readU32LE()
      val sName = src.readNullTerminatedAscii(32)
      src.align(4)
      when (sName) {
        "#Strings" -> { stringsOffset = mdOffset + sOff; stringsSize = sSize }
        "#~", "#-" -> { tildeOffset = mdOffset + sOff; tildeSize = sSize }
      }
    }

    val stringHeap =
      if (stringsOffset > 0 && stringsOffset + stringsSize <= src.length && stringsSize <= 16L * 1024 * 1024) {
        src.position = stringsOffset
        src.readBytes(stringsSize.toInt())
      } else {
        ByteArray(0)
      }

    val types = mutableListOf<MonoTypeModel>()
    val assemblyRefs = mutableListOf<String>()

    if (tildeOffset > 0 && tildeOffset + 24 <= src.length && tildeSize >= 24) {
      src.position = tildeOffset
      src.readU32LE() // reserved
      src.readU8(); src.readU8() // major, minor
      val heapSizes = src.readU8()
      src.readU8() // reserved2
      val validMask = src.readU64LE()
      src.readU64LE() // sortedMask

      val strIdxSize = if ((heapSizes and 0x01) != 0) 4 else 2
      val guidIdxSize = if ((heapSizes and 0x02) != 0) 4 else 2
      val blobIdxSize = if ((heapSizes and 0x04) != 0) 4 else 2

      val rowCounts = IntArray(64)
      for (bit in 0 until 64) {
        if (((validMask ushr bit) and 1L) != 0L) {
          rowCounts[bit] = src.readI32LE().coerceAtLeast(0)
        }
      }

      // Compute table offsets sequentially for tables 0x00..0x23
      var cursor = src.position
      val tableOffsets = LongArray(64) { -1L }
      for (t in 0..0x23) {
        if (rowCounts[t] > 0) {
          tableOffsets[t] = cursor
          val rowWidth =
            computeCliRowWidth(
              table = t,
              rowCounts = rowCounts,
              strIdxSize = strIdxSize,
              guidIdxSize = guidIdxSize,
              blobIdxSize = blobIdxSize,
            )
          if (rowWidth <= 0) {
            warnings.add("Unsupported CLI metadata table 0x${t.toString(16)}; truncated deep table scan.")
            break
          }
          cursor += rowWidth.toLong() * rowCounts[t]
        }
      }

      // Read AssemblyRef (0x23)
      if (tableOffsets[0x23] > 0) {
        val rowW = computeCliRowWidth(0x23, rowCounts, strIdxSize, guidIdxSize, blobIdxSize)
        val count = minOf(rowCounts[0x23], 128)
        for (r in 0 until count) {
          src.position = tableOffsets[0x23] + r.toLong() * rowW + 12 // 8 version + 4 flags
          src.position += blobIdxSize // publicKeyOrToken
          val nameIdx = readHeapIdx(src, strIdxSize)
          val refName = readHeapString(stringHeap, nameIdx)
          if (refName.isNotBlank()) assemblyRefs.add(refName)
        }
      }

      // Read TypeDef (0x02), Field (0x04), MethodDef (0x06)
      val typeCount = minOf(rowCounts[0x02], 500)
      val fieldCount = rowCounts[0x04]
      val methodCount = rowCounts[0x06]
      val typeRowW = computeCliRowWidth(0x02, rowCounts, strIdxSize, guidIdxSize, blobIdxSize)
      val fieldRowW = computeCliRowWidth(0x04, rowCounts, strIdxSize, guidIdxSize, blobIdxSize)
      val methodRowW = computeCliRowWidth(0x06, rowCounts, strIdxSize, guidIdxSize, blobIdxSize)

      data class RawTypeDef(val flags: Long, val name: String, val ns: String, val fieldStart: Int, val methodStart: Int)
      val rawTypes = mutableListOf<RawTypeDef>()
      if (tableOffsets[0x02] > 0 && typeRowW > 0) {
        val fieldIdxSize = if (fieldCount < 65536) 2 else 4
        val methodIdxSize = if (methodCount < 65536) 2 else 4
        val typeDefOrRefIdxSize = codedIdxSize(rowCounts, intArrayOf(0x02, 0x01, 0x1B), 2)
        for (i in 0 until typeCount) {
          src.position = tableOffsets[0x02] + i.toLong() * typeRowW
          val flags = src.readU32LE()
          val tName = readHeapString(stringHeap, readHeapIdx(src, strIdxSize))
          val tNs = readHeapString(stringHeap, readHeapIdx(src, strIdxSize))
          src.position += typeDefOrRefIdxSize // extends
          val fStart = readHeapIdx(src, fieldIdxSize)
          val mStart = readHeapIdx(src, methodIdxSize)
          rawTypes.add(RawTypeDef(flags, tName, tNs, fStart, mStart))
        }
      }

      for (i in rawTypes.indices) {
        val rt = rawTypes[i]
        if (rt.name == "<Module>" || rt.name.isBlank()) continue
        val nextField = if (i + 1 < rawTypes.size) rawTypes[i + 1].fieldStart else (fieldCount + 1)
        val nextMethod = if (i + 1 < rawTypes.size) rawTypes[i + 1].methodStart else (methodCount + 1)

        val fieldNames = mutableListOf<String>()
        if (tableOffsets[0x04] > 0 && fieldRowW > 0) {
          for (fIdx in rt.fieldStart until minOf(nextField, rt.fieldStart + 64)) {
            if (fIdx in 1..fieldCount) {
              src.position = tableOffsets[0x04] + (fIdx - 1).toLong() * fieldRowW + 2
              val fName = readHeapString(stringHeap, readHeapIdx(src, strIdxSize))
              if (fName.isNotBlank()) fieldNames.add(fName)
            }
          }
        }

        val methodModels = mutableListOf<MonoMethodModel>()
        if (tableOffsets[0x06] > 0 && methodRowW > 0) {
          for (mIdx in rt.methodStart until minOf(nextMethod, rt.methodStart + 64)) {
            if (mIdx in 1..methodCount) {
              src.position = tableOffsets[0x06] + (mIdx - 1).toLong() * methodRowW
              val rva = src.readU32LE()
              src.readU16LE() // implFlags
              src.readU16LE() // flags
              val mName = readHeapString(stringHeap, readHeapIdx(src, strIdxSize))
              val disasm = disassembleMethodIl(src, rva, sections)
              if (mName.isNotBlank()) {
                methodModels.add(MonoMethodModel(mName, "0x" + rva.toString(16), disasm))
              }
            }
          }
        }

        val csharp = buildReconstructedCSharp(fileName, rt.ns, rt.name, fieldNames, methodModels)
        types.add(
          MonoTypeModel(
            namespaceName = rt.ns,
            typeName = rt.name,
            flags = rt.flags,
            methods = methodModels,
            fields = fieldNames,
            reconstructedCSharp = csharp,
            warnings = listOf("Method bodies recovered as CIL disassembly / structural C# outline; verify control flow."),
          ),
        )
      }
    } else {
      warnings.add("CLR #~ compressed metadata stream not found.")
    }

    return MonoAssemblyReport(
      assemblyFileName = fileName,
      validPeCli = true,
      runtimeVersion = runtimeVersion.ifBlank { "CLR" },
      referencedAssemblies = assemblyRefs.distinct(),
      types = types,
      warnings = warnings,
      provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
    )
  }

  private fun disassembleMethodIl(src: BinarySource, rva: Long, sections: List<PeSection>): String {
    if (rva == 0L) return "// Abstract / runtime-provided method (RVA = 0x0)"
    val off = rvaToOffset(rva, sections) ?: return "// Unable to map RVA 0x${rva.toString(16)}"
    if (off >= src.length) return "// RVA outside file bounds"
    src.position = off
    val b0 = src.readU8()
    val codeSize: Int
    if ((b0 and 0x03) == 0x02) {
      // Tiny IL header
      codeSize = (b0 ushr 2) and 0x3F
    } else if ((b0 and 0x03) == 0x03 && off + 12 <= src.length) {
      // Fat IL header
      src.position = off + 4
      codeSize = src.readI32LE().coerceIn(0, 256)
      src.position = off + 12
    } else {
      return "// Unrecognized IL method header byte 0x${b0.toString(16)}"
    }

    val toRead = minOf(codeSize, 64)
    if (src.position + toRead > src.length) return "// Truncated IL stream"
    val il = src.readBytes(toRead)
    val sb = StringBuilder()
    var i = 0
    while (i < il.size) {
      val op = il[i].toInt() and 0xFF
      val (mnemonic, operandLen) = decodeCilOpcode(op)
      sb.append(String.format(java.util.Locale.US, "IL_%04x: %s", i, mnemonic))
      if (operandLen > 0 && i + operandLen < il.size) {
        sb.append(" 0x")
        for (k in operandLen downTo 1) {
          sb.append(String.format(java.util.Locale.US, "%02x", il[i + k]))
        }
      }
      sb.append('\n')
      i += 1 + operandLen
    }
    return sb.toString().trimEnd()
  }

  private fun decodeCilOpcode(op: Int): Pair<String, Int> =
    when (op) {
      0x00 -> "nop" to 0
      0x02 -> "ldarg.0" to 0
      0x03 -> "ldarg.1" to 0
      0x04 -> "ldarg.2" to 0
      0x06 -> "ldloc.0" to 0
      0x0A -> "stloc.0" to 0
      0x14 -> "ldnull" to 0
      0x16 -> "ldc.i4.0" to 0
      0x17 -> "ldc.i4.1" to 0
      0x1F -> "ldc.i4.s" to 1
      0x20 -> "ldc.i4" to 4
      0x28 -> "call" to 4
      0x2A -> "ret" to 0
      0x6F -> "callvirt" to 4
      0x72 -> "ldstr" to 4
      0x73 -> "newobj" to 4
      0x7B -> "ldfld" to 4
      0x7D -> "stfld" to 4
      else -> String.format(java.util.Locale.US, "op_0x%02x", op) to 0
    }

  private fun buildReconstructedCSharp(
    assemblyName: String,
    ns: String,
    typeName: String,
    fields: List<String>,
    methods: List<MonoMethodModel>,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("// [RECONSTRUCTED BY REU MonoAnalyzer from $assemblyName]")
    sb.appendLine("// Provenance: DECOMPILED_RECONSTRUCTED (Metadata + CIL disassembly)")
    if (ns.isNotBlank()) {
      sb.appendLine("namespace $ns")
      sb.appendLine("{")
    }
    val indent = if (ns.isNotBlank()) "    " else ""
    sb.appendLine("${indent}public partial class $typeName")
    sb.appendLine("${indent}{")
    for (f in fields) {
      sb.appendLine("${indent}    private object $f;")
    }
    for (m in methods) {
      sb.appendLine("${indent}    // RVA: ${m.rvaHex}")
      sb.appendLine("${indent}    public void ${m.name}()")
      sb.appendLine("${indent}    {")
      m.ilDisassembly.lines().take(12).forEach { line ->
        sb.appendLine("${indent}        // $line")
      }
      sb.appendLine("${indent}    }")
    }
    sb.appendLine("${indent}}")
    if (ns.isNotBlank()) sb.appendLine("}")
    return sb.toString()
  }

  private fun rvaToOffset(rva: Long, sections: List<PeSection>): Long? {
    for (s in sections) {
      val size = maxOf(s.virtualSize, s.rawSize)
      if (rva in s.virtualAddress until (s.virtualAddress + size)) {
        return s.rawPointer + (rva - s.virtualAddress)
      }
    }
    return null
  }

  private fun readHeapIdx(src: BinarySource, size: Int): Int =
    if (size == 4) src.readI32LE() else src.readU16LE()

  private fun readHeapString(heap: ByteArray, index: Int): String {
    if (index <= 0 || index >= heap.size) return ""
    var end = index
    while (end < heap.size && heap[end] != 0.toByte() && end - index < 256) end++
    return String(heap, index, end - index, StandardCharsets.UTF_8)
  }

  private fun codedIdxSize(rowCounts: IntArray, tables: IntArray, tagBits: Int): Int {
    val threshold = 1 shl (16 - tagBits)
    val maxRows = tables.maxOfOrNull { if (it in rowCounts.indices) rowCounts[it] else 0 } ?: 0
    return if (maxRows < threshold) 2 else 4
  }

  private fun computeCliRowWidth(
    table: Int,
    rowCounts: IntArray,
    strIdxSize: Int,
    guidIdxSize: Int,
    blobIdxSize: Int,
  ): Int {
    val typeDefOrRef = codedIdxSize(rowCounts, intArrayOf(0x02, 0x01, 0x1B), 2)
    val resolutionScope = codedIdxSize(rowCounts, intArrayOf(0x00, 0x1A, 0x23, 0x01), 2)
    val hasConstant = codedIdxSize(rowCounts, intArrayOf(0x04, 0x08, 0x17), 2)
    val hasCustomAttr = codedIdxSize(rowCounts, intArrayOf(0x06, 0x04, 0x01, 0x02, 0x08, 0x09, 0x0A, 0x00, 0x0E, 0x17, 0x14, 0x11, 0x1A, 0x1B, 0x20, 0x23, 0x26, 0x27, 0x28), 5)
    val memberRefParent = codedIdxSize(rowCounts, intArrayOf(0x02, 0x01, 0x1A, 0x06, 0x1B), 3)
    val typeOrMethodDef = codedIdxSize(rowCounts, intArrayOf(0x02, 0x06), 1)
    val customAttrType = codedIdxSize(rowCounts, intArrayOf(0x06, 0x0A), 3)
    val hasFieldMarshal = codedIdxSize(rowCounts, intArrayOf(0x04, 0x08), 1)
    val hasDeclSec = codedIdxSize(rowCounts, intArrayOf(0x02, 0x06, 0x20), 2)
    val hasSemantics = codedIdxSize(rowCounts, intArrayOf(0x14, 0x17), 1)
    val methodDefOrRef = codedIdxSize(rowCounts, intArrayOf(0x06, 0x0A), 1)
    val memberForwarded = codedIdxSize(rowCounts, intArrayOf(0x04, 0x06), 1)
    val impl = codedIdxSize(rowCounts, intArrayOf(0x26, 0x23, 0x27), 2)

    val fieldIdx = if (rowCounts[0x04] < 65536) 2 else 4
    val methodIdx = if (rowCounts[0x06] < 65536) 2 else 4
    val paramIdx = if (rowCounts[0x08] < 65536) 2 else 4
    val typeDefIdx = if (rowCounts[0x02] < 65536) 2 else 4
    val eventIdx = if (rowCounts[0x14] < 65536) 2 else 4
    val propIdx = if (rowCounts[0x17] < 65536) 2 else 4
    val modRefIdx = if (rowCounts[0x1A] < 65536) 2 else 4

    return when (table) {
      0x00 -> 2 + strIdxSize + guidIdxSize * 3 // Module
      0x01 -> resolutionScope + strIdxSize * 2 // TypeRef
      0x02 -> 4 + strIdxSize * 2 + typeDefOrRef + fieldIdx + methodIdx // TypeDef
      0x04 -> 2 + strIdxSize + blobIdxSize // Field
      0x06 -> 8 + strIdxSize + blobIdxSize + paramIdx // MethodDef
      0x08 -> 4 + strIdxSize // Param
      0x09 -> typeDefIdx + typeDefOrRef // InterfaceImpl
      0x0A -> memberRefParent + strIdxSize + blobIdxSize // MemberRef
      0x0B -> 2 + hasConstant + blobIdxSize // Constant
      0x0C -> hasCustomAttr + customAttrType + blobIdxSize // CustomAttribute
      0x0D -> hasFieldMarshal + blobIdxSize // FieldMarshal
      0x0E -> 2 + hasDeclSec + blobIdxSize // DeclSecurity
      0x0F -> 6 + typeDefIdx // ClassLayout
      0x10 -> 4 + fieldIdx // FieldLayout
      0x11 -> blobIdxSize // StandAloneSig
      0x12 -> typeDefIdx + eventIdx // EventMap
      0x14 -> 2 + strIdxSize + typeDefOrRef // Event
      0x15 -> typeDefIdx + propIdx // PropertyMap
      0x17 -> 2 + strIdxSize + blobIdxSize // Property
      0x18 -> 2 + methodIdx + hasSemantics // MethodSemantics
      0x19 -> typeDefIdx + methodDefOrRef + methodDefOrRef // MethodImpl
      0x1A -> strIdxSize // ModuleRef
      0x1B -> blobIdxSize // TypeSpec
      0x1C -> 2 + memberForwarded + strIdxSize + modRefIdx // ImplMap
      0x1D -> 4 + fieldIdx // FieldRVA
      0x20 -> 16 + blobIdxSize + strIdxSize * 2 // Assembly
      0x21 -> 4 // AssemblyProcessor
      0x22 -> 12 // AssemblyOS
      0x23 -> 12 + blobIdxSize + strIdxSize * 2 // AssemblyRef
      else -> -1
    }
  }

  private fun invalid(fileName: String, msg: String) =
    MonoAssemblyReport(
      assemblyFileName = fileName,
      validPeCli = false,
      runtimeVersion = "",
      referencedAssemblies = emptyList(),
      types = emptyList(),
      warnings = listOf(msg),
      provenance = DataProvenance.MISSING_UNRECOVERABLE,
      errorMessage = msg,
    )

  private data class PeSection(val virtualAddress: Long, val virtualSize: Long, val rawPointer: Long, val rawSize: Long)
}
