# REU — Compatibility Matrix & Known Limitations

## 1. Android Platform & Architecture

| Target | Support Level | Notes |
|---|---|---|
| Android 8.0+ (API 26–36) | Full Support | `minSdk` configured at 24 for dependency compatibility; zero storage permissions required via SAF |
| `arm64-v8a` | Primary | Pure-Kotlin binary engine + C++20 JNI target (`abiFilters`) |
| `armeabi-v7a` | Secondary | Supported in C++20 CMake `abiFilters` and Kotlin engine |

## 2. Import Formats

| Format | Status | Notes |
|---|---|---|
| `.apk` | Supported | Streaming ZipInputStream + Zip-Slip protection + package name hint |
| `.apks` / Split APKs | Supported | Multi-APK selection and nested `.apk` split extraction |
| `.xapk` | Supported | Extracts embedded `.apk` splits + `manifest.json` metadata |
| `.zip` | Supported | Safe bounded archive extraction |
| Extracted Game Directory | Supported | Recursive `DocumentFile` SAF tree scan |
| Individual Unity Files | Supported | Direct `.so`, `.dat`, `.dll`, `.assets`, `.bundle` import |

## 3. Code & Asset Recovery Reality Matrix

| Capability | Actual Recovery Level | Honest Limitation |
|---|---|---|
| Mono `.dll` (`.NET` PE/CLI) | TypeDefs, Fields, MethodDefs, AssemblyRefs, CIL Disassembly (`DECOMPILED_RECONSTRUCTED`) | Produces structural C# outlines + CIL instruction disassembly; full high-level decompilation of complex async/LINQ closures requires desktop ILSpy. |
| IL2CPP (`global-metadata.dat` + `libil2cpp.so`) | Metadata version, images, type names, namespaces, method signatures, field names (`DECOMPILED_RECONSTRUCTED`) | Original C# method bodies are compiled to native ARM machine code and are **not** recoverable verbatim (`MISSING_UNRECOVERABLE`). Encrypted/custom-obfuscated metadata is detected and reported explicitly. |
| Textures (`Texture2D`) | RGBA32, ARGB32, DXT1/BC1 decoding; PNG, JPG, TGA export | Platform-crunched/ASTC/ETC2 variants requiring external native codec packs are indexed accurately without faking pixels. |
| Audio (`AudioClip`) | Ogg Vorbis (`OggS`), RIFF WAV, FMOD `FSB5` detection & extraction | Native FSB5 sub-streams remain in original `.fsb5` container unless PCM16-decoded. |
| 3D Models (`Mesh`) | Wavefront `.obj`, embedded `glTF 2.0` (`.gltf`) | **FBX is not supported** because no verified open-source Android FBX SDK exists. |
| Unity Project Rebuild | Valid `RecoveredProject/` folder hierarchy + `manifest.json` + `REU_Report/` | Android does not host Unity Editor; exported projects must be opened in Unity Editor on desktop, where IL2CPP dummy stubs require manual re-implementation. |
