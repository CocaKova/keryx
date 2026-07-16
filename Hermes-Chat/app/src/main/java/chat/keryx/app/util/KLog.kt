package chat.keryx.app.util

import chat.keryx.app.BuildConfig

/**
 * Debug-gated info logging for HOT paths (per-emission, per-SSE-event, per-typing-change). The
 * lambda form means release builds skip the log string's construction entirely — belt-and-braces
 * with the R8 `-assumenosideeffects` Log rule, and still effective if a release ever ships with
 * minification reverted. Warnings/errors stay on plain android.util.Log everywhere: they're rare
 * and wanted in release logcat (CrashLog triage).
 */
object KLog {
    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) android.util.Log.i(tag, message())
    }

    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) android.util.Log.d(tag, message())
    }
}
