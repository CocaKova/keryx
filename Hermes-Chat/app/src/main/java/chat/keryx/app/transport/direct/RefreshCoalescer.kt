package chat.keryx.app.transport.direct

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * At most one [refresh] per floor, however many times [poke] is called (2.13.10).
 *
 * The gateway's `sessions.changed` fires on every store write a running turn makes, and each
 * roster refresh is two list pages — measured idle, one refetch a minute came to ~16 MB/h. A
 * poke that lands mid-floor is remembered and served once the floor ends: the trailing edge
 * is kept, nothing is dropped. On screen the floor is [foregroundFloorMs]; off screen it is
 * [backgroundFloorMs], but coming back on screen ends it at once, so the drawer is never
 * stale for longer than one round trip after the user looks.
 */
class RefreshCoalescer(
    private val scope: CoroutineScope,
    private val foreground: StateFlow<Boolean>,
    private val foregroundFloorMs: Long,
    private val backgroundFloorMs: Long,
    private val refresh: suspend () -> Unit,
) {
    private val dirty = AtomicBoolean(false)
    private var job: Job? = null

    fun poke() {
        dirty.set(true)
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch {
                while (dirty.getAndSet(false)) {
                    refresh()
                    if (foreground.value) delay(foregroundFloorMs)
                    else withTimeoutOrNull(backgroundFloorMs) { foreground.first { it } }
                }
            }
        }
    }
}
