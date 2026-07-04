import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// Release signing comes from local.properties (never committed):
//   keryx.keystore=/absolute/path/to/release.keystore
//   keryx.keystore.password=…
//   keryx.key.alias=…
//   keryx.key.password=…
// Absent those, release builds fall back to the debug keystore (sideload/dev convenience).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeystorePath: String? = localProps.getProperty("keryx.keystore")

android {
    namespace = "chat.keryx.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "chat.keryx.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.3"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = localProps.getProperty("keryx.keystore.password")
                keyAlias = localProps.getProperty("keryx.key.alias")
                keyPassword = localProps.getProperty("keryx.key.password")
            }
        }
    }

    buildTypes {
        release {
            // Minification stays OFF until the R8 keeps in proguard-rules.pro have been verified
            // on-device (Trixnity + kotlinx-serialization are reflection-heavy; an untested
            // minified build is worse than a slightly larger honest one).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Matrix SDK (Trixnity) — client + Android Room persistence + media store
  implementation(libs.trixnity.client)
  implementation(libs.trixnity.client.repository.room)
  implementation(libs.trixnity.client.media.okio)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.androidx.sqlite.bundled)
  implementation(libs.markdown.renderer.m3)

  // Room (Removed - Migrating to Matrix Server)

  // OkHttp & SSE
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)

  // Serialization
  implementation(libs.kotlinx.serialization.json)
}
