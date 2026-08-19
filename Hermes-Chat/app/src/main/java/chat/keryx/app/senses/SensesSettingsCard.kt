package chat.keryx.app.senses

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.app.presentation.ui.components.KeryxSectionHeader
import chat.keryx.app.presentation.ui.components.SettingsSwitchRow

/**
 * Settings → "Senses" (2.3 §4). Three opt-in switches, the last-sent line, and the one promise
 * that makes the feature acceptable at all: this data never travels on its own.
 *
 * Reads and writes [SensesPrefs] directly — Senses owns its own preferences file, so this card
 * needs neither the ChatViewModel nor SettingsRepository. Drop it into any settings column.
 */
@Composable
fun SensesSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { SensesPrefs(context) }

    var batteryOn by remember { mutableStateOf(prefs.batteryEnabled) }
    var timeOn by remember { mutableStateOf(prefs.timeEnabled) }
    var placeOn by remember { mutableStateOf(prefs.placeEnabled) }
    var lastSent by remember { mutableLongStateOf(prefs.lastSentAnywhere()) }

    // The marker is written on the send path, not here, so the "last sent" line has to hear about
    // it from the file itself.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(SensesPrefs.PREFIX_LAST_SENT)) {
                lastSent = prefs.lastSentAnywhere()
            }
        }
        prefs.addChangeListener(listener)
        onDispose { prefs.removeChangeListener(listener) }
    }

    val placePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // A denial leaves the switch exactly where it was: off.
        prefs.placeEnabled = granted
        placeOn = granted
    }

    SensesCard(modifier) {
        SettingsSwitchRow(
            title = "Battery",
            subtitle = "Charge level and whether you're plugged in",
            checked = batteryOn,
            onCheckedChange = { batteryOn = it; prefs.batteryEnabled = it },
        )
        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            title = "Local time",
            subtitle = "The clock and time zone you're actually standing in",
            checked = timeOn,
            onCheckedChange = { timeOn = it; prefs.timeEnabled = it },
        )
        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            title = "Coarse place",
            subtitle = "Town-level only, rounded to about a kilometre",
            checked = placeOn,
            onCheckedChange = { wanted ->
                if (!wanted) {
                    placeOn = false
                    prefs.placeEnabled = false
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        placeOn = true
                        prefs.placeEnabled = true
                    } else {
                        // Stays off until the system dialog says otherwise.
                        placePermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Last sent: " + KeryxSenses.lastSentLabel(System.currentTimeMillis(), lastSent),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (batteryOn || timeOn || placeOn) {
            Spacer(Modifier.height(8.dp))
            // Senses has no "send" of its own — the marker only ever rides an outgoing message —
            // so the honest "now" is to drop the throttle and let the next one carry it.
            TextButton(
                onClick = { prefs.clearThrottle(); lastSent = prefs.lastSentAnywhere() },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text("Send with my next message", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Rides inside your own messages, end-to-end encrypted. " +
                "Nothing leaves the phone until you send.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/**
 * The settings-group card, matched to `SettingsDialog`'s private `SettingsCard` — same section
 * header, radius, surface wash and hairline. Copied rather than shared because that one is
 * file-private; if it ever goes internal, delete this.
 */
@Composable
private fun SensesCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        KeryxSectionHeader(
            "Senses",
            modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(KeryxRadius.card),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}
