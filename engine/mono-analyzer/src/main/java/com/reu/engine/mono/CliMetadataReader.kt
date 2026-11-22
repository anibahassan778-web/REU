package com.reu.engine.mono

import com.reu.core.common.BinarySource
import java.nio.charset.StandardCharsets

internal data class PeSection(
  val virtualAddress: Long,
  val virtualSize: Long,
  val rawPointer: Long,
  val rawSize: Long,
)

internal data class CliTypeRef(
  val token: Int,
  val namespaceName: String,
  val name: String,
)

internal data class CliTypeDef(
  val token: Int,
  val flags: Long,
  val name: String,
  val namespaceName: String,
  val extendsToken: Int,
  val fieldStart: Int,
  val methodStart: Int,
)

internal data class CliFieldDef(
  val token: Int,
  val flags: Int,
  val name: String,
  val signatureBlobIdx: Int,
)

internal data class CliParamDef(
  val flags: Int,
  val sequence: Int,
  val name: String,
)

internal data class CliMethodDef(
  val token: Int,
  val rva: Long,
  val implFlags: Int,
  val flags: Int,
  val name: String,
  val signatureBlobIdx: Int,
  val paramStart: Int,
)

internal data class CliMemberRef(
  val token: Int,
  val parentToken: Int,
  val name: String,
  val signatureBlobIdx: Int,
)

internal data class MethodSignatureInfo(
  val hasThis: Boolean,
  val returnType: String,
  val paramTypes: List<String>,
)

