package chat.keryx.app.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import chat.keryx.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The look and the personal switches (split out of [ChatViewModel] in 3.0): theme and accent
 * colours, herald accents, bubble style and text size, motion and haptics, the lock, E2EE,
 * telemetry, and the Matrix account fields Settings edits. Each is a StateFlow seeded from
 * settings and written back on change; nothing else in the app derives from them here.
 */
class PreferencesDelegate(private val settings: SettingsRepository) {
    private val _isDarkTheme = MutableStateFlow<Boolean?>(null) // null = system default
    val isDarkTheme: StateFlow<Boolean?> = _isDarkTheme.asStateFlow()

    private val _accentColor = MutableStateFlow<Color>(
        try {
            Color(android.graphics.Color.parseColor(settings.accentColorHex))
        } catch (e: Exception) {
            Color(0xFFE55A00)
        }
    )
    val accentColor: StateFlow<Color> = _accentColor.asStateFlow()

    private val _accentColor2 = MutableStateFlow<Color>(
        try {
            Color(android.graphics.Color.parseColor(settings.accentColor2Hex))
        } catch (e: Exception) {
            Color(0xFF8B5CF6)
        }
    )
    val accentColor2: StateFlow<Color> = _accentColor2.asStateFlow()

    private val _matrixUrl = MutableStateFlow(settings.homeserverUrl)
    val matrixUrl: StateFlow<String> = _matrixUrl.asStateFlow()

    private val _agentMatrixId = MutableStateFlow(settings.agentMatrixId)
    val agentMatrixId: StateFlow<String> = _agentMatrixId.asStateFlow()

    /** Per-herald accent overrides (localpart -> "#RRGGBB"). Empty entries fall back to the
     *  derived council palette (2.3 §1). */
    private val _heraldAccents = MutableStateFlow(settings.heraldAccents)
    val heraldAccents: StateFlow<Map<String, String>> = _heraldAccents.asStateFlow()

    private val _matrixToken = MutableStateFlow(settings.matrixToken)
    val matrixToken: StateFlow<String> = _matrixToken.asStateFlow()

    private val _biometricLock = MutableStateFlow(settings.biometricLockEnabled)
    val biometricLock: StateFlow<Boolean> = _biometricLock.asStateFlow()

    private val _e2eeEnabled = MutableStateFlow(settings.e2eeEnabled)
    val e2eeEnabled: StateFlow<Boolean> = _e2eeEnabled.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(settings.hapticsEnabled)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _animationStyle = MutableStateFlow(settings.animationStyle)
    val animationStyle: StateFlow<String> = _animationStyle.asStateFlow()

    private val _bubbleStyle = MutableStateFlow(settings.bubbleStyle)
    val bubbleStyle: StateFlow<String> = _bubbleStyle.asStateFlow()

    private val _messageTextScale = MutableStateFlow(settings.messageTextScale)
    val messageTextScale: StateFlow<Float> = _messageTextScale.asStateFlow()

    private val _roomTransition = MutableStateFlow(
        chat.keryx.app.presentation.ui.components.RoomTransition.of(settings.roomTransition)
    )
    /** How the transcript crosses rooms (3.0, Appearance). */
    val roomTransition: StateFlow<chat.keryx.app.presentation.ui.components.RoomTransition> = _roomTransition.asStateFlow()

    private val _showTelemetry = MutableStateFlow(settings.showTelemetry)
    val showTelemetry: StateFlow<Boolean> = _showTelemetry.asStateFlow()

    fun toggleTheme(isDark: Boolean?) {
        _isDarkTheme.value = isDark
    }

    fun setAccentColor(color: Color) {
        _accentColor.value = color
        settings.accentColorHex = String.format("#%06X", (0xFFFFFF and color.toArgb()))
    }

    fun setAccentColor2(color: Color) {
        _accentColor2.value = color
        settings.accentColor2Hex = String.format("#%06X", (0xFFFFFF and color.toArgb()))
    }

    fun setMatrixUrl(url: String) {
        _matrixUrl.value = url
        settings.homeserverUrl = url
    }

    fun setAgentMatrixId(id: String) {
        _agentMatrixId.value = id
        settings.agentMatrixId = id
    }

    /** Set (or, with a null [hex], clear) one herald's accent override. */
    fun setHeraldAccent(localpart: String, hex: String?) {
        val key = localpart.trim().lowercase()
        if (key.isEmpty()) return
        val next = _heraldAccents.value.toMutableMap()
        if (hex.isNullOrBlank()) next.remove(key) else next[key] = hex
        _heraldAccents.value = next
        settings.heraldAccents = next
    }

    fun setMatrixToken(token: String) {
        _matrixToken.value = token
        settings.matrixToken = token
    }

    fun setBiometricLock(enabled: Boolean) {
        _biometricLock.value = enabled
        settings.biometricLockEnabled = enabled
    }

    fun setE2eeEnabled(enabled: Boolean) {
        _e2eeEnabled.value = enabled
        settings.e2eeEnabled = enabled
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _hapticsEnabled.value = enabled
        settings.hapticsEnabled = enabled
    }

    fun setAnimationStyle(style: String) {
        _animationStyle.value = style
        settings.animationStyle = style
    }

    fun setBubbleStyle(style: String) {
        _bubbleStyle.value = style
        settings.bubbleStyle = style
    }

    fun setRoomTransition(style: chat.keryx.app.presentation.ui.components.RoomTransition) {
        _roomTransition.value = style
        settings.roomTransition = style.key
    }

    fun setMessageTextScale(scale: Float) {
        _messageTextScale.value = scale
        settings.messageTextScale = scale
    }

    /** Restore default message appearance: gradient bubbles, default accent, default text size. */
    fun resetMessageAppearance() {
        setBubbleStyle("Gradient")
        setMessageTextScale(1.0f)
        setAccentColor(Color(0xFFE55A00))
        setAccentColor2(Color(0xFF8B5CF6))
    }

    fun setShowTelemetry(enabled: Boolean) {
        _showTelemetry.value = enabled
        settings.showTelemetry = enabled
    }
}
