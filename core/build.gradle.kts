plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.reu.core"
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
          "core-common/src/main/java",
          "core-storage/src/main/java",
          "core-database/src/main/java",
          "core-logging/src/main/java",
          "core-security/src/main/java",
          "core-jobs/src/main/java",
        ),
      )
    }
  }
}

dependencies {
  api(libs.androidx.core.ktx)
  api(libs.androidx.documentfile)
  api(libs.androidx.datastore.preferences)
  api(libs.androidx.room.runtime)
  api(libs.androidx.room.ktx)
  api(libs.androidx.work.runtime.ktx)
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.coroutines.android)
  api(libs.kotlinx.serialization.json)
  api(libs.okhttp)
  "ksp"(libs.androidx.room.compiler)
}
