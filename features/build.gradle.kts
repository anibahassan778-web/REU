plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.reu.features"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
  }

  sourceSets {
    getByName("main") {
      kotlin.directories.addAll(
        listOf(
          "dashboard/src/main/java",
          "importer/src/main/java",
          "inspector/src/main/java",
          "analysis/src/main/java",
          "code-explorer/src/main/java",
          "asset-browser/src/main/java",
          "scene-explorer/src/main/java",
          "il2cpp-lab/src/main/java",
          "tool-manager/src/main/java",
          "project-rebuilder/src/main/java",
          "export-center/src/main/java",
          "settings/src/main/java",
        ),
      )
    }
  }
}

dependencies {
  api(project(":core"))
  api(project(":engine"))
  api(project(":integrations"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.coil.compose)
}
