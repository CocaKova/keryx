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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// FragmentActivity (not ComponentActivity): androidx BiometricPrompt can only attach to one.
class MainActivity : androidx.fragment.app.FragmentActivity() {

    private lateinit var viewModel: ChatViewModel

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    // ── Biometric app lock ─────────────────────────────────────────────────
    // The Settings toggle used to be a stub — it saved a preference nothing read. The activity
    // now locks at cold start and whenever the app returns after RELOCK_AFTER_MS in the
    // background; content is replaced (not overlaid) by the lock screen while locked.
    private val locked = androidx.compose.runtime.mutableStateOf(false)
    private var lastStoppedAt = 0L
    private var unlockedOnce = false

    private fun lockAvailable(): Boolean =
        androidx.biometric.BiometricManager.from(this).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

    private fun promptUnlock() {
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    unlockedOnce = true
                    locked.value = false
                }
                // Error/cancel keeps the lock screen up; its Unlock button re-prompts.
            },
        )
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Keryx")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(info)
    }

    override fun onStart() {
        super.onStart()
        val enabled = (application as KeryxApp).settingsRepository.biometricLockEnabled
        // No enrolled biometrics AND no device credential → never lock, or the user is walled out.
        if (!enabled || !lockAvailable()) { locked.value = false; return }
        val awayMs = System.currentTimeMillis() - lastStoppedAt
        if (!unlockedOnce || awayMs > RELOCK_AFTER_MS) {
            locked.value = true
            promptUnlock()
        }
    }

    override fun onStop() {
        lastStoppedAt = System.currentTimeMillis()
        super.onStop()
    }

    private companion object {
        /** Returning within this window skips re-auth (quick app switches stay fluid). */
        const val RELOCK_AFTER_MS = 60_000L
    }

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
                    // While locked, the chat is REPLACED (not overlaid): nothing renders
                    // underneath, so content can't flash or leak into the app switcher.
                    val isLocked by locked
                    if (isLocked) LockScreen(onUnlock = { promptUnlock() })
                    else HermesApp(viewModel = viewModel)
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

/** Shown INSTEAD of the app while the biometric lock is engaged. The system sheet appears on
 *  top automatically; this is what's visible behind it and after a cancel. */
@androidx.compose.runtime.Composable
private fun LockScreen(onUnlock: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        chat.keryx.app.presentation.ui.components.KeryxWordmark(fontSize = 34.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Text(
            "Locked",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(28.dp))
        androidx.compose.material3.OutlinedButton(onClick = onUnlock) {
            androidx.compose.material3.Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text("Unlock")
        }
    }
}
