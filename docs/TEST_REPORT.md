# REU — Test & Verification Report (Phase 8)

## 1. Execution Summary

| Verification Step | Command / Tool | Result | Executed |
|---|---|---|---|
| Full Android Build | `compile_applet` | **Passed** (`Build succeeded - the applet is compiled`) | Yes |
| JVM Unit + Integration + Robolectric UI Suite | `gradle :app:testDebugUnitTest` | **13 / 13 Passed** (0 failed, 0 skipped) | Yes |
| C++20 Native Engine Unit Tests | `native/tests/native_unit_tests.cpp` via CMake / `ctest` | **Not Executed in Container** (NDK, CMake, and host `clang++`/`g++` absent) | No |
| On-Device Android Instrumentation Tests | `gradle :app:connectedAndroidTest` | **Not Executed in Container** (No Android Emulator / ADB in container) | No |

---

## 2. Verified JVM & Robolectric Test Suites (`gradle :app:testDebugUnitTest`)

Executed across `:app`, `:core`, `:engine`, `:integrations`, and `:features` on OpenJDK `21.0.13+9-LTS`, Gradle `9.3.1`, AGP `9.1.1`, and Robolectric `4.16.1` (`sdk = 36`).

### 2.1 Security & Core Tests (`com.reu.tests.unit.ReuSecurityAndCoreTests`)
- **Result:** 2 / 2 passed
- **Cases:**
  1. `integrity verifier produces standard SHA-256 digest` — Verifies streaming SHA-256 hashing matches known NIST digest vectors (`abc` -> `ba7816bf...`).
  2. `path traversal guard blocks Zip-Slip and allows safe paths` — Verifies `../` traversal, absolute paths, and null-byte names are rejected while normalized safe archive entries succeed.

### 2.2 Binary Reverse-Engineering Engine Unit Tests (`com.reu.tests.unit.ReuBinaryEngineTests`)
- **Result:** 4 / 4 passed
- **Cases:**
  1. `elf analyzer parses ELF64 AArch64 header sections and exported symbols` — Constructs a synthetic ELF64 `EM_AARCH64` `ET_DYN` shared object with `.shstrtab`, `.dynstr`, and `.dynsym` (`il2cpp_init`) and verifies architecture, section headers, and symbol extraction.
  2. `global metadata parser and il2cpp analyzer extract types methods and fields` — Constructs a binary `global-metadata.dat` v29 image (`0xFAB11BAF`) and verifies namespace/type/method/field table decoding, generated `PlayerController` dummy C# stub output, and explicit limitation reporting.
  3. `mono analyzer parses PE32 CLR CLI header and disassembles CIL opcodes` — Constructs a valid PE32 binary with `.text`, CLR CLI header, `BSJB` metadata root, `#~` and `#Strings` streams, and tiny-format CIL body (`ldarg.0`, `ret`), verifying `DECOMPILED_RECONSTRUCTED` provenance, CIL opcode disassembly, and `MonoAnalyzer.isIncludedLibraryAssembly(...)` filtering (`Mono.Security.dll`, `Newtonsoft.Json.dll`, `UnityEngine.*`, `System.*` excluded; game assemblies retained).
  4. `unity asset parser audio model and texture engines export valid files` — Verifies pure-Kotlin LZ4 block decompression, `UnityFs` v7 unpacking (`blocksInfoNeedPadding 0x200`), `ETC2_RGBA8` PNG decoding via pure-Kotlin RGBA PNG writer, PCM16 `RIFF` WAV & FSB5 PCM16-to-WAV export, Wavefront `.obj` and embedded `glTF 2.0` mesh export, and `DXT1/BC1` 4x4 RGBA block decoding.

