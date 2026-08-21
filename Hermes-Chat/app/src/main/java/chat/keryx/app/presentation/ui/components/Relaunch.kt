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
 */
fun relaunchApp(context: Context) {
    val i = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(i)
    Runtime.getRuntime().exit(0)
}
