package chat.keryx.app.util

/**
 * Byte-budgeted, access-ordered LRU for raw payloads (media/avatar bytes). The session-lifetime
 * `Map<String, ByteArray>` caches it replaces grew without bound — every image ever viewed stayed
 * in memory until the app died (a direct driver of the 514 MB marathon RSS). Pure Kotlin so the
 * budget/eviction rules are JVM-testable; coarse-grained synchronization is plenty for a cache
 * touched a few times per rendered bubble.
 */
class BoundedByteCache(private val maxBytes: Long) {

    private val map = LinkedHashMap<String, ByteArray>(32, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: String): ByteArray? = map[key]

    @Synchronized
    fun put(key: String, value: ByteArray) {
        map.remove(key)?.let { bytes -= it.size }
        map[key] = value
        bytes += value.size
        // Evict eldest-first until within budget. A single over-budget payload is kept (it was
        // already fetched and is in use) — it just becomes the first casualty of the next put.
        val it = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.value === value) continue
            bytes -= eldest.value.size
            it.remove()
        }
    }

    @Synchronized
    fun trimToBytes(target: Long) {
        val it = map.entries.iterator()
        while (bytes > target && it.hasNext()) {
            bytes -= it.next().value.size
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0L
    }

    @get:Synchronized
    val sizeBytes: Long get() = bytes

    @get:Synchronized
    val count: Int get() = map.size
}

/**
 * Process-wide roll call of trimmable caches, driven by Application.onTrimMemory: under memory
 * pressure every registered cache sheds weight instead of riding the app into the OOM killer.
 * Everything cached here is re-fetchable (media re-downloads, thumbnails re-decode), so trimming
 * costs at most a reload flash. Long-lived owners (process singletons) may register and forget;
 * anything shorter-lived (a ViewModel) must [unregister] when cleared or the registry pins it.
 */
object CacheRegistry {

    private val trimmers = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun register(trimmer: (aggressive: Boolean) -> Unit) {
        trimmers.addIfAbsent(trimmer)
    }

    fun unregister(trimmer: (aggressive: Boolean) -> Unit) {
        trimmers.remove(trimmer)
    }

    /** [aggressive] = the app is backgrounded or the system is desperate: drop everything.
     *  Otherwise shed roughly half — enough to matter, cheap to rebuild. */
    fun trimAll(aggressive: Boolean) {
        for (t in trimmers) {
            runCatching { t(aggressive) }
        }
    }
}