internal class CliMetadataContext(
  val runtimeVersion: String,
  val assemblyRefs: List<String>,
  val typeRefs: List<CliTypeRef>,
  val typeDefs: List<CliTypeDef>,
  val fields: List<CliFieldDef>,
  val methods: List<CliMethodDef>,
  val params: List<CliParamDef>,
  val interfaceImpls: Map<Int, List<Int>>,
  val memberRefs: List<CliMemberRef>,
  val standAloneSigs: List<Int>,
  val typeSpecs: List<Int>,
  val methodSpecs: List<Pair<Int, Int>>,
  val stringHeap: ByteArray,
  val userStringsHeap: ByteArray,
  val blobHeap: ByteArray,
  val warnings: List<String>,
) {
  val referencedNamespaces = linkedSetOf<String>()

  private val fieldOwnerByFieldIdx = IntArray(fields.size + 1) { -1 }
  private val methodOwnerByMethodIdx = IntArray(methods.size + 1) { -1 }

  init {
    for (i in typeDefs.indices) {
      val td = typeDefs[i]
      val nextF = if (i + 1 < typeDefs.size) typeDefs[i + 1].fieldStart else (fields.size + 1)
      for (f in td.fieldStart until nextF) {
        if (f in 1 until fieldOwnerByFieldIdx.size) fieldOwnerByFieldIdx[f] = i
      }
      val nextM = if (i + 1 < typeDefs.size) typeDefs[i + 1].methodStart else (methods.size + 1)
      for (m in td.methodStart until nextM) {
        if (m in 1 until methodOwnerByMethodIdx.size) methodOwnerByMethodIdx[m] = i
      }
    }
  }

  fun readUserString(token: Int): String {
    val idx = token and 0x00FFFFFF
    if (idx <= 0 || idx >= userStringsHeap.size) return "\"\""
    val (byteLen, dataStart) = readCompressedUint(userStringsHeap, idx) ?: return "\"\""
    if (byteLen <= 1 || dataStart + byteLen > userStringsHeap.size) return "\"\""
    val utf16Bytes = byteLen - 1 // strip trailing terminal flag byte
    val raw = String(userStringsHeap, dataStart, utf16Bytes, StandardCharsets.UTF_16LE)
    return "\"" + escapeCSharpString(raw) + "\""
  }

  fun resolveTypeToken(token: Int): String {
    val table = (token ushr 24) and 0xFF
    val row = token and 0x00FFFFFF
    return when (table) {
      0x01 -> {
        val tr = typeRefs.getOrNull(row - 1) ?: return "object"
        recordNamespace(tr.namespaceName)
        shortTypeName(tr.namespaceName, tr.name)
      }
      0x02 -> {
        val td = typeDefs.getOrNull(row - 1) ?: return "object"
        recordNamespace(td.namespaceName)
        td.name.substringBefore('`')
      }
      0x1B -> {
        val blobIdx = typeSpecs.getOrNull(row - 1) ?: return "object"
        decodeTypeSpec(blobIdx)
      }
      else -> "object"
    }
  }

  fun resolveFieldToken(token: Int): Triple<String, String, String> {
    val table = (token ushr 24) and 0xFF
    val row = token and 0x00FFFFFF
    return when (table) {
      0x04 -> {
        val f = fields.getOrNull(row - 1) ?: return Triple("this", "field_0x${token.toString(16)}", "object")
        val ownerIdx = if (row in fieldOwnerByFieldIdx.indices) fieldOwnerByFieldIdx[row] else -1
        val ownerName = typeDefs.getOrNull(ownerIdx)?.name?.substringBefore('`') ?: "this"
        val fType = decodeFieldType(f.signatureBlobIdx)
        Triple(ownerName, sanitizeIdentifier(f.name), fType)
      }
      0x0A -> {
        val mr = memberRefs.getOrNull(row - 1) ?: return Triple("/*unknown*/", "field_0x${token.toString(16)}", "object")
        val owner = resolveTypeToken(mr.parentToken)
        val fType = decodeFieldType(mr.signatureBlobIdx)
        Triple(owner, sanitizeIdentifier(mr.name), fType)
      }
      else -> Triple("this", "field_0x${token.toString(16)}", "object")
    }
  }

  fun resolveCallableToken(token: Int): ResolvedCallable {
    val table = (token ushr 24) and 0xFF
    val row = token and 0x00FFFFFF
    return when (table) {
      0x06 -> {
        val md = methods.getOrNull(row - 1) ?: return ResolvedCallable("Unknown", "Method_${row}", emptyList(), "void", true)
        val ownerIdx = if (row in methodOwnerByMethodIdx.indices) methodOwnerByMethodIdx[row] else -1
        val ownerTd = typeDefs.getOrNull(ownerIdx)
        val ownerName = ownerTd?.name?.substringBefore('`') ?: "Unknown"
        val sig = decodeMethodSignature(md.signatureBlobIdx)
        ResolvedCallable(ownerName, md.name, sig.paramTypes, sig.returnType, sig.hasThis)
      }
      0x0A -> {
        val mr = memberRefs.getOrNull(row - 1) ?: return ResolvedCallable("Unknown", "Member_${row}", emptyList(), "void", true)
        val owner = resolveTypeToken(mr.parentToken)
        val sig = decodeMethodSignature(mr.signatureBlobIdx)
        ResolvedCallable(owner, mr.name, sig.paramTypes, sig.returnType, sig.hasThis)
      }
      0x2B -> {
        val spec = methodSpecs.getOrNull(row - 1) ?: return ResolvedCallable("Unknown", "GenericMethod_${row}", emptyList(), "void", true)
        val base = resolveCallableToken(spec.first)
        val genArgs = decodeMethodSpecArgs(spec.second)
        if (genArgs.isEmpty()) return base
        fun subst(t: String): String {
          var res = t
          genArgs.forEachIndexed { idx, argType ->
            if (res == "M$idx") res = argType
          }
          return res
        }
        val cleanName = base.name.substringBefore('`') + "<" + genArgs.joinToString(", ") + ">"
        base.copy(
          name = cleanName,
          paramTypes = base.paramTypes.map(::subst),
          returnType = subst(base.returnType),
        )
      }
      else -> ResolvedCallable("Unknown", "Call_0x${token.toString(16)}", emptyList(), "void", true)
    }
  }

  private fun decodeMethodSpecArgs(blobIdx: Int): List<String> {
    if (blobIdx <= 0 || blobIdx >= blobHeap.size) return emptyList()
    val (len, start) = readCompressedUint(blobHeap, blobIdx) ?: return emptyList()
    if (len < 2 || start + len > blobHeap.size) return emptyList()
    val cur = BlobCursor(blobHeap, start, start + len)
    val kind = cur.readU8()
    if (kind != 0x0A) return emptyList()
    val count = cur.readCompressedUInt().coerceIn(0, 8)
    return (0 until count).map { decodeTypeSig(cur) }
  }

  fun decodeFieldType(blobIdx: Int): String {
    if (blobIdx <= 0 || blobIdx >= blobHeap.size) return "object"
    val (len, start) = readCompressedUint(blobHeap, blobIdx) ?: return "object"
    if (len < 2 || start + len > blobHeap.size) return "object"
    val cur = BlobCursor(blobHeap, start, start + len)
    val sigKind = cur.readU8()
    if ((sigKind and 0x0F) != 0x06) return "object"
    return decodeTypeSig(cur)
  }

  fun decodeMethodSignature(blobIdx: Int): MethodSignatureInfo {
    if (blobIdx <= 0 || blobIdx >= blobHeap.size) return MethodSignatureInfo(true, "void", emptyList())
    val (len, start) = readCompressedUint(blobHeap, blobIdx) ?: return MethodSignatureInfo(true, "void", emptyList())
    if (len < 3 || start + len > blobHeap.size) return MethodSignatureInfo(true, "void", emptyList())
    val cur = BlobCursor(blobHeap, start, start + len)
    val cc = cur.readU8()
    val hasThis = (cc and 0x20) != 0
    if ((cc and 0x10) != 0) cur.readCompressedUInt() // generic param count
    val paramCount = cur.readCompressedUInt().coerceIn(0, 32)
    val retType = decodeTypeSig(cur)
    val pTypes = ArrayList<String>(paramCount)
    for (i in 0 until paramCount) {
      if (!cur.hasMore()) break
      pTypes.add(decodeTypeSig(cur))
    }
    return MethodSignatureInfo(hasThis, retType, pTypes)
  }

  fun decodeLocalVarTypes(standAloneSigToken: Int): List<String> {
    if ((standAloneSigToken ushr 24) != 0x11) return emptyList()
    val row = standAloneSigToken and 0x00FFFFFF
    val blobIdx = standAloneSigs.getOrNull(row - 1) ?: return emptyList()
    if (blobIdx <= 0 || blobIdx >= blobHeap.size) return emptyList()
    val (len, start) = readCompressedUint(blobHeap, blobIdx) ?: return emptyList()
    if (len < 2 || start + len > blobHeap.size) return emptyList()
    val cur = BlobCursor(blobHeap, start, start + len)
    val kind = cur.readU8()
    if (kind != 0x07) return emptyList()
    val count = cur.readCompressedUInt().coerceIn(0, 64)
    val out = ArrayList<String>(count)
    for (i in 0 until count) {
      if (!cur.hasMore()) break
      out.add(decodeTypeSig(cur))
    }
    return out
  }

  private fun decodeTypeSpec(blobIdx: Int): String {
    if (blobIdx <= 0 || blobIdx >= blobHeap.size) return "object"
    val (len, start) = readCompressedUint(blobHeap, blobIdx) ?: return "object"
    if (len < 1 || start + len > blobHeap.size) return "object"
    return decodeTypeSig(BlobCursor(blobHeap, start, start + len))
  }

  private fun decodeTypeSig(cur: BlobCursor): String {
    if (!cur.hasMore()) return "object"
    return when (val et = cur.readU8()) {
      0x01 -> "void"
      0x02 -> "bool"
      0x03 -> "char"
      0x04 -> "sbyte"
      0x05 -> "byte"
      0x06 -> "short"
      0x07 -> "ushort"
      0x08 -> "int"
      0x09 -> "uint"
      0x0A -> "long"
      0x0B -> "ulong"
      0x0C -> "float"
      0x0D -> "double"
      0x0E -> "string"
      0x0F -> "${decodeTypeSig(cur)}*"
      0x10 -> "ref ${decodeTypeSig(cur)}"
      0x11, 0x12 -> {
        val encoded = cur.readCompressedUInt()
        resolveTypeToken(decodeTypeDefOrRefCoded(encoded))
      }
      0x13 -> "T${cur.readCompressedUInt()}"
      0x14 -> {
        val elem = decodeTypeSig(cur)
        val rank = cur.readCompressedUInt().coerceIn(1, 4)
        val sizes = cur.readCompressedUInt()
        for (i in 0 until sizes) cur.readCompressedUInt()
        val lo = cur.readCompressedUInt()
        for (i in 0 until lo) cur.readCompressedUInt()
        "$elem[${",".repeat((rank - 1).coerceAtLeast(0))}]"
      }
      0x15 -> {
        val genType = decodeTypeSig(cur)
        val argCount = cur.readCompressedUInt().coerceIn(0, 8)
        val args = (0 until argCount).map { decodeTypeSig(cur) }
        "$genType<${args.joinToString(", ")}>"
      }
      0x18 -> "IntPtr"
      0x19 -> "UIntPtr"
      0x1C -> "object"
      0x1D -> "${decodeTypeSig(cur)}[]"
      0x1E -> "M${cur.readCompressedUInt()}"
      0x1F, 0x20 -> {
        cur.readCompressedUInt() // modreq/modopt token
        decodeTypeSig(cur)
      }
      0x45 -> decodeTypeSig(cur) // pinned
      else -> "object"
    }
  }

  private fun recordNamespace(ns: String) {
    if (ns.isBlank() || ns.startsWith("<")) return
    if (
      ns == "System" ||
        ns.startsWith("System.") ||
        ns == "UnityEngine" ||
        ns.startsWith("UnityEngine.") ||
        ns.startsWith("Unity.") ||
        ns.startsWith("TMPro")
    ) {
      referencedNamespaces.add(ns)
    }
  }

  private fun shortTypeName(ns: String, rawName: String): String {
    val clean = rawName.substringBefore('`')
    return when {
      ns == "System" && clean == "Object" -> "object"
      ns == "System" && clean == "String" -> "string"
      ns == "System" && clean == "Boolean" -> "bool"
      ns == "System" && clean == "Int32" -> "int"
      ns == "System" && clean == "Single" -> "float"
      ns == "System" && clean == "Double" -> "double"
      ns == "System" && clean == "Void" -> "void"
      else -> clean
    }
  }

  private fun escapeCSharpString(s: String): String =
    buildString(s.length) {
      for (ch in s) {
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> if (ch.code < 0x20) append(String.format(java.util.Locale.US, "\\u%04x", ch.code)) else append(ch)
        }
      }
    }

  private fun sanitizeIdentifier(raw: String): String {
    if (raw.startsWith("<") && raw.contains(">k__BackingField")) {
      return raw.substringAfter('<').substringBefore('>')
    }
    return raw.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "_field" }
  }
}

