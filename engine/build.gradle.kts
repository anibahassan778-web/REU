plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.reu.engine"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  sourceSets {
    getByName("main") {
      kotlin.directories.addAll(
        listOf(
          "unity-detector/src/main/java",
          "apk-inspector/src/main/java",
          "mono-analyzer/src/main/java",
          "il2cpp-analyzer/src/main/java",
          "elf-analyzer/src/main/java",
          "metadata-parser/src/main/java",
          "asset-engine/src/main/java",
          "texture-engine/src/main/java",
          "audio-engine/src/main/java",
          "model-engine/src/main/java",
          "scene-engine/src/main/java",
          "dependency-engine/src/main/java",
          "reconstruction-engine/src/main/java",
          "export-engine/src/main/java",
        ),
      )
    }
  }
}

dependencies {
  api(project(":core"))
}
