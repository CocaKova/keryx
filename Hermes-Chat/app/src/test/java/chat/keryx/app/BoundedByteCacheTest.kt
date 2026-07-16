package chat.keryx.app

import chat.keryx.app.util.BoundedByteCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byte-budget invariant behind the 1.19.0 cache bounding: session caches may never grow
 *  past their budget, evict least-recently-used first, and shed on demand. */
class BoundedByteCacheTest {

    private fun bytes(n: Int) = ByteArray(n)

    @Test
    fun budgetIsEnforced_eldestFirst() {
        val cache = BoundedByteCache(maxBytes = 1_000)
        cache.put("a", bytes(400))
        cache.put("b", bytes(400))
        cache.put("c", bytes(400)) // 1200 > 1000 → "a" (eldest) evicted
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertTrue(cache.sizeBytes <= 1_000)
    }

    @Test
    fun accessRefreshesLruOrder() {
        val cache = BoundedByteCache(maxBytes = 1_000)
        cache.put("a", bytes(400))
        cache.put("b", bytes(400))
        cache.get("a") // "a" is now most-recent; "b" becomes the eviction candidate
        cache.put("c", bytes(400))
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun singleOversizePayload_isKeptUntilTheNextPut() {
        val cache = BoundedByteCache(maxBytes = 100)
        cache.put("huge", bytes(500)) // already fetched and in use — keep it
        assertNotNull(cache.get("huge"))
        cache.put("next", bytes(50)) // the oversize entry is the first casualty
        assertNull(cache.get("huge"))
        assertNotNull(cache.get("next"))
    }

    @Test
    fun overwriteSameKey_accountsBytesOnce() {
        val cache = BoundedByteCache(maxBytes = 1_000)
        cache.put("a", bytes(600))
        cache.put("a", bytes(300))
        assertEquals(300L, cache.sizeBytes)
        assertEquals(1, cache.count)
    }

    @Test
    fun trimToBytes_and_clear() {
        val cache = BoundedByteCache(maxBytes = 10_000)
        repeat(10) { cache.put("k$it", bytes(1_000)) }
        cache.trimToBytes(4_000)
        assertTrue(cache.sizeBytes <= 4_000)
        assertNull(cache.get("k0")) // eldest went first
        assertNotNull(cache.get("k9"))
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
        assertEquals(0, cache.count)
    }
}
