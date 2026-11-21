package com.reu.engine.metadata

import com.reu.core.common.BinarySource
import com.reu.core.common.ByteArrayBinarySource
import com.reu.core.common.FileBinarySource
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
data class Il2CppTypeSummary(
  val namespaceName: String,
  val typeName: String,
  val flags: Long,
  val token: Long,
  val methodStart: Int,
  val methodCount: Int,
  val fieldStart: Int,
  val fieldCount: Int,
)

@Serializable
data class Il2CppMethodSummary(
  val name: String,
  val token: Long,
  val flags: Int,
  val parameterCount: Int,
  val returnTypeIndex: Int,
)

@Serializable
data class Il2CppFieldSummary(
  val name: String,
  val typeIndex: Int,
  val token: Long,
)

@Serializable
data class Il2CppMetadataReport(
  val valid: Boolean,
  val sanityHex: String,
  val metadataVersion: Int,
  val stringPoolSizeBytes: Long,
  val stringLiteralCount: Int,
  val imageCount: Int,
  val typeDefinitionCount: Int,
  val methodCount: Int,
  val fieldCount: Int,
  val images: List<String>,
  val types: List<Il2CppTypeSummary>,
  val methods: List<Il2CppMethodSummary>,
  val fields: List<Il2CppFieldSummary>,
  val sampleStringLiterals: List<String>,
  val consistencyWarnings: List<String>,
  val errorMessage: String? = null,
)

class GlobalMetadataParser {
  fun parse(file: File, maxItemsPerTable: Int = 1500): Il2CppMetadataReport {
    if (!file.exists() || file.length() < 136) {
      return invalid("Metadata file missing or smaller than minimum IL2CPP header (136 bytes).")
    }
    FileBinarySource(file).use { src -> return parseSource(src, maxItemsPerTable) }
  }

  fun parseBytes(bytes: ByteArray, maxItemsPerTable: Int = 1500): Il2CppMetadataReport {
    if (bytes.size < 136) return invalid("Metadata buffer smaller than minimum IL2CPP header.")
    ByteArrayBinarySource(bytes).use { src -> return parseSource(src, maxItemsPerTable) }
  }

