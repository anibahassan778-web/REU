plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.reu.integrations"
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
          "unitypy-adapter/src/main/java",
          "cpp2il-adapter/src/main/java",
          "ilspy-adapter/src/main/java",
          "asset-tools-adapter/src/main/java",
        ),
      )
    }
  }
}

dependencies {
  api(project(":core"))
  api(project(":engine"))
}
