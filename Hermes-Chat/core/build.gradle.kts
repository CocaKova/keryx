// :core — the spine both transports stand on.
//
// Everything in commonMain compiles against the common stdlib only, so the COMPILER enforces
// what a naming convention cannot: no android.*, no java.*, no Trixnity, no OkHttp. A model or a
// parser that lands here is one the Matrix path and the direct path can both use, and the build
// fails the moment someone reaches for a platform type to make one of them easier.
//
// Targets: jvm (what the Android app consumes) plus the iOS pair, which build only on a macOS
// host — on Linux they are declared and simply never asked for, so the jvm half still builds.
plugins {
    // No version: AGP 9 already owns the Kotlin plugin classpath; stating one here fails with
    // "already on the classpath with an unknown version".
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: Flow and Json appear in this module's public API, so
            // consumers need them on their own compile classpath.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            // ISO instants for cron humanization (CronHumanize.nextIn) — the KMP-standard
            // clock library; Talaria's shared half already stood on it.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