  private fun parseSource(src: BinarySource, maxItems: Int): Il2CppMetadataReport {
    src.position = 0
    val sanity = src.readU32LE()
    val version = src.readI32LE()
    val sanityHex = "0x" + sanity.toString(16).uppercase()

    if (sanity != 0xFAB11BAFL) {
      return invalid(
        "Invalid global-metadata.dat magic ($sanityHex != 0xFAB11BAF). Metadata may be encrypted or obfuscated.",
        sanityHex,
        version,
      )
    }

    if (version !in 16..35) {
      return invalid("Unsupported IL2CPP metadata version: $version", sanityHex, version)
    }

    val warnings = mutableListOf<String>()

    // Header layout: pairs of (offset, size)
    val stringLiteralOffset = src.readU32LE() // 0x08
    val stringLiteralSize = src.readU32LE() // 0x0C
    val stringLiteralDataOffset = src.readU32LE() // 0x10
    val stringLiteralDataSize = src.readU32LE() // 0x14
    val stringOffset = src.readU32LE() // 0x18
    val stringSize = src.readU32LE() // 0x1C

    // Skip events(8), properties(8) -> at 0x30: methods(8)
    src.position = 0x30
    val methodsOffset = src.readU32LE()
    val methodsSize = src.readU32LE()
    // 0x38: parameterDefaultValues(8), 0x40: fieldDefaultValues(8), 0x48: fieldAndParameterDefaultValueData(8)
    // 0x50: fieldMarshaledSizes(8), 0x58: parameters(8), 0x60: fields(8)
    src.position = 0x60
    val fieldsOffset = src.readU32LE()
    val fieldsSize = src.readU32LE()

    // typeDefinitions & images offset vary slightly between v24 and v27+; read standard v24-31 locations safely:
    val typeDefsOffset: Long
    val typeDefsSize: Long
    val imagesOffset: Long
    val imagesSize: Long
    if (src.length >= 0xC0) {
      src.position = 0xA0
      typeDefsOffset = src.readU32LE()
      typeDefsSize = src.readU32LE()
      imagesOffset = src.readU32LE()
      imagesSize = src.readU32LE()
    } else {
      typeDefsOffset = 0L; typeDefsSize = 0L; imagesOffset = 0L; imagesSize = 0L
    }

    val stringPool =
      if (stringOffset + stringSize <= src.length && stringSize in 1..(32L * 1024 * 1024)) {
        src.position = stringOffset
        src.readBytes(stringSize.toInt())
      } else {
        if (stringSize > 0) warnings.add("String pool bounds exceed file size.")
        ByteArray(0)
      }

    // Parse sample string literals
    val literals = mutableListOf<String>()
    val litCount = (stringLiteralSize / 8L).toInt().coerceAtLeast(0)
    if (stringLiteralOffset + stringLiteralSize <= src.length &&
      stringLiteralDataOffset + stringLiteralDataSize <= src.length
    ) {
      val toRead = minOf(litCount, 200)
      for (i in 0 until toRead) {
        src.position = stringLiteralOffset + i * 8L
        val len = src.readU32LE().toInt()
        val dataRel = src.readU32LE()
        if (len in 1..256 && dataRel + len <= stringLiteralDataSize) {
          src.position = stringLiteralDataOffset + dataRel
          val bytes = src.readBytes(len)
          val text = String(bytes, StandardCharsets.UTF_8)
          if (text.any { !it.isISOControl() }) literals.add(text)
        }
      }
    }

    // Parse ImageDefinitions (40 bytes in v24-v29+, first 4 bytes = nameIndex)
    val imageEntrySize = if (version >= 27) 40 else 40
    val imageTotal = if (imagesOffset + imagesSize <= src.length) (imagesSize / imageEntrySize).toInt() else 0
    val images = mutableListOf<String>()
    for (i in 0 until minOf(imageTotal, maxItems)) {
      src.position = imagesOffset + i.toLong() * imageEntrySize
      val nameIdx = src.readI32LE()
      val imgName = readPoolString(stringPool, nameIdx)
      if (imgName.isNotBlank()) images.add(imgName)
    }

    // Parse TypeDefinitions (standard 88-byte layout: nameIdx@0, nsIdx@4, ... fieldStart@52, methodStart@56, ... fieldCount@76, methodCount@78, ... flags@72, token@84)
    val typeEntrySize = if (version >= 29) 88 else 88
    val typeTotal =
      if (typeDefsOffset > 0 && typeDefsOffset + typeDefsSize <= src.length && typeDefsSize % typeEntrySize == 0L) {
        (typeDefsSize / typeEntrySize).toInt()
      } else {
        0
      }
    val types = mutableListOf<Il2CppTypeSummary>()
    for (i in 0 until minOf(typeTotal, maxItems)) {
      src.position = typeDefsOffset + i.toLong() * typeEntrySize
      val nameIdx = src.readI32LE()
      val nsIdx = src.readI32LE()
      val typeName = readPoolString(stringPool, nameIdx)
      val nsName = readPoolString(stringPool, nsIdx)
      src.position = typeDefsOffset + i.toLong() * typeEntrySize + 52
      val fieldStart = src.readI32LE()
      val methodStart = src.readI32LE()
      src.position = typeDefsOffset + i.toLong() * typeEntrySize + 72
      val flags = src.readU32LE()
      val fieldCount = src.readU16LE()
      val methodCount = src.readU16LE()
      src.position = typeDefsOffset + i.toLong() * typeEntrySize + 84
      val token = src.readU32LE()
      if (typeName.isNotBlank()) {
        types.add(
          Il2CppTypeSummary(
            namespaceName = nsName,
            typeName = typeName,
            flags = flags,
            token = token,
            methodStart = methodStart,
            methodCount = methodCount,
            fieldStart = fieldStart,
            fieldCount = fieldCount,
          ),
        )
      }
    }

    // Parse MethodDefinitions (32 bytes in v24-31: nameIdx@0, declaringType@4, paramStart@8, returnType@12, ... token@24, flags@28, iflags@30, slot@32? paramCount@30)
    val methodEntrySize = 32
    val methodTotal =
      if (methodsOffset > 0 && methodsOffset + methodsSize <= src.length) (methodsSize / methodEntrySize).toInt() else 0
    val methods = mutableListOf<Il2CppMethodSummary>()
    for (i in 0 until minOf(methodTotal, maxItems)) {
      src.position = methodsOffset + i.toLong() * methodEntrySize
      val nameIdx = src.readI32LE()
      src.readI32LE() // declaringType
      src.readI32LE() // parameterStart
      val retType = src.readI32LE()
      src.position = methodsOffset + i.toLong() * methodEntrySize + 24
      val token = src.readU32LE()
      val flags = src.readU16LE()
      val paramCount = src.readU16LE()
      val mName = readPoolString(stringPool, nameIdx)
      if (mName.isNotBlank()) {
        methods.add(Il2CppMethodSummary(mName, token, flags, paramCount.coerceAtMost(64), retType))
      }
    }

    // Parse FieldDefinitions (12 bytes: nameIdx@0, typeIdx@4, token@8)
    val fieldEntrySize = 12
    val fieldTotal =
      if (fieldsOffset > 0 && fieldsOffset + fieldsSize <= src.length) (fieldsSize / fieldEntrySize).toInt() else 0
    val fields = mutableListOf<Il2CppFieldSummary>()
    for (i in 0 until minOf(fieldTotal, maxItems)) {
      src.position = fieldsOffset + i.toLong() * fieldEntrySize
      val nameIdx = src.readI32LE()
      val typeIdx = src.readI32LE()
      val token = src.readU32LE()
      val fName = readPoolString(stringPool, nameIdx)
      if (fName.isNotBlank()) {
        fields.add(Il2CppFieldSummary(fName, typeIdx, token))
      }
    }

    return Il2CppMetadataReport(
      valid = true,
      sanityHex = sanityHex,
      metadataVersion = version,
      stringPoolSizeBytes = stringSize,
      stringLiteralCount = litCount,
      imageCount = imageTotal,
      typeDefinitionCount = typeTotal,
      methodCount = methodTotal,
      fieldCount = fieldTotal,
      images = images,
      types = types,
      methods = methods,
      fields = fields,
      sampleStringLiterals = literals,
      consistencyWarnings = warnings,
    )
  }

  private fun readPoolString(pool: ByteArray, index: Int): String {
    if (index < 0 || index >= pool.size) return ""
    var end = index
    while (end < pool.size && pool[end] != 0.toByte() && end - index < 256) end++
    val candidate = String(pool, index, end - index, StandardCharsets.UTF_8)
    return if (candidate.all { !it.isISOControl() }) candidate else ""
  }

  private fun invalid(msg: String, sanityHex: String = "0x0", version: Int = 0) =
    Il2CppMetadataReport(
      valid = false,
      sanityHex = sanityHex,
      metadataVersion = version,
      stringPoolSizeBytes = 0,
      stringLiteralCount = 0,
      imageCount = 0,
      typeDefinitionCount = 0,
      methodCount = 0,
      fieldCount = 0,
      images = emptyList(),
      types = emptyList(),
      methods = emptyList(),
      fields = emptyList(),
      sampleStringLiterals = emptyList(),
      consistencyWarnings = listOf(msg),
      errorMessage = msg,
    )
}
