# REU — System Architecture

## 1. Overview

**REU (Reverse Engineering Unity)** is a modular, offline-first Android application engineered in Kotlin (Jetpack Compose, Material 3, Room, DataStore, SAF, Coroutines) paired with a C++20 native binary analysis engine (`native/`).

## 2. Module Boundaries

```
:app (MainActivity, ReuApplication, ReuNavShell, Resources, BuildConfig)
 └── :features (12 MVVM Jetpack Compose UI modules + ReuPipelineOrchestrator)
      ├── :integrations (unitypy-adapter, cpp2il-adapter, ilspy-adapter, asset-tools-adapter)
      │    └── :engine (14 specialized binary inspection, extraction & reconstruction engines)
      │         └── :core (core-common, core-storage, core-database, core-logging, core-security, core-jobs)
      └── :core
```

### Core Layer (`:core`)
- `core-common`: `DataProvenance`, `UnityScriptingBackend`, `ImportSourceType`, `BinarySource` (`FileBinarySource` using bounded `RandomAccessFile` seeks without loading large builds into RAM).
- `core-storage`: `WorkspaceManager` (isolated per-project directories, `StatFs` storage checks, SAF display name/size resolution) and `SettingsDataStore` (DataStore Preferences).
- `core-database`: Room SQLite database (`projects`, `analysis_jobs`, `assets`, `code_symbols`, `dependency_edges`, `tool_modules`) with reactive `Flow` DAOs.
- `core-logging`: Ring-buffered `ReuLogger` with per-project log files (`project_<id>_log.txt`).
- `core-security`: `PathTraversalGuard` (Zip-Slip protection) and `IntegrityVerifier` (streaming SHA-256).
- `core-jobs`: `JobCoordinator` with coroutine cancellation and pause/resume checkpoints.

### Engine Layer (`:engine`)
- `apk-inspector`: Streaming APK, XAPK, APKS split APK, ZIP, directory, and single-file inspector.
- `unity-detector`: Evidence-based Unity version, scripting backend (Mono/IL2CPP/Hybrid), ABI, and asset layout detection.
- `elf-analyzer`: ELF32/ELF64 parser for headers, section tables, and exported symbols (`libil2cpp.so`, `libunity.so`).
- `metadata-parser`: IL2CPP `global-metadata.dat` parser (`0xFAB11BAF`, v16–v35 string pool, images, types, methods, fields).
- `mono-analyzer`: .NET PE32/PE32+ CLR CLI (`BSJB` / `#~` / `#Strings`) parser + CIL opcode disassembler.
- `il2cpp-analyzer`: Cross-references ELF + `global-metadata.dat` and generates clearly labeled dummy C# signature stubs.
- `asset-engine`: `UnityFS` header + LZ4 block decompressor + `SerializedFile` object & TypeTree parser.
- `texture-engine`: RGBA / DXT1(BC1) decoder + PNG, JPG, and TGA exporters.
- `audio-engine`: Ogg Vorbis (`OggS`), RIFF WAV, and FMOD `FSB5` container extractor + PCM16 WAV writer.
- `model-engine`: Mesh exporter for Wavefront OBJ and embedded glTF 2.0.
- `scene-engine`: GameObject hierarchy, Materials, Animations, Cameras, Lights, and ScriptableObject summary extractor.
- `dependency-engine`: PPtr reference graph, missing reference detector, and duplicate asset reporter.
- `reconstruction-engine`: Generates canonical `RecoveredProject/` with `Assets/`, `Packages/manifest.json`, `ProjectSettings/`, and `REU_Report/`.
- `export-engine`: Streaming ZIP exporter with SHA-256 verification.

### Native C++20 Engine (`native/`)
- `native/CMakeLists.txt`, `native/include/reu_native.hpp`, `native/src/*.cpp`, `native/jni/reu_jni.cpp`, `native/tests/native_unit_tests.cpp`.
- Automatically compiled via `externalNativeBuild` when Android NDK + CMake are installed; `NativeEngineBridge` provides seamless parity fallback to Kotlin binary parsers when built without NDK.
