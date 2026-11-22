package com.reu.engine.mono

import com.reu.core.common.BinarySource
import com.reu.core.common.ByteArrayBinarySource
import com.reu.core.common.DataProvenance
import com.reu.core.common.FileBinarySource
import java.io.File
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
  val isLibraryAssembly: Boolean = false,
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
    src.readU16LE()

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

    val sections = mutableListOf<PeSection>()
    val secTableStart = optStart + optHeaderSize
    for (i in 0 until numSections.coerceAtMost(64)) {
      src.position = secTableStart + i * 40L
      src.readBytes(8)
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
    src.readU32LE()
    val mdOffset = rvaToOffset(metadataRva, sections)
      ?: return invalid(fileName, "Unable to resolve CLI metadata RVA 0x${metadataRva.toString(16)}.")

    val ctx = CliMetadataReader.read(src, mdOffset, sections)
      ?: return invalid(fileName, "Invalid CLR metadata root signature (expected BSJB).")

    val isLib = isIncludedLibraryAssembly(fileName)
    val types = mutableListOf<MonoTypeModel>()

    for (i in ctx.typeDefs.indices) {
      val td = ctx.typeDefs[i]
      if (td.name == "<Module>" || td.name.isBlank()) continue
      // Skip compiler-generated private implementation detail classes
      if (td.name.startsWith("<PrivateImplementationDetails>")) continue

      val nextField = if (i + 1 < ctx.typeDefs.size) ctx.typeDefs[i + 1].fieldStart else (ctx.fields.size + 1)
      val nextMethod = if (i + 1 < ctx.typeDefs.size) ctx.typeDefs[i + 1].methodStart else (ctx.methods.size + 1)

      val fieldLines = mutableListOf<String>()
      val fieldNames = mutableListOf<String>()
      for (fIdx in td.fieldStart until nextField) {
        val f = ctx.fields.getOrNull(fIdx - 1) ?: continue
        if (f.name.isBlank()) continue
        fieldNames.add(f.name)
        val fType = ctx.decodeFieldType(f.signatureBlobIdx)
        val fVis = fieldVisibility(f.flags)
        val isStatic = (f.flags and 0x0010) != 0
        val staticKw = if (isStatic) "static " else ""
        val cleanField = sanitizeFieldName(f.name)
        fieldLines.add("$fVis $staticKw$fType $cleanField;")
      }

      val baseType =
        if (td.extendsToken != 0) {
          val resolvedBase = ctx.resolveTypeToken(td.extendsToken)
          if (resolvedBase != "object" && resolvedBase != "Object" && resolvedBase != "ValueType" && resolvedBase != "Enum" && resolvedBase != "MulticastDelegate") {
            resolvedBase
          } else {
            null
          }
        } else {
          null
        }
      val ifaceNames = ctx.interfaceImpls[td.token]
        .orEmpty()
        .map { ctx.resolveTypeToken(it) }
        .filter { it.isNotBlank() && it != "object" }
        .distinct()
      val heritageTypes = listOfNotNull(baseType) + ifaceNames

      val isInterface = (td.flags and 0x00000020L) != 0L
      val isAbstract = (td.flags and 0x00000080L) != 0L && !isInterface
      val isStaticClass = isAbstract && (td.flags and 0x00000100L) != 0L

      val methodModels = mutableListOf<MonoMethodModel>()
      val methodBlocks = mutableListOf<String>()

      for (mIdx in td.methodStart until nextMethod) {
        val m = ctx.methods.getOrNull(mIdx - 1) ?: continue
        if (m.name.isBlank()) continue

        val nextParamStart = ctx.methods.getOrNull(mIdx)?.paramStart ?: (ctx.params.size + 1)
        val methodParams = mutableListOf<CliParamDef>()
        for (pIdx in m.paramStart until nextParamStart) {
          ctx.params.getOrNull(pIdx - 1)?.let { p ->
            if (p.sequence > 0) methodParams.add(p)
          }
        }

        val sig = ctx.decodeMethodSignature(m.signatureBlobIdx)
        val paramNames = sig.paramTypes.indices.map { pPos ->
          methodParams.firstOrNull { it.sequence == pPos + 1 }?.name?.takeIf { it.isNotBlank() } ?: "arg$pPos"
        }

        val decompiled =
          CilDecompiler.decompileMethod(
            src = src,
            rva = m.rva,
            sections = sections,
            ctx = ctx,
            hasThis = sig.hasThis,
            paramNames = paramNames,
            returnType = sig.returnType,
          )
        methodModels.add(MonoMethodModel(m.name, "0x" + m.rva.toString(16), decompiled.ilDisassembly))

        // Render clean C# signature (fixing invalid `.ctor()` names!)
        val cleanTypeName = td.name.substringBefore('`')
        if (m.name == ".ctor" && decompiled.csharpLines.isEmpty()) {
          continue // Omit compiler-generated empty default constructor
        }
        val mVis = methodVisibility(m.flags)
        val staticKw = if (!sig.hasThis && m.name != ".cctor") "static " else ""
        val paramList = sig.paramTypes.zip(paramNames).joinToString(", ") { (t, n) -> "$t $n" }
        val header =
          when (m.name) {
            ".ctor" -> "$mVis $cleanTypeName($paramList)"
            ".cctor" -> "static $cleanTypeName()"
            else -> "$mVis $staticKw${sig.returnType} ${m.name}($paramList)"
          }
        val block = buildString {
          appendLine("    // RVA: 0x${m.rva.toString(16)}")
          appendLine("    $header")
          appendLine("    {")
          decompiled.csharpLines.forEach { line ->
            appendLine("        $line")
          }
          appendLine("    }")
        }
        methodBlocks.add(block.trimEnd())
      }

      val csharp =
        renderCSharpFile(
          assemblyName = fileName,
          ns = td.namespaceName,
          typeName = td.name.substringBefore('`'),
          isInterface = isInterface,
          isStatic = isStaticClass,
          isAbstract = isAbstract && !isStaticClass,
          heritageTypes = heritageTypes,
          namespaces = ctx.referencedNamespaces,
          fieldLines = fieldLines,
          methodBlocks = methodBlocks,
        )

      types.add(
        MonoTypeModel(
          namespaceName = td.namespaceName,
          typeName = td.name,
          flags = td.flags,
          methods = methodModels,
          fields = fieldNames,
          reconstructedCSharp = csharp,
          warnings = listOf("Decompiled from .NET CLI metadata + CIL bytecode (DECOMPILED_RECONSTRUCTED)."),
        ),
      )
    }

    return MonoAssemblyReport(
      assemblyFileName = fileName,
      validPeCli = true,
      runtimeVersion = ctx.runtimeVersion.ifBlank { "CLR" },
      referencedAssemblies = ctx.assemblyRefs.distinct(),
      types = types,
      warnings = ctx.warnings,
      provenance = DataProvenance.DECOMPILED_RECONSTRUCTED,
      isLibraryAssembly = isLib,
    )
  }

  private fun renderCSharpFile(
    assemblyName: String,
    ns: String,
    typeName: String,
    isInterface: Boolean,
    isStatic: Boolean,
    isAbstract: Boolean,
    heritageTypes: List<String>,
    namespaces: Set<String>,
    fieldLines: List<String>,
    methodBlocks: List<String>,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("// [REU MonoAnalyzer Decompiler — $assemblyName]")
    sb.appendLine("// Provenance: DECOMPILED_RECONSTRUCTED")

    val usings =
      (namespaces + setOf("System", "UnityEngine"))
        .filter { it.isNotBlank() && it != ns }
        .distinct()
        .sorted()
    for (u in usings) {
      sb.appendLine("using $u;")
    }
    sb.appendLine()

    val hasNs = ns.isNotBlank()
    val indent = if (hasNs) "    " else ""
    if (hasNs) {
      sb.appendLine("namespace $ns")
      sb.appendLine("{")
    }

    val kindKw =
      when {
        isInterface -> "interface"
        isStatic -> "static class"
        isAbstract -> "abstract class"
        else -> "class"
      }
    val baseClause = if (heritageTypes.isNotEmpty()) " : ${heritageTypes.joinToString(", ")}" else ""
    sb.appendLine("${indent}public $kindKw $typeName$baseClause")
    sb.appendLine("${indent}{")
    for (f in fieldLines) {
      sb.appendLine("${indent}    $f")
    }
    if (fieldLines.isNotEmpty() && methodBlocks.isNotEmpty()) sb.appendLine()
    for (mb in methodBlocks) {
      mb.lines().forEach { line ->
        sb.appendLine("$indent$line")
      }
      sb.appendLine()
    }
    sb.appendLine("${indent}}")
    if (hasNs) sb.appendLine("}")
    return sb.toString()
  }

  private fun fieldVisibility(flags: Int): String =
    when (flags and 0x07) {
      0x06 -> "public"
      0x04 -> "protected"
      0x03 -> "internal"
      else -> "private"
    }

  private fun methodVisibility(flags: Int): String =
    when (flags and 0x07) {
      0x06 -> "public"
      0x04 -> "protected"
      0x03 -> "internal"
      else -> "private"
    }

  private fun sanitizeFieldName(raw: String): String =
    if (raw.startsWith("<") && raw.contains(">k__BackingField")) {
      raw.substringAfter('<').substringBefore('>')
    } else {
      raw
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

  private fun invalid(fileName: String, msg: String) =
    MonoAssemblyReport(
      assemblyFileName = fileName,
      validPeCli = false,
      runtimeVersion = "",
      referencedAssemblies = emptyList(),
      types = emptyList(),
      warnings = listOf(msg),
      provenance = DataProvenance.MISSING_UNRECOVERABLE,
      isLibraryAssembly = isIncludedLibraryAssembly(fileName),
      errorMessage = msg,
    )

  companion object {
    private val LIBRARY_EXACT =
      setOf(
        "mscorlib.dll",
        "netstandard.dll",
        "newtonsoft.json.dll",
        "riptidenetworking.dll",
        "unityengine.dll",
        "unity.textmeshpro.dll",
        "unity.timeline.dll",
        "unity.mathematics.dll",
        "unity.burst.dll",
        "unity.burst.unsafe.dll",
      )

    private val LIBRARY_PREFIXES =
      listOf(
        "system.",
        "mono.",
        "microsoft.",
        "unityengine.",
        "unity.",
        "nunit.",
        "firebase.",
        "google.",
        "photon",
        "mirror.",
        "facebook.",
        "ironsource",
        "applovin",
      )

    fun isIncludedLibraryAssembly(fileName: String): Boolean {
      val lower = fileName.substringAfterLast('/').lowercase()
      if (lower == "system.dll") return true
      if (lower in LIBRARY_EXACT) return true
      return LIBRARY_PREFIXES.any { lower.startsWith(it) }
    }
  }
}
