package chat.keryx.app.presentation.ui.components

import android.content.Context
import android.content.Intent

/**
 * Relaunch the app cleanly into a fresh process — how a transport-door change takes effect
 * (the spine is built at startup and is not hot-swappable under a live ViewModel).
 *
 * Callers persist their state with a SYNCHRONOUS commit first ([SettingsRepository
 * .commitTransportDoor]/[commitTransportMode]): apply()'s async disk write loses the race
 * against the exit below — device-caught on the first direct-door walk.
 *
 * The door keys aren't the only writes in flight, though: ANY setting toggled this session
 * (haptics, text scale, drafts, …) still rides apply(), and a door crossing right after
 * would silently revert it. So before exiting, barrier the whole settings file to disk with
 * one synchronous commit — getSharedPreferences returns the same per-file singleton the
 * repository holds, so this commit carries every pending in-memory edit with it.
 */
@Suppress("ApplySharedPref") // synchronous ON PURPOSE — the process dies on the next line.
fun relaunchApp(context: Context) {
    context.getSharedPreferences(
        chat.keryx.app.data.repository.SettingsRepositoryImpl.PREFS_FILE,
        Context.MODE_PRIVATE,
    ).edit().putLong("relaunch_flush_barrier", System.currentTimeMillis()).commit()
    val i = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(i)
    Runtime.getRuntime().exit(0)
}
