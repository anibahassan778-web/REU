# REU — Environment Audit Report (Phase 0)

## 1. Host & Toolchain Summary

| Component | Detected Value | Status |
|---|---|---|
| Operating System | Linux `4.19.0-gvisor` (`amd64`) | Ready |
| Java Runtime / JDK | OpenJDK `21.0.13+9-LTS` (Temurin) | Ready |
| Gradle | `9.3.1` (Groovy `4.0.29`, Kotlin DSL `2.2.21`) | Ready |
| Android Gradle Plugin (AGP) | `9.1.1` (built-in Kotlin support) | Ready |
| Kotlin / KSP | Kotlin `2.4.21` / KSP `2.3.5` | Ready |
| Android SDK Root | `/opt/android/sdk` | Ready |
| Android Platforms | `platforms;android-36`, `platforms;android-36.1` | Ready |
| Build Tools | `build-tools;36.0.0` | Ready |
| Platform Tools | `platform-tools` (`37.0.1`) | Ready |
| Android NDK | **Not installed** (`/opt/android/sdk/ndk` missing) | Limitation |
| CMake | **Not installed** (`/opt/android/sdk/cmake` & `cmake` missing) | Limitation |
| Host C++ Toolchain | **Not installed** (`clang++` / `g++` absent) | Limitation |
| ImageMagick (`convert`) | `/usr/bin/convert` | Ready |
| Python 3 | `/usr/bin/python3` | Ready |
| Available Disk Space | `756G` available on `/` | Ready |

## 2. Existing Repository Inspection

- Single starter module `:app` using Jetpack Compose BOM `2024.09.00`, Material 3, Room `2.7.0` (with KSP), Moshi `1.15.2`, OkHttp `4.10.0`, Retrofit `2.12.0`, Robolectric `4.16.1`, and JUnit `4.13.2`.
- `settings.gradle.kts` includes `:app` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (`google()`, `mavenCentral()`).
- `gradle.properties` sets `-Xmx4g`, `org.gradle.parallel=true`, `org.gradle.configuration-cache=true`, `kotlin.compiler.execution.strategy=in-process`, and `googleServices.missing.passthrough=true`.
- Signing configs (`debugConfig` and `release`) are pre-configured in `app/build.gradle.kts` and must remain untouched.

## 3. Identified Build & Execution Limitations

1. **Missing Android NDK & CMake in Build Container**:
   - `externalNativeBuild` with CMake cannot compile `.so` shared libraries inside this container because `ndk` and `cmake` are not installed in `/opt/android/sdk`, and host `g++`/`clang++` are absent.
   - **Mitigation Strategy**:
     - Author the full C++20 native engine (`native/CMakeLists.txt`, `native/include/`, `native/src/`, `native/jni/`, `native/tests/`) for NDK r27+ / CMake 3.22.1+.
     - Configure `app/build.gradle.kts` to detect NDK + CMake availability dynamically: when present (e.g., in Android Studio or an NDK CI image), Gradle builds `libreu_native.so` for `arm64-v8a` and `armeabi-v7a`; when absent, Gradle skips `externalNativeBuild` cleanly and the Kotlin/JVM binary engine provides full runtime parity via `NativeEngineBridge`.
2. **No Android Emulator / ADB in Container**:
   - Connected instrumentation tests (`connectedAndroidTest`) cannot execute here.
   - **Mitigation Strategy**: Comprehensive JUnit 4 unit tests, binary fixture integration tests, and Robolectric (`@Config(sdk = [36])`) Compose UI & WorkManager tests executed via `gradle :app:testDebugUnitTest`.
3. **Desktop-Only Third-Party Reverse-Engineering Tools**:
   - Tools such as ILSpy (desktop WPF/.NET), AssetStudio (.NET Windows/Desktop), UABEA (C++/Avalonia), Ghidra (desktop JVM + Swing), and Blender cannot execute natively as desktop binaries inside an Android APK.
   - **Mitigation Strategy**: Implement verified Kotlin/C++ binary parsers and adapters (`integrations/*-adapter`) for Unity `SerializedFile`, `UnityFS`, `global-metadata.dat`, ELF64/ELF32, and .NET PE/CLI metadata, and clearly classify capabilities, outputs, and unsupported desktop-only operations in `COMPATIBILITY.md`.
