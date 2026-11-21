# REU — Dependency & Third-Party License Audit (Phase 0)

## 1. Android & Kotlin Dependencies (In-APK)

| Dependency | Version | Purpose | License | Status |
|---|---|---|---|---|
| AndroidX Core KTX | `1.18.0` | SAF, file/system extensions | Apache-2.0 | Pre-configured |
| Jetpack Compose BOM | `2024.09.00` | Material 3 UI toolkit | Apache-2.0 | Pre-configured |
| AndroidX Navigation Compose | `2.8.9` | Screen routing & backstack | Apache-2.0 | Pre-configured (uncomment) |
| AndroidX Lifecycle / ViewModel | `2.8.7` | MVVM state flows | Apache-2.0 | Pre-configured |
| AndroidX Room + KSP | `2.7.0` | Local SQLite workspace database | Apache-2.0 | Pre-configured |
| AndroidX DataStore Preferences | `1.1.7` | Language, theme, settings storage | Apache-2.0 | Pre-configured (uncomment) |
| AndroidX WorkManager (`work-runtime-ktx`) | `2.10.0` | Background cancellable extraction jobs | Apache-2.0 | To add to catalog |
| Kotlinx Coroutines | `1.10.2` | Streaming async I/O & Flow | Apache-2.0 | Pre-configured |
| Kotlinx Serialization JSON | `1.8.0` | Manifests, reports, typed navigation | Apache-2.0 | To add to catalog |
| OkHttp | `4.10.0` | Hybrid module manifest/package download | Apache-2.0 | Pre-configured |
| Coil Compose | `2.7.0` | Extracted texture preview loading | Apache-2.0 | Pre-configured (uncomment) |
| JUnit 4 / Robolectric / Coroutines Test | `4.13.2` / `4.16.1` / `1.10.2` | JVM + Android framework tests | EPL-1.0 / MIT / Apache-2.0 | Pre-configured |

**Unused Template Dependencies Disabled for APK Optimization**:
- `firebase-ai`, `firebase-appcheck-*`, `retrofit`, `converter-moshi`, `moshi-kotlin`, `logging-interceptor` (REU is offline-first with Kotlinx Serialization + OkHttp; removing unused heavy SDKs minimizes APK size and build time).

## 2. Reverse-Engineering Ecosystem Compatibility Audit

| External Tool | Official License | Runtime Platform | Can Run Natively on Android? | REU Integration Strategy |
|---|---|---|---|---|
| **UnityPy** | MIT | Python 3 + C extensions (`lz4`, `etcpak`, `texture2ddecoder`) | Not directly without bundling CPython | **Native/Kotlin Adapter (`unitypy-adapter`)**: Pure Kotlin/C++20 `UnityFS`, `SerializedFile`, LZ4/LZMA-compatible & texture block decoders following UnityPy format specs. |
| **Cpp2IL** | MIT | .NET 8/9 CLR | Requires .NET host runtime | **Adapter (`cpp2il-adapter`)**: Native C++/Kotlin `global-metadata.dat` + ELF64/32 parser generating dummy C# types, methods, attributes & signature reports. |
| **ILSpy / ICSharpCode.Decompiler** | MIT | .NET CLR (Avalonia/WPF UI) | Desktop .NET runtime required | **Adapter (`ilspy-adapter`)**: Pure Kotlin/C++ PE32/PE32+ CLI (`#~` metadata stream) parser + CIL bytecode disassembler & structured C# signature/skeleton reconstructor. |
| **Il2CppDumper** | MIT | .NET CLR | Desktop .NET runtime | Format compatibility for `global-metadata.dat` versions 24–31 + ELF symbol/registration table scanning. |
| **AssetRipper** | GPL-3.0 | .NET 9 | Desktop .NET runtime (Note: GPL-3.0 copyleft incompatibility if statically linked into Apache/MIT closed builds) | **Clean-room Adapter (`asset-tools-adapter`)**: Independent MIT/Apache-compatible Kotlin/C++ project structure & YAML/JSON asset exporter inspired by Unity serialization specs; zero GPL code copied. |
| **UABEA** | MIT | C++ / C# (.NET + Avalonia) | Desktop GUI | **Clean-room Adapter**: Streaming `SerializedFile` & `UnityFS` reader/extractor without desktop GUI dependencies. |
| **AssetStudio** | MIT | .NET Framework / .NET | Windows/Desktop | **Clean-room Adapter**: Mesh OBJ/glTF, Texture2D, AudioClip & Scene hierarchy extraction. |
| **Ghidra** | Apache-2.0 | Java Desktop (Swing + C++ SLEIGH decompiler) | Desktop JVM (too heavy for mobile UI; headless SLEIGH requires native C++ build) | **Reference/Export Integration**: Export ELF section & IL2CPP method RVA maps (`script.json`-compatible) for optional desktop Ghidra import; local ELF disassembly/symbol summary in-app. |
| **Blender** | GPL-3.0 | Desktop C/C++/Python + OpenGL | Desktop | **Interchange Strategy**: Export clean **Wavefront OBJ** and **glTF 2.0 (`.gltf` + `.bin`)** directly on Android, ready for Blender import. FBX is NOT claimed because no verified native Android FBX SDK exists under open licensing. |

## 3. Permissions & Play Policy Compliance

- **Zero Broad Storage Permissions**: Uses Android Storage Access Framework (`OpenDocument`, `OpenMultipleDocuments`, `OpenDocumentTree`, `CreateDocument`) instead of `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`.
- **Network**: `INTERNET` declared solely for user-initiated Tool Manager manifest checks and SHA-256-verified optional module downloads.
- **No Dynamic Code Execution**: Downloaded tool packs are declarative JSON/lookup/TypeTree/signature data packs verified by SHA-256 checksums — never `.dex`, `.jar`, or `.so` dynamic code loading (`DCL`).