internal data class ResolvedCallable(
  val ownerType: String,
  val name: String,
  val paramTypes: List<String>,
  val returnType: String,
  val hasThis: Boolean,
)

internal class BlobCursor(private val data: ByteArray, private var pos: Int, private val end: Int) {
  fun hasMore(): Boolean = pos < end
  fun readU8(): Int = if (pos < end) data[pos++].toInt() and 0xFF else 0
  fun readCompressedUInt(): Int {
    val (v, next) = readCompressedUint(data, pos) ?: return 0
    pos = next.coerceAtMost(end)
    return v
  }
}

internal fun readCompressedUint(buf: ByteArray, offset: Int): Pair<Int, Int>? {
  if (offset < 0 || offset >= buf.size) return null
  val b0 = buf[offset].toInt() and 0xFF
  return when {
    (b0 and 0x80) == 0 -> b0 to (offset + 1)
    (b0 and 0xC0) == 0x80 && offset + 1 < buf.size -> {
      val v = ((b0 and 0x3F) shl 8) or (buf[offset + 1].toInt() and 0xFF)
      v to (offset + 2)
    }
    (b0 and 0xE0) == 0xC0 && offset + 3 < buf.size -> {
      val v =
        ((b0 and 0x1F) shl 24) or
          ((buf[offset + 1].toInt() and 0xFF) shl 16) or
          ((buf[offset + 2].toInt() and 0xFF) shl 8) or
          (buf[offset + 3].toInt() and 0xFF)
      v to (offset + 4)
    }
    else -> null
  }
}

