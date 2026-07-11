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
 * Glue between the UnifiedPush distributor and the Matrix pusher list.
 *
 * enable(): pick a distributor and request an endpoint (async — the distributor answers through
 * [KeryxPushService.onNewEndpoint], which lands in [onNewEndpoint] here and registers the HTTP
 * pusher on the homeserver, pointed at the user's push gateway with `event_id_only` format).
 * disable(): unregister both sides. Everything is best-effort and logged; the in-process sync
 * notifications remain the fallback tier whenever push isn't active.
 */
object PushManager {

    const val APP_ID = "chat.keryx.app.android"
    private val scope = CoroutineScope(Dispatchers.IO)

    sealed interface EnableResult {
        data object Requested : EnableResult
        data object NoDistributor : EnableResult
    }

    /** Kick off registration. Returns [EnableResult.NoDistributor] when no UnifiedPush-capable
     *  app (ntfy, Sunup, …) is installed — callers surface that as "using in-app sync". */
    fun enable(context: Context): EnableResult {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) return EnableResult.NoDistributor
        // Keep a previously chosen distributor; otherwise take the first available one.
        val distributor = UnifiedPush.getSavedDistributor(context) ?: distributors.first()
        UnifiedPush.saveDistributor(context, distributor)
        UnifiedPush.register(context, INSTANCE, null, null)
        return EnableResult.Requested
    }

    /** Tear down: distributor registration, homeserver pusher, stored endpoint. */
    fun disable(context: Context) {
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