### 2.3 End-to-End Pipeline & Integration Tests (`com.reu.tests.integration.ReuPipelineAndRebuilderTests`)
- **Result:** 4 / 4 passed
- **Cases:**
  1. `end to end APK inspection blocks zip slip detects Unity IL2CPP rebuilds project and exports zip` — Builds an in-memory Unity IL2CPP APK containing `lib/arm64-v8a/libil2cpp.so`, `lib/arm64-v8a/libunity.so`, `assets/bin/Data/Managed/Metadata/global-metadata.dat`, `assets/bin/Data/globalgamemanagers`, and a malicious `../../evil.txt` Zip-Slip entry; verifies Zip-Slip rejection, IL2CPP + Unity detection, ELF/metadata analysis, `RecoveredProject/` reconstruction (`Assets/Scripts/`, `ProjectSettings/`, `Packages/manifest.json`, `REU_Report/` provenance reports), and exportable ZIP generation with SHA-256 verification.
  2. `tool module manager enforces SHA-256 verification and rejects executable DEX packages` — Seeds the Room-backed tool catalog and verifies that mismatched SHA-256 archives and any archive containing `.dex`, `.jar`, or `.so` executable payloads are rejected to enforce Google Play policy compliance.
  3. `code-only rebuild omits assets and excludes included library assemblies` — Verifies `RebuildMode.CODE_ONLY` writes only `Scripts/<GameAssembly>/<Type>.cs`, omits `Newtonsoft.Json.dll` / `Mono.Security.dll` and all assets/audio/`Assets/`/`Packages/`/`ProjectSettings/`.
  4. `full project extraction unpacks UnityFS bundle and exports textures sprites audio fonts shaders and scenes` — Constructs a synthetic APK with a `UnityFS` `data.unity3d` bundle containing a `SerializedFile` v22 scene (`level0`) with `Texture2D` (RGBA32), `Sprite`, `AudioClip` (PCM16), `GameObject` + `Transform`, `Shader` (`Sprites/Default`), `Font` (valid SFNT `.ttf`), `Assembly-CSharp.dll`, and `Newtonsoft.Json.dll`; verifies `UnityFullAssetExtractor` + `ProjectRebuilder(RebuildMode.FULL_PROJECT)` exports valid PNG, cropped Sprite PNG, RIFF WAV Audio, TTF Font, named ShaderLab file, `.unity` YAML scene, and game-only C# scripts with `missingOrPlaceholderCount == 0`.

### 2.4 Robolectric Compose UI & Localization Tests (`com.example.ExampleRobolectricTest` & `com.example.ExampleUnitTest`)
- **Result:** 3 / 3 passed (`14.202s` combined)
- **Cases:**
  1. `ExampleRobolectricTest > verify REU app name in English and Arabic resources` — Verifies `"REU"` branding and Arabic resource lookup (`"لوحة التحكم"`).
  2. `ExampleRobolectricTest > verify navigation across core screens and Arabic RTL switch` — Launches `MainActivity`, navigates across Dashboard (`dashboard_screen`), Importer (`importer_screen`), Analysis (`analysis_screen`), and Settings (`settings_screen`), clicks the `lang_arabic` chip (`العربية (RTL)`), and verifies reactive DataStore update switches the `TopAppBar` title (`topbar_title`) to `"الإعدادات"` and section headers to Arabic (`"اللغة والاتجاه (RTL / LTR)"`).
  3. `ExampleUnitTest > addition_isCorrect` — Baseline JUnit sanity test.

---

## 3. Defects Caught and Fixed During Verification

1. **DXT1/BC1 Spec Edge Case in Unit Vector:** Initial DXT1 test vector filled all 8 bytes with `0xFF` (`c0 == c1 == 0xFFFF`, selector `0b11` = code `3`), which per DXT1 spec decodes to transparent black `(0,0,0,0)`. Confirmed `TextureEngine.decodeDxt1ToRgba` implements DXT1 transparency correctly and updated the test fixture to a non-transparent white block (`c0 = 0xFFFF, c1 = 0x0000`, selector `0x00000000`).
2. **Narrow-Screen Chip Clipping in `SettingsScreen`:** On Robolectric's default 320dp viewport, placing `"System"`, `"English (LTR)"`, and `"العربية (RTL)"` inside a single non-wrapping `Row` pushed `lang_arabic` near/past the edge. Replaced `Row` with `FlowRow` in `SettingsScreen.kt` for both language and I/O buffer chips so all options wrap cleanly and remain clickable across compact screens.
3. **Ambiguous Semantics Selector in `ReuNavShell`:** `ModalNavigationDrawer` composes destination labels offscreen alongside `TopAppBar`, causing `onNodeWithText("الإعدادات")` to match 2 nodes. Added `Modifier.testTag("topbar_title")` to the `TopAppBar` title in `ReuNavShell.kt` and asserted both `topbar_title` and the unique Arabic section label in `ExampleRobolectricTest`.

---

## 4. Reproducible Native C++20 & On-Device Commands (Outside Container)

When building on a workstation or CI runner with **Android NDK (r27+)** and **CMake (3.22.1+)**:

```bash
# 1. Run host C++20 native parser unit tests
cmake -S native -B build-native-tests
cmake --build build-native-tests
ctest --test-dir build-native-tests --output-on-failure

# 2. Build Android APK including libreu_native.so (arm64-v8a & armeabi-v7a)
gradle :app:assembleDebug

# 3. Run unit + Robolectric tests
gradle :app:testDebugUnitTest

# 4. Run on-device instrumentation tests (requires connected emulator/device)
gradle :app:connectedAndroidTest
```
