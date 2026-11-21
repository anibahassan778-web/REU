# REU — Third-Party Licenses & Attribution

## 1. Bundled Android & Kotlin Libraries

| Library | License | Usage |
|---|---|---|
| AndroidX Core KTX, Activity Compose, DocumentFile, WorkManager, Navigation, Room, DataStore | Apache License 2.0 | Android framework, SAF, SQLite ORM, background jobs, navigation, preferences |
| Jetpack Compose & Material 3 | Apache License 2.0 | Declarative UI & Material 3 components |
| Kotlin Standard Library, Coroutines, Serialization | Apache License 2.0 | Language runtime, async streaming, JSON |
| OkHttp 4.10.0 | Apache License 2.0 | Optional Tool Manager manifest fetch |
| Coil Compose 2.7.0 | Apache License 2.0 | Image preview rendering |
| Google Fonts (`Space Grotesk`, `JetBrains Mono`, `Cairo`) | SIL Open Font License 1.1 | Bundled UI, monospace, and Arabic typography |
| JUnit 4 / Robolectric | EPL-1.0 / MIT | Automated unit and JVM Android tests |

## 2. Clean-Room Format Specification Attribution

REU implements independent, clean-room Kotlin and C++20 binary parsers compatible with public Unity serialization formats and interchange outputs pioneered by the following open-source projects (none of their desktop binaries or GPL code are linked into the APK):

- **UnityPy** — MIT License (UnityFS & SerializedFile structure reference)
- **Cpp2IL** — MIT License (IL2CPP metadata analysis concepts)
- **Il2CppDumper** — MIT License (`global-metadata.dat` layout & Ghidra `script.json` interchange schema)
- **ILSpy / ICSharpCode.Decompiler** — MIT License (.NET CLI `#~` metadata & CIL disassembly reference)
- **UABEA / AssetStudio** — MIT License (Unity asset extraction & OBJ/glTF interchange concepts)
- **Ghidra** — Apache License 2.0 (Optional `ghidra_script.json` symbol export format)
