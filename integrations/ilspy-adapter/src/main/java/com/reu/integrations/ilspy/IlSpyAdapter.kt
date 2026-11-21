package com.reu.integrations.ilspy

import com.reu.engine.mono.MonoAnalyzer
import com.reu.engine.mono.MonoAssemblyReport
import java.io.File

/**
 * ILSpy-compatible adapter for inspecting .NET assemblies on Android without requiring
 * a desktop Windows/Avalonia CLR host.
 */
class IlSpyAdapter(
  private val monoAnalyzer: MonoAnalyzer = MonoAnalyzer(),
) {
  fun inspectManagedAssembly(dllFile: File): MonoAssemblyReport {
    return monoAnalyzer.analyzeAssembly(dllFile)
  }

  fun summarizeCompatibility(): String =
    "ILSpy Desktop WPF/Avalonia GUI cannot execute natively on Android; REU IlSpyAdapter uses " +
      "a clean-room PE32/PE32+ #~ CLI metadata parser and CIL bytecode disassembler."
}
