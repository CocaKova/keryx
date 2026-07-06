package chat.keryx.app.presentation.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True while the device is in Battery Saver. Decorative always-on animations (the braille snake,
 * breathing glows) check this and hold a static frame instead — each one is an infinite
 * frame-clock client that keeps the GPU compositing at 60fps for pure ornament, which is exactly
 * what a user who flipped Battery Saver on asked us not to do. Live-updates via the system
 * broadcast, so flipping the toggle mid-session takes effect immediately.
 */
@Composable
fun rememberReducedMotion(): State<Boolean> {
    val context = LocalContext.current
    val pm = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val saver = remember { mutableStateOf(pm?.isPowerSaveMode == true) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                saver.value = pm?.isPowerSaveMode == true
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saver
}
