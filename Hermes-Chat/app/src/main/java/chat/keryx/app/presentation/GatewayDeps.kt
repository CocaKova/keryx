package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope

/**
 * What every gateway-feature delegate needs and nothing more: the ViewModel's scope, the
 * settings store, the two client factories, and the toast lane. One instance, shared.
 *
 * [client] is the snapshot-caching client ([SettingsRepository.putHubSnapshot] rides along so
 * hub panels can seed offline); [bareClient] is the plain one for endpoints whose answers
 * shouldn't be cached as panel snapshots. Both return null while Hermes Link is off or
 * unconfigured — callers surface that, not this.
 */
class GatewayDeps(
    val scope: CoroutineScope,
    val settings: SettingsRepository,
    val client: () -> HermesStreamClient?,
    val bareClient: () -> HermesStreamClient?,
    val toast: (String) -> Unit,
)
