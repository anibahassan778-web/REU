# REU — Build & Architecture Plan (Phase 0)

## 1. Module Topology & Dependency Boundaries

To enforce strict separation of concerns while remaining fast and compatible with AGP 9.1.1 built-in Kotlin:

- **Gradle Subprojects**:
  - `:core` (`com.android.library`) — source roots mapped to:
    - `core/core-common/src/main/java` (Result types, byte/hex utilities, provenance classification)
    - `core/core-storage/src/main/java` (SAF URI resolution, bounded streaming I/O, workspace layout)
    - `core/core-database/src/main/java` (Room entities, DAOs, database, repositories)
    - `core/core-logging/src/main/java` (Structured diagnostic logger & persistent log buffer)
    - `core/core-security/src/main/java` (Zip-slip path traversal guard, SHA-256 streaming hasher, manifest signature/checksum verification)
    - `core/core-jobs/src/main/java` (Cancellable job coordinator, progress state models)
  - `:engine` (`com.android.library`, depends on `:core`) — source roots mapped to:
    - `engine/apk-inspector` (Streaming APK/APKS/XAPK/ZIP/directory inspector)
    - `engine/unity-detector` (Evidence-based Unity version, Mono vs IL2CPP, ABI, asset layout detection)
    - `engine/elf-analyzer` (ELF32/ELF64 headers, sections, program headers, dynamic symbols, `.rodata` string scanning)
    - `engine/metadata-parser` (IL2CPP `global-metadata.dat` header, version, string/type/method/field tables)
    - `engine/mono-analyzer` (.NET PE/COFF/CLI header & `#~` metadata stream inspection, managed type/method recovery)
    - `engine/il2cpp-analyzer` (Combined ELF + `global-metadata.dat` cross-reference, dummy C# signature reconstruction)
    - `engine/asset-engine` (`UnityFS` AssetBundle & `SerializedFile` TypeTree/object table parser)
    - `engine/texture-engine` (Texture2D decoder & PNG/JPG/TGA exporters)
    - `engine/audio-engine` (AudioClip / FMOD FSB5 / OGG / WAV header & payload extractor)
    - `engine/model-engine` (Mesh vertex/index decoder & Wavefront OBJ / glTF 2.0 exporters)
    - `engine/scene-engine` (GameObject, Transform, Component, Material, AnimationClip, ScriptableObject inspector)
    - `engine/dependency-engine` (Asset dependency graph, missing/duplicate/broken reference detector)
    - `engine/reconstruction-engine` (Valid `RecoveredProject/` generator + `manifest.json` + `REU_Report/`)
    - `engine/export-engine` (Safe ZIP exporter & SAF directory exporter)
  - `:integrations` (`com.android.library`, depends on `:core`, `:engine`) — source roots mapped to:
    - `integrations/unitypy-adapter` (UnityPy-compatible AssetBundle / SerializedFile inspection adapter + JNI bridge)
    - `integrations/cpp2il-adapter` (Cpp2IL-compatible metadata & dummy assembly recovery adapter)
    - `integrations/ilspy-adapter` (.NET CIL metadata & IL opcode disassembler / structured C# skeleton adapter)
    - `integrations/asset-tools-adapter` (AssetRipper / UABEA / AssetStudio / Il2CppDumper format compatibility & interchange adapter)
  - `:features` (`com.android.library` + Jetpack Compose, depends on `:core`, `:engine`, `:integrations`) — source roots mapped to all 12 screens:
    - `dashboard`, `importer`, `inspector`, `analysis`, `code-explorer`, `asset-browser`, `scene-explorer`, `il2cpp-lab`, `tool-manager`, `project-rebuilder`, `export-center`, `settings`.
  - `:app` (`com.android.application`, depends on `:core`, `:engine`, `:integrations`, `:features`) — `ReuApplication`, `MainActivity`, Navigation Compose graph, WorkManager workers, app icons, bilingual EN/AR resources (`values/` & `values-ar/`), and tests.
- **Native C++20 Engine (`native/`)**:
  - `native/CMakeLists.txt`, `native/include/*.hpp`, `native/src/*.cpp`, `native/jni/reu_jni.cpp`, `native/tests/*.cpp`.
  - Automatically linked into `:app` when Android NDK + CMake are present.

## 2. Phase Gate Sequence

- **Phase 0**: Environment, Dependency, and Build Plan audits (`ENVIRONMENT_REPORT.md`, `BUILD_PLAN.md`, `DEPENDENCY_AUDIT.md`).
- **Phase 1**: Multi-module Gradle setup, C++20 CMake/JNI scaffold, Room database, DataStore settings, structured logging, security guards, custom app icon & fonts, EN/AR localization, Navigation Compose.
- **Phase 2**: Premium dark desktop-inspired UI across all 12 screens + Compose/Navigation Robolectric tests.
- **Phase 3**: SAF Importer + Path Traversal Guard + SHA-256 + APK/APKS/XAPK/ZIP/Folder + Unity Build Detector + ELF Inspector + binary fixture tests.
- **Phase 4**: Real .NET PE/CLI Mono Analyzer + IL2CPP `global-metadata.dat` + ELF cross-referencer + CIL/stub recovery + unit tests.
- **Phase 5**: `UnityFS` & `SerializedFile` parser + Texture (PNG/JPG/TGA), Audio (WAV/OGG), Mesh (OBJ/glTF), Material, Animation, Scene, ScriptableObject extractors & dependency graph + tests.
- **Phase 6**: `RecoveredProject/` Unity Project Rebuilder + `REU_Report/*.json` + ZIP/SAF export + structure validation tests.
- **Phase 7**: Signed/SHA-256-verified Tool Manager, offline package installer, license registry + integration tests.
- **Phase 8**: Streaming memory bounds, WorkManager cancellation, crash/storage handling, security & localization regression tests, release APK build & final documentation.
