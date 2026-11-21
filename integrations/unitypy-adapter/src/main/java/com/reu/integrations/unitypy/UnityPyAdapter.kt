package com.reu.integrations

import com.reu.engine.asset.SerializedFileSummary
import com.reu.engine.asset.UnityAssetParser
import com.reu.engine.asset.UnityFsBundleSummary
import java.io.File

/**
 * JNI bridge to C++20 libreu_native.so when compiled with Android NDK + CMake.
 * Automatically falls back to parity pure-Kotlin binary engines when NDK library is not bundled.
 */
object NativeEngineBridge {
  val isNativeLibraryLoaded: Boolean =
    runCatching {
      System.loadLibrary("reu_native")
      true
    }.getOrDefault(false)

  external fun nativeEngineInfo(): String

  external fun nativeInspectElfJson(path: String): String

  external fun nativeInspectIl2CppMetadataJson(path: String): String

  fun engineStatusSummary(): String =
    if (isNativeLibraryLoaded) {
      runCatching { nativeEngineInfo() }.getOrDefault("REU C++20 Native Engine Loaded")
    } else {
      "Pure-Kotlin Binary Streaming Engine Active (C++20 libreu_native.so compiles when Android NDK + CMake are attached)"
    }
}

class UnityPyAdapter(
  private val assetParser: UnityAssetParser = UnityAssetParser(),
) {
  fun inspectAssetContainer(file: File, relativePath: String): UnityContainerInspection {
    val bundle = assetParser.parseUnityFs(file)
    if (bundle != null) {
      return UnityContainerInspection(
        isBundle = true,
        bundleSummary = bundle,
        serializedSummary = null,
        adapterNote = "Parsed via REU UnityPy-compatible UnityFS reader (MIT format spec).",
      )
    }
    val serialized = assetParser.parseSerializedFile(file, relativePath)
    return UnityContainerInspection(
      isBundle = false,
      bundleSummary = null,
      serializedSummary = serialized,
      adapterNote = "Parsed via REU UnityPy-compatible SerializedFile reader.",
    )
  }
}

data class UnityContainerInspection(
  val isBundle: Boolean,
  val bundleSummary: UnityFsBundleSummary?,
  val serializedSummary: SerializedFileSummary?,
  val adapterNote: String,
)
