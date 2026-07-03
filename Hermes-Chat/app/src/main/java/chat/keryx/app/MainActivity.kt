package chat.keryx.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import chat.keryx.app.notify.KeryxNotifications
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chat.keryx.app.data.remote.MatrixService
import chat.keryx.app.data.repository.ChatRepositoryImpl
import chat.keryx.app.data.repository.SettingsRepositoryImpl
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.HermesApp
import chat.keryx.app.theme.HermesChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ChatViewModel

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shared, process-wide instances (created once in KeryxApp). Creating a new MatrixService
        // per activity used to start a second Matrix client on the same DB on every config change.
        val app = application as KeryxApp
        val settingsRepository = app.settingsRepository
        val repository = app.repository

        // Ask once to be exempt from battery optimization. Without this, aggressive OEM power
        // management (Samsung's "deep sleep" / Freecess) freezes the process in the background and
        // kills the Matrix sync, so messages don't arrive until you reopen the app. Granting it lets
        // sync keep running with no persistent notification.
        maybeRequestBatteryExemption(settingsRepository)

        // Ask for notification permission on Android 13+ (no-op on older versions).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(repository, settingsRepository) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        // Keep KeryxApp's "currently open room" in sync so we don't notify for the room on screen,
        // and clear that room's notification when it's opened.
        lifecycleScope.launch {
            viewModel.currentSession.collectLatest { session ->
                app.openRoomId = session?.id
                session?.id?.let { KeryxNotifications.clear(applicationContext, it) }
            }
        }

        // A notification tap delivers the room id; open it.
        handleNotificationIntent(intent)

        enableEdgeToEdge()
        setContent {
            val isDarkOverride by viewModel.isDarkTheme.collectAsState()
            val useDarkTheme = isDarkOverride ?: isSystemInDarkTheme()
            val currentAccent by viewModel.accentColor.collectAsState()
            val currentAccent2 by viewModel.accentColor2.collectAsState()

            // Keep the status-bar icons legible: light glyphs on our dark (OLED-black) background,
            // dark glyphs in light mode. Without this the clock/battery vanish into the black bar.
            val view = LocalView.current
            SideEffect {
                val window = (view.context as android.app.Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
            }

            HermesChatTheme(darkTheme = useDarkTheme, customAccent = currentAccent, customAccent2 = currentAccent2) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HermesApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /** If launched/resumed from a message notification, open the room it points at. */
    private fun handleNotificationIntent(intent: Intent?) {
        val roomId = intent?.getStringExtra(KeryxNotifications.EXTRA_ROOM_ID) ?: return
        if (::viewModel.isInitialized) viewModel.openRoomById(roomId)
        KeryxNotifications.clear(applicationContext, roomId)
    }

    /** One-time system prompt to whitelist the app from battery optimization (keeps sync alive). */
    private fun maybeRequestBatteryExemption(settings: SettingsRepositoryImpl) {
        if (settings.batteryPromptShown) return
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) { settings.batteryPromptShown = true; return }
        settings.batteryPromptShown = true
        runCatching {
            @android.annotation.SuppressLint("BatteryLife")
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
    }
}
