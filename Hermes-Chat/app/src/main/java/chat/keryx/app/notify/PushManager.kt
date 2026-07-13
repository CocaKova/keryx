package chat.keryx.app.notify

import android.content.Context
import chat.keryx.app.KeryxApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.folivo.trixnity.clientserverapi.model.push.PusherData
import net.folivo.trixnity.clientserverapi.model.push.SetPushers
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Glue between a push transport and the Matrix pusher list. Two transports, one funnel:
 *
 *  - **Distributor**: a UnifiedPush app (ntfy, Sunup, …) is installed — pick it and request an
 *    endpoint (async — the distributor answers through [KeryxPushService.onNewEndpoint], which
 *    lands in [onNewEndpoint] here and registers the HTTP pusher on the homeserver, pointed at
 *    the user's push gateway with `event_id_only` format).
 *  - **Built-in**: no distributor installed — Keryx acts as its own. It mints a private ntfy
 *    topic, registers the pusher with the endpoint a distributor would have issued
 *    (`<gateway>/<topic>?up=1` — ntfy's native UnifiedPush shape), and [BuiltinPushService]
 *    holds the subscription. Push never requires a companion app; an installed distributor is
 *    simply the auto-detected upgrade.
 *
 * disable(): unregister whichever side is active. Everything is best-effort and logged; the
 * in-process sync notifications remain the fallback tier whenever push isn't active.
 */
object PushManager {

    const val APP_ID = "chat.keryx.app.android"
    private val scope = CoroutineScope(Dispatchers.IO)

    sealed interface EnableResult {
        /** Distributor picked; endpoint arrives async via [KeryxPushService.onNewEndpoint]. */
        data object Requested : EnableResult
        /** No distributor app — Keryx's own connection to the push gateway is active. */
        data object BuiltinActive : EnableResult
        /** Built-in mode needs the push gateway URL and none is configured. */
        data object NoGateway : EnableResult
    }

    /** Kick off registration on the best available transport (distributor if installed, else the
     *  built-in connection). Returns [EnableResult.NoGateway] when neither can deliver. */
    fun enable(context: Context): EnableResult {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isNotEmpty()) {
            // Distributor app present: it owns delivery — the built-in connection stands down.
            BuiltinPushService.stop(context)
            // Keep a previously chosen distributor; otherwise take the first available one.
            val distributor = UnifiedPush.getSavedDistributor(context) ?: distributors.first()
            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context, INSTANCE, null, null)
            return EnableResult.Requested
        }
        val app = context.applicationContext as? KeryxApp ?: return EnableResult.NoGateway
        val settings = app.settingsRepository
        val gateway = settings.pushGatewayUrl.trim().trimEnd('/')
        if (gateway.isBlank()) return EnableResult.NoGateway
        // Same endpoint a distributor would have issued for an ntfy server; the pusher side of the
        // pipe can't tell the difference.
        onNewEndpoint(context, "$gateway/${builtinTopic(settings)}?up=1")
        BuiltinPushService.start(context)
        return EnableResult.BuiltinActive
    }

    /** True when built-in mode should be running (push on, gateway set, no distributor app) —
     *  the boot/app-update receiver's restart condition. */
    fun builtinModeWanted(context: Context): Boolean {
        val app = context.applicationContext as? KeryxApp ?: return false
        val settings = app.settingsRepository
        return settings.pushEnabled &&
            settings.pushGatewayUrl.isNotBlank() &&
            UnifiedPush.getDistributors(context).isEmpty()
    }

    /** The private ntfy topic this install subscribes to, minted once. Random suffix: topic names
     *  are the only auth on a default ntfy server, so it must be unguessable. */
    fun builtinTopic(settings: chat.keryx.app.domain.repository.SettingsRepository): String =
        settings.builtinPushTopic.ifBlank {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val rng = java.security.SecureRandom()
            val suffix = (1..20).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
            "keryx-$suffix".also { settings.builtinPushTopic = it }
        }

    /** Tear down: built-in connection, distributor registration, homeserver pusher, endpoint. */
    fun disable(context: Context) {
        BuiltinPushService.stop(context)
        runCatching { UnifiedPush.unregister(context, INSTANCE) }
        val app = context.applicationContext as? KeryxApp ?: return
        val endpoint = app.settingsRepository.pushEndpoint
        app.settingsRepository.pushEndpoint = ""
        if (endpoint.isBlank()) return
        scope.launch {
            val client = app.matrixService.client.value ?: return@launch
            client.api.push.setPushers(SetPushers.Request.Remove(APP_ID, endpoint))
                .onSuccess { android.util.Log.i("KeryxPush", "pusher removed") }
                .onFailure { android.util.Log.w("KeryxPush", "pusher remove failed: ${it.message}") }
        }
    }

    /** Distributor issued (or rotated) our endpoint: (re)register the homeserver pusher. */
    fun onNewEndpoint(context: Context, endpoint: String) {
        val app = context.applicationContext as? KeryxApp ?: return
        val settings = app.settingsRepository
        if (!settings.pushEnabled) return
        val gateway = settings.pushGatewayUrl.trim().trimEnd('/')
        if (gateway.isBlank()) {
            android.util.Log.w("KeryxPush", "endpoint received but no push gateway URL configured")
            return
        }
        val stale = settings.pushEndpoint
        settings.pushEndpoint = endpoint
        scope.launch {
            runCatching { app.matrixService.restore(allowInsecure = settings.allowInsecure) }
            val client = app.matrixService.client.value ?: run {
                android.util.Log.w("KeryxPush", "no Matrix client; pusher not registered")
                return@launch
            }
            // A rotated endpoint leaves the old pusher orphaned on the homeserver — drop it.
            if (stale.isNotBlank() && stale != endpoint) {
                client.api.push.setPushers(SetPushers.Request.Remove(APP_ID, stale))
            }
            client.api.push.setPushers(
                SetPushers.Request.Set(
                    appId = APP_ID,
                    pushkey = endpoint,
                    kind = "http",
                    appDisplayName = "Keryx",
                    deviceDisplayName = android.os.Build.MODEL ?: "Android",
                    lang = "en",
                    data = PusherData(
                        // event_id_only: ids ride the push, content comes from our own sync —
                        // works identically for E2EE rooms and keeps message text off ntfy.
                        format = "event_id_only",
                        url = "$gateway/_matrix/push/v1/notify",
                    ),
                    // Replace any previous registration with this pushkey instead of appending
                    // per-device duplicates.
                    append = false,
                ),
            )
                .onSuccess { android.util.Log.i("KeryxPush", "pusher registered at $gateway") }
                .onFailure { android.util.Log.w("KeryxPush", "pusher register failed: ${it.message}") }
        }
    }

    /** Distributor dropped us (uninstalled, failed registration): reflect reality in settings. */
    fun onRegistrationGone(context: Context) {
        val app = context.applicationContext as? KeryxApp ?: return
        app.settingsRepository.pushEndpoint = ""
    }

    /** Single-account app: one UnifiedPush instance is enough. */
    private const val INSTANCE = "default"
}