internal fun decodeTypeDefOrRefCoded(coded: Int): Int {
  val row = coded ushr 2
  if (row == 0) return 0
  val table =
    when (coded and 0x03) {
      0 -> 0x02
      1 -> 0x01
      2 -> 0x1B
      else -> return 0
    }
  return (table shl 24) or row
}

internal fun decodeMemberRefParentCoded(coded: Int): Int {
  val row = coded ushr 3
  if (row == 0) return 0
  val table =
    when (coded and 0x07) {
      0 -> 0x02
      1 -> 0x01
      2 -> 0x1A
      3 -> 0x06
      4 -> 0x1B
      else -> return 0
    }
  return (table shl 24) or row
}

internal fun decodeMethodDefOrRefCoded(coded: Int): Int {
  val row = coded ushr 1
  if (row == 0) return 0
  val table = if ((coded and 0x01) == 0) 0x06 else 0x0A
  return (table shl 24) or row
}

internal object CliMetadataReader {
  fun read(
    src: BinarySource,
    mdOffset: Long,
    sections: List<PeSection>,
  ): CliMetadataContext? {
    src.position = mdOffset
    if (src.readU32LE() != 0x424A5342L) return null
    src.readU16LE(); src.readU16LE(); src.readU32LE()
    val verLen = src.readU32LE().toInt().coerceIn(0, 256)
    val runtimeVersion = String(src.readBytes(verLen), StandardCharsets.UTF_8).trimEnd('\u0000')
    src.align(4)
    src.readU16LE() // flags
    val streamCount = src.readU16LE()

    var stringsOff = 0L; var stringsSize = 0L
    var usOff = 0L; var usSize = 0L
    var blobOff = 0L; var blobSize = 0L
    var tildeOff = 0L; var tildeSize = 0L

    for (i in 0 until streamCount.coerceAtMost(16)) {
      val sOff = src.readU32LE()
      val sSize = src.readU32LE()
      val sName = src.readNullTerminatedAscii(32)
      src.align(4)
      when (sName) {
        "#Strings" -> { stringsOff = mdOffset + sOff; stringsSize = sSize }
        "#US" -> { usOff = mdOffset + sOff; usSize = sSize }
        "#Blob" -> { blobOff = mdOffset + sOff; blobSize = sSize }
        "#~", "#-" -> { tildeOff = mdOffset + sOff; tildeSize = sSize }
      }
    }

    val stringHeap = readBoundedHeap(src, stringsOff, stringsSize)
    val usHeap = readBoundedHeap(src, usOff, usSize)
    val blobHeap = readBoundedHeap(src, blobOff, blobSize)
    val warnings = mutableListOf<String>()

    val typeRefs = mutableListOf<CliTypeRef>()
    val typeDefs = mutableListOf<CliTypeDef>()
    val fields = mutableListOf<CliFieldDef>()
    val methods = mutableListOf<CliMethodDef>()
    val params = mutableListOf<CliParamDef>()
    val interfaceImpls = mutableMapOf<Int, MutableList<Int>>()
    val memberRefs = mutableListOf<CliMemberRef>()
    val standAloneSigs = mutableListOf<Int>()
    val typeSpecs = mutableListOf<Int>()
    val methodSpecs = mutableListOf<Pair<Int, Int>>()
    val assemblyRefs = mutableListOf<String>()

    if (tildeOff <= 0 || tildeOff + 24 > src.length || tildeSize < 24) {
      warnings.add("CLR #~ compressed metadata stream not found.")
      return CliMetadataContext(
        runtimeVersion, assemblyRefs, typeRefs, typeDefs, fields, methods, params, interfaceImpls, memberRefs,
        standAloneSigs, typeSpecs, methodSpecs, stringHeap, usHeap, blobHeap, warnings,
      )
    }

    src.position = tildeOff
    src.readU32LE() // reserved
    src.readU8(); src.readU8()
    val heapSizes = src.readU8()
    src.readU8()
    val validMask = src.readU64LE()
    src.readU64LE() // sortedMask

    val strIdx = if ((heapSizes and 0x01) != 0) 4 else 2
    val guidIdx = if ((heapSizes and 0x02) != 0) 4 else 2
    val blobIdx = if ((heapSizes and 0x04) != 0) 4 else 2

    val rowCounts = IntArray(64)
    for (bit in 0 until 64) {
      if (((validMask ushr bit) and 1L) != 0L) {
        rowCounts[bit] = src.readI32LE().coerceAtLeast(0)
      }
    }

    var cursor = src.position
    val tableOffsets = LongArray(64) { -1L }
    for (t in 0..0x2C) {
      if (rowCounts[t] > 0) {
        tableOffsets[t] = cursor
        val rowW = computeCliRowWidth(t, rowCounts, strIdx, guidIdx, blobIdx)
        if (rowW <= 0) {
          warnings.add("Unsupported CLI table 0x${t.toString(16)}; stopped table offset scan.")
          break
        }
        cursor += rowW.toLong() * rowCounts[t]
      }
    }

    val resScopeSz = codedIdxSize(rowCounts, intArrayOf(0x00, 0x1A, 0x23, 0x01), 2)
    val typeDefOrRefSz = codedIdxSize(rowCounts, intArrayOf(0x02, 0x01, 0x1B), 2)
    val memberRefParentSz = codedIdxSize(rowCounts, intArrayOf(0x02, 0x01, 0x1A, 0x06, 0x1B), 3)
    val methodDefOrRefSz = codedIdxSize(rowCounts, intArrayOf(0x06, 0x0A), 1)
    val fieldIdxSz = if (rowCounts[0x04] < 65536) 2 else 4
    val methodIdxSz = if (rowCounts[0x06] < 65536) 2 else 4
    val paramIdxSz = if (rowCounts[0x08] < 65536) 2 else 4

    // 0x01 TypeRef
    if (tableOffsets[0x01] > 0) {
      val rowW = resScopeSz + strIdx * 2
      val count = minOf(rowCounts[0x01], 16_384)
      for (r in 0 until count) {
        src.position = tableOffsets[0x01] + r.toLong() * rowW + resScopeSz
        val name = readHeapString(stringHeap, readIdx(src, strIdx))
        val ns = readHeapString(stringHeap, readIdx(src, strIdx))
        typeRefs.add(CliTypeRef((0x01 shl 24) or (r + 1), ns, name))
      }
    }

    // 0x02 TypeDef
    if (tableOffsets[0x02] > 0) {
      val rowW = 4 + strIdx * 2 + typeDefOrRefSz + fieldIdxSz + methodIdxSz
      val count = minOf(rowCounts[0x02], 16_384)
      for (r in 0 until count) {
        src.position = tableOffsets[0x02] + r.toLong() * rowW
        val flags = src.readU32LE()
        val name = readHeapString(stringHeap, readIdx(src, strIdx))
        val ns = readHeapString(stringHeap, readIdx(src, strIdx))
        val extendsCoded = readIdx(src, typeDefOrRefSz)
        val fStart = readIdx(src, fieldIdxSz)
        val mStart = readIdx(src, methodIdxSz)
        typeDefs.add(CliTypeDef((0x02 shl 24) or (r + 1), flags, name, ns, decodeTypeDefOrRefCoded(extendsCoded), fStart, mStart))
      }
    }

    // 0x04 Field
    if (tableOffsets[0x04] > 0) {
      val rowW = 2 + strIdx + blobIdx
      val count = minOf(rowCounts[0x04], 65_536)
      for (r in 0 until count) {
        src.position = tableOffsets[0x04] + r.toLong() * rowW
        val flags = src.readU16LE()
        val name = readHeapString(stringHeap, readIdx(src, strIdx))
        val sig = readIdx(src, blobIdx)
        fields.add(CliFieldDef((0x04 shl 24) or (r + 1), flags, name, sig))
      }
    }

    // 0x06 MethodDef
    if (tableOffsets[0x06] > 0) {
      val rowW = 8 + strIdx + blobIdx + paramIdxSz
      val count = minOf(rowCounts[0x06], 65_536)
      for (r in 0 until count) {
        src.position = tableOffsets[0x06] + r.toLong() * rowW
        val rva = src.readU32LE()
        val implFlags = src.readU16LE()
        val flags = src.readU16LE()
        val name = readHeapString(stringHeap, readIdx(src, strIdx))
        val sig = readIdx(src, blobIdx)
        val pStart = readIdx(src, paramIdxSz)
        methods.add(CliMethodDef((0x06 shl 24) or (r + 1), rva, implFlags, flags, name, sig, pStart))
      }
    }

    // 0x08 Param
    if (tableOffsets[0x08] > 0) {
      val rowW = 4 + strIdx
      val count = minOf(rowCounts[0x08], 65_536)
      for (r in 0 until count) {
        src.position = tableOffsets[0x08] + r.toLong() * rowW
        val flags = src.readU16LE()
        val seq = src.readU16LE()
        val name = readHeapString(stringHeap, readIdx(src, strIdx))
        params.add(CliParamDef(flags, seq, name))
      }
    }

    // 0x09 InterfaceImpl
    if (tableOffsets[0x09] > 0) {
      val typeDefIdxSz = if (rowCounts[0x02] < 65536) 2 else 4
      val rowW = typeDefIdxSz + typeDefOrRefSz
      val count = minOf(rowCounts[0x09], 16_384)
      for (r in 0 until count) {
        src.position = tableOffsets[0x09] + r.toLong() * rowW
        val classRow = readIdx(src, typeDefIdxSz)
        val ifaceTok = decodeTypeDefOrRefCoded(readIdx(src, typeDefOrRefSz))
        if (classRow > 0 && ifaceTok != 0) {
          interfaceImpls.getOrPut((0x02 shl 24) or classRow) { mutableListOf() }.add(ifaceTok)
        }
      }
    }

    // 0x0A MemberRef
    if (tableOffsets[0x0A] > 0) {
      val rowW = memberRefParentSz + strIdx + blobIdx
      val count = minOf(rowCounts[0x0A], 65_536)
      for (r in 0 until count) {
        src.position = tableOffsets[0x0A] + r.toLong() * rowW
        val parent = decodeMemberRefParentCoded(readIdx(src, memberRefParentSz))
        val name = readHeapString(stringHeap, readIdx(src, strIdx))
        val sig = readIdx(src, blobIdx)
        memberRefs.add(CliMemberRef((0x0A shl 24) or (r + 1), parent, name, sig))
      }
    }

    // 0x11 StandAloneSig
    if (tableOffsets[0x11] > 0) {
      val count = minOf(rowCounts[0x11], 16_384)
      for (r in 0 until count) {
        src.position = tableOffsets[0x11] + r.toLong() * blobIdx
        standAloneSigs.add(readIdx(src, blobIdx))
      }
    }

    // 0x1B TypeSpec
    if (tableOffsets[0x1B] > 0) {
      val count = minOf(rowCounts[0x1B], 16_384)
      for (r in 0 until count) {
        src.position = tableOffsets[0x1B] + r.toLong() * blobIdx
        typeSpecs.add(readIdx(src, blobIdx))
      }
    }

    // 0x23 AssemblyRef
    if (tableOffsets[0x23] > 0) {
      val rowW = 12 + blobIdx + strIdx * 2
      val count = minOf(rowCounts[0x23], 512)
      for (r in 0 until count) {
        src.position = tableOffsets[0x23] + r.toLong() * rowW + 12 + blobIdx
        val refName = readHeapString(stringHeap, readIdx(src, strIdx))
        if (refName.isNotBlank()) assemblyRefs.add(refName)
      }
    }

    // 0x2B MethodSpec
    if (tableOffsets[0x2B] > 0) {
      val rowW = methodDefOrRefSz + blobIdx
      val count = minOf(rowCounts[0x2B], 16_384)
      for (r in 0 until count) {
        src.position = tableOffsets[0x2B] + r.toLong() * rowW
        val methodTok = decodeMethodDefOrRefCoded(readIdx(src, methodDefOrRefSz))
        val sig = readIdx(src, blobIdx)
        methodSpecs.add(methodTok to sig)
      }
    }

    return CliMetadataContext(
      runtimeVersion, assemblyRefs, typeRefs, typeDefs, fields, methods, params, interfaceImpls, memberRefs,
      standAloneSigs, typeSpecs, methodSpecs, stringHeap, usHeap, blobHeap, warnings,
    )
  }

