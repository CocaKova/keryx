package chat.keryx.app

import android.app.Application
import chat.keryx.app.data.remote.MatrixService
import chat.keryx.app.data.repository.ChatRepositoryImpl
import chat.keryx.app.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Process-wide singletons. The Matrix client MUST live here, not in MainActivity: the activity is
 * recreated on every configuration change (theme/accent toggle, rotation, …) and a per-activity
 * client meant a new MatrixClient — and a new sync loop — was started on the SAME database each
 * time. Multiple Trixnity clients sharing one store fight over the sync token and corrupt it, which
 * showed up as messages silently going missing / not arriving. One client, restored once, fixes it.
 */
class KeryxApp : Application() {

    lateinit var settingsRepository: SettingsRepositoryImpl
        private set
    lateinit var matrixService: MatrixService
        private set
    lateinit var repository: ChatRepositoryImpl
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepositoryImpl(applicationContext)
        matrixService = MatrixService(applicationContext)
        repository = ChatRepositoryImpl(matrixService, settingsRepository)

        // Restore an existing Matrix session exactly once for the whole process.
        appScope.launch {
            runCatching { matrixService.restore(allowInsecure = settingsRepository.allowInsecure) }
        }
    }
}
