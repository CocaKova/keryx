package chat.keryx.app

import chat.keryx.app.transport.direct.RefreshCoalescer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoalescerTest {

    @Test
    fun `a burst on screen is one refresh now and one trailing refresh after the floor`() = runTest {
        var calls = 0
        val c = RefreshCoalescer(backgroundScope, MutableStateFlow(true), 2_000, 30_000) { calls++ }
        repeat(10) { c.poke() }
        runCurrent()
        assertEquals(1, calls)
        repeat(10) { c.poke() }
        advanceTimeBy(1_999); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, calls)
        // Nothing pending: the floor passes quietly.
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `off screen the floor is long but nothing is dropped`() = runTest {
        var calls = 0
        val c = RefreshCoalescer(backgroundScope, MutableStateFlow(false), 2_000, 30_000) { calls++ }
        c.poke(); runCurrent()
        assertEquals(1, calls)
        repeat(30) { c.poke() }
        advanceTimeBy(29_000); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `coming back on screen ends the background floor at once`() = runTest {
        var calls = 0
        val fg = MutableStateFlow(false)
        val c = RefreshCoalescer(backgroundScope, fg, 2_000, 30_000) { calls++ }
        c.poke(); runCurrent()
        c.poke()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, calls)
        fg.value = true; runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `a poke after the job finished starts a fresh refresh immediately`() = runTest {
        var calls = 0
        val c = RefreshCoalescer(backgroundScope, MutableStateFlow(true), 2_000, 30_000) { calls++ }
        c.poke(); runCurrent()
        advanceTimeBy(2_001); runCurrent()
        c.poke(); runCurrent()
        assertEquals(2, calls)
    }
}
