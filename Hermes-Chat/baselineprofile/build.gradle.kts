// The macrobenchmark module (3.0 perf §4): records the app's Baseline Profile and times its cold
// start. It drives an installed build from the shell, so it never ships and the ship gate never
// builds it. tools/baseline_profile.sh runs it; see docs/PERF-BASELINE.md.
plugins {
  alias(libs.plugins.android.test)
}

android {
  namespace = "chat.keryx.baselineprofile"
  compileSdk = 37
  defaultConfig {
    // BaselineProfileRule needs API 33 on an unrooted device; the startup benchmark runs from 28.
    minSdk = 28
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  targetProjectPath = ":app"
  // The test APK instruments itself and drives chat.keryx.app through the shell, so the two
  // need not share a signing key.
  experimentalProperties["android.experimental.self-instrumenting"] = true
  // One build type per app build it measures: profileGen (no R8) to record the profile,
  // benchmark (R8, like release) to time cold start.
  buildTypes {
    create("profileGen") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
    create("benchmark") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
  implementation("androidx.test.uiautomator:uiautomator:2.4.0")
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.runner)
}
