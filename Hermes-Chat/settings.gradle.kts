pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Build guard. A host that names a build host (~/.config/keryx/build-host) has said "I delegate
// builds" -- typically because something else owns its memory (here: a local model server), and
// a Gradle run (two JVMs, ~5 GB peak) would push it over and take that down. So on such a
// host, refuse to build unless there is real headroom, and point at the hand-off instead.
// The build host itself has no such file, so nothing here fires there or in CI.
// Override, knowingly: KERYX_BUILD_HERE=1. Threshold: KERYX_BUILD_MIN_AVAIL_GIB (default 24).
run {
    val hostFile = File(
        System.getenv("KERYX_BUILD_HOST_FILE")
            ?: "${System.getProperty("user.home")}/.config/keryx/build-host"
    )
    if (!hostFile.isFile || System.getenv("KERYX_BUILD_HERE") == "1") return@run
    val availKb = File("/proc/meminfo").takeIf { it.canRead() }?.useLines { lines ->
        lines.firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
    } ?: return@run
    val minGib = System.getenv("KERYX_BUILD_MIN_AVAIL_GIB")?.toLongOrNull() ?: 24L
    val availGib = availKb / 1048576
    if (availGib < minGib) {
        throw GradleException(
            "Refusing to build here: ${availGib} GiB available, need ${minGib}. This host delegates " +
                "builds to ${hostFile.readText().trim()} (${hostFile}). Run tools/ship.sh from the " +
                "repo root -- it hands off by itself and runs the tests there. " +
                "Do not retry gradlew here. Override only with the model server stopped: KERYX_BUILD_HERE=1."
        )
    }
}

rootProject.name = "HermesChat"
include(":app")
include(":core")