  private fun readBoundedHeap(src: BinarySource, offset: Long, size: Long): ByteArray {
    if (offset <= 0 || size <= 0 || offset + size > src.length || size > 32L * 1024 * 1024) {
      return ByteArray(0)
    }
    src.position = offset
    return src.readBytes(size.toInt())
  }

  private fun readIdx(src: BinarySource, size: Int): Int =
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
    strIdx: Int,
    guidIdx: Int,
    blobIdx: Int,
  ): Int {
    val typeDefOrRef = codedIdxSize(rowCounts, intArrayOf(0x02, 0x01, 0x1B), 2)
    val resolutionScope = codedIdxSize(rowCounts, intArrayOf(0x00, 0x1A, 0x23, 0x01), 2)
    val hasConstant = codedIdxSize(rowCounts, intArrayOf(0x04, 0x08, 0x17), 2)
    val hasCustomAttr = codedIdxSize(rowCounts, intArrayOf(0x06, 0x04, 0x01, 0x02, 0x08, 0x09, 0x0A, 0x00, 0x0E, 0x17, 0x14, 0x11, 0x1A, 0x1B, 0x20, 0x23, 0x26, 0x27, 0x28, 0x2A, 0x2C, 0x2B), 5)
    val memberRefParent = codedIdxSize(rowCounts, intArrayOf(0x02, 0x01, 0x1A, 0x06, 0x1B), 3)
    val typeOrMethodDef = codedIdxSize(rowCounts, intArrayOf(0x02, 0x06), 1)
    val customAttrType = codedIdxSize(rowCounts, intArrayOf(0x06, 0x0A), 3)
    val hasFieldMarshal = codedIdxSize(rowCounts, intArrayOf(0x04, 0x08), 1)
    val hasDeclSec = codedIdxSize(rowCounts, intArrayOf(0x02, 0x06, 0x20), 2)
    val hasSemantics = codedIdxSize(rowCounts, intArrayOf(0x14, 0x17), 1)
    val methodDefOrRef = codedIdxSize(rowCounts, intArrayOf(0x06, 0x0A), 1)
    val memberForwarded = codedIdxSize(rowCounts, intArrayOf(0x04, 0x06), 1)
    val impl = codedIdxSize(rowCounts, intArrayOf(0x26, 0x23, 0x27), 2)

    val asmRefIdx = if (rowCounts[0x23] < 65536) 2 else 4
    val fieldIdx = if (rowCounts[0x04] < 65536) 2 else 4
    val methodIdx = if (rowCounts[0x06] < 65536) 2 else 4
    val paramIdx = if (rowCounts[0x08] < 65536) 2 else 4
    val typeDefIdx = if (rowCounts[0x02] < 65536) 2 else 4
    val eventIdx = if (rowCounts[0x14] < 65536) 2 else 4
    val propIdx = if (rowCounts[0x17] < 65536) 2 else 4
    val modRefIdx = if (rowCounts[0x1A] < 65536) 2 else 4
    val genParamIdx = if (rowCounts[0x2A] < 65536) 2 else 4

    return when (table) {
      0x00 -> 2 + strIdx + guidIdx * 3
      0x01 -> resolutionScope + strIdx * 2
      0x02 -> 4 + strIdx * 2 + typeDefOrRef + fieldIdx + methodIdx
      0x03 -> fieldIdx
      0x04 -> 2 + strIdx + blobIdx
      0x05 -> methodIdx
      0x06 -> 8 + strIdx + blobIdx + paramIdx
      0x07 -> paramIdx
      0x08 -> 4 + strIdx
      0x09 -> typeDefIdx + typeDefOrRef
      0x0A -> memberRefParent + strIdx + blobIdx
      0x0B -> 2 + hasConstant + blobIdx
      0x0C -> hasCustomAttr + customAttrType + blobIdx
      0x0D -> hasFieldMarshal + blobIdx
      0x0E -> 2 + hasDeclSec + blobIdx
      0x0F -> 6 + typeDefIdx
      0x10 -> 4 + fieldIdx
      0x11 -> blobIdx
      0x12 -> typeDefIdx + eventIdx
      0x13 -> eventIdx
      0x14 -> 2 + strIdx + typeDefOrRef
      0x15 -> typeDefIdx + propIdx
      0x16 -> propIdx
      0x17 -> 2 + strIdx + blobIdx
      0x18 -> 2 + methodIdx + hasSemantics
      0x19 -> typeDefIdx + methodDefOrRef + methodDefOrRef
      0x1A -> strIdx
      0x1B -> blobIdx
      0x1C -> 2 + memberForwarded + strIdx + modRefIdx
      0x1D -> 4 + fieldIdx
      0x20 -> 16 + blobIdx + strIdx * 2
      0x21 -> 4
      0x22 -> 12
      0x23 -> 12 + blobIdx * 2 + strIdx * 2
      0x24 -> 4 + asmRefIdx
      0x25 -> 12 + asmRefIdx
      0x26 -> 4 + strIdx + blobIdx
      0x27 -> 8 + strIdx * 2 + impl
      0x28 -> 8 + strIdx + impl
      0x29 -> typeDefIdx * 2
      0x2A -> 4 + typeOrMethodDef + strIdx
      0x2B -> methodDefOrRef + blobIdx
      0x2C -> genParamIdx + typeDefOrRef
      else -> -1
    }
  }
}
