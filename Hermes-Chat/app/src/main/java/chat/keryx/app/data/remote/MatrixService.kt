package chat.keryx.app.data.remote

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.login
import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
import net.folivo.trixnity.client.store.repository.room.TrixnityRoomDatabase
import net.folivo.trixnity.client.store.repository.room.createRoomRepositoriesModule
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import okio.Path.Companion.toPath
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Wraps the Trixnity Matrix SDK: client lifecycle, login, session restore, sync.
 *
 * Generalized for any homeserver. TLS is validated by default; [allowInsecure] enables a
 * trust-all engine for power users running local / self-signed servers.
 */
class MatrixService(private val appContext: Context) {

    private val _client = MutableStateFlow<MatrixClient?>(null)
    val client: StateFlow<MatrixClient?> = _client.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Watch the SDK's own login state. Trixnity flips this to LOGGED_OUT_SOFT when the homeserver
     * invalidates our access token mid-session (the "random sign-out"): the client object stays
     * alive but sync is dead, so the room list silently empties. By dropping [_client] on any
     * LOGGED_OUT* / LOCKED state we surface a clean re-login instead of a frozen empty screen,
     * and log the transition so the next occurrence is diagnosable.
     */
    private fun watchLoginState(mc: MatrixClient) {
        scope.launch {
            mc.loginState.collect { state ->
                android.util.Log.i("KeryxAuth", "loginState=$state")
                if (state != null && state != MatrixClient.LoginState.LOGGED_IN) {
                    android.util.Log.w("KeryxAuth", "session ended ($state) — returning to login")
                    if (_client.value === mc) {
                        _client.value = null
                        // Fully tear the dead client down BEFORE the user re-logs in. Otherwise a
                        // second MatrixClient gets created on the same Room database while this one's
                        // sync loop + DB handles are still open — that double-open corrupts the store
                        // and silently drops messages (they show in other clients but not here).
                        teardown(mc)
                    }
                }
            }
        }
    }

    /** Cancel sync and release the client's DB / HTTP resources. Safe to call more than once. */
    private suspend fun teardown(mc: MatrixClient) {
        runCatching { mc.cancelSync() }
        runCatching { mc.close() }
    }

    /** Log sync-state transitions so a stalled/error sync (messages not arriving) is diagnosable. */
    private fun watchSyncState(mc: MatrixClient) {
        scope.launch {
            mc.syncState.collect { android.util.Log.i("KeryxSync", "syncState=$it") }
        }
    }

    private fun repositoriesModule() =
        createRoomRepositoriesModule(
            Room.databaseBuilder<TrixnityRoomDatabase>(
                context = appContext,
                name = appContext.getDatabasePath("trixnity.db").absolutePath,
            )
                // Bundled SQLite (WAL + multi-reader) — the framework driver's pool deadlocks
                // under Trixnity's concurrent store reads during sync.
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
        )

    private fun mediaStoreModule() =
        createOkioMediaStoreModule(
            appContext.filesDir.resolve("trixnity-media").absolutePath.toPath()
        )

    /** Build the ktor engine; trust-all only when [allowInsecure] is set. */
    private fun engine(allowInsecure: Boolean): HttpClientEngine = OkHttp.create {
        if (allowInsecure) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
            config {
                sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
        }
    }

    /** Fresh password login. On success the client is started and exposed via [client]. */
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        deviceDisplayName: String = "Hermes-Chat",
        allowInsecure: Boolean = false,
    ): Result<MatrixClient> = MatrixClient.login(
        baseUrl = Url(normalizeUrl(baseUrl)),
        identifier = IdentifierType.User(username),
        password = password,
        initialDeviceDisplayName = deviceDisplayName,
        repositoriesModule = repositoriesModule(),
        mediaStoreModule = mediaStoreModule(),
    ) {
        httpClientEngine = engine(allowInsecure)
    }.onSuccess { mc ->
        // Close any lingering client first so we never run two on the same DB.
        _client.value?.let { teardown(it) }
        _client.value = mc
        watchLoginState(mc)
        watchSyncState(mc)
        mc.startSync()
    }

    /** Restore a persisted session if one exists; null when there is nothing stored. */
    suspend fun restore(allowInsecure: Boolean = false): MatrixClient? {
        // Idempotent: never start a second client on the same store if one is already live.
        _client.value?.let { return it }
        val mc = MatrixClient.fromStore(
            repositoriesModule = repositoriesModule(),
            mediaStoreModule = mediaStoreModule(),
        ) {
            httpClientEngine = engine(allowInsecure)
        }.getOrNull() ?: return null
        // Lost a race: another caller already installed a client — drop this one.
        if (_client.value != null) { teardown(mc); return _client.value }
        _client.value = mc
        watchLoginState(mc)
        watchSyncState(mc)
        mc.startSync()
        return mc
    }

    suspend fun logout() {
        val mc = _client.value
        _client.value = null
        if (mc != null) {
            runCatching { mc.logout() }
            teardown(mc)
        }
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    }
}
