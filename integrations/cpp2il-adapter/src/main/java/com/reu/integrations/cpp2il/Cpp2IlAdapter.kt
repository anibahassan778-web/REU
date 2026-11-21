package com.reu.integrations.cpp2il

import com.reu.engine.il2cpp.Il2CppAnalyzer
import com.reu.engine.il2cpp.Il2CppPipelineReport
import com.reu.integrations.NativeEngineBridge
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Cpp2IL & Il2CppDumper compatible adapter for IL2CPP metadata extraction,
 * dummy C# signature export, and Ghidra script.json symbol export.
 */
class Cpp2IlAdapter(
  private val analyzer: Il2CppAnalyzer = Il2CppAnalyzer(),
) {
  private val json = Json { prettyPrint = true }

  fun runPipeline(metadataFile: File, libIl2CppSo: File?): Il2CppPipelineReport {
    return analyzer.analyze(metadataFile, libIl2CppSo)
  }

  fun exportGhidraSymbolJson(report: Il2CppPipelineReport, outputFile: File): File {
    outputFile.parentFile?.mkdirs()
    val payload =
      buildMap {
        put("engine", NativeEngineBridge.engineStatusSummary())
        put("metadataVersion", report.metadataReport.metadataVersion.toString())
        put("typeCount", report.metadataReport.typeDefinitionCount.toString())
        put("methodCount", report.metadataReport.methodCount.toString())
        put("elfArch", report.elfReport?.machineArch ?: "Unknown")
      }
    val scriptCompat =
      buildString {
        appendLine("{")
        appendLine("  \"generator\": \"REU Cpp2IlAdapter (Ghidra/Il2CppDumper compatible)\",")
        appendLine("  \"metadataSummary\": ${json.encodeToString(payload)},")
        appendLine("  \"ScriptMethod\": [")
        val methods = report.metadataReport.methods.take(250)
        methods.forEachIndexed { idx, m ->
          val comma = if (idx < methods.lastIndex) "," else ""
          appendLine("    { \"Name\": \"${m.name.replace("\"", "\\\"")}\", \"Token\": \"0x${m.token.toString(16)}\", \"ParamCount\": ${m.parameterCount} }$comma")
        }
        appendLine("  ]")
        appendLine("}")
      }
    outputFile.writeText(scriptCompat)
    return outputFile
  }
}
