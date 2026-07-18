package chat.keryx.app

import chat.keryx.app.presentation.ui.components.sectionStartIndices
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Missions board's lane-jump math (1.23): each section renders one header item followed by
 * its cards, so a chip tap must scroll to header index = sum of (1 + count) over prior sections.
 */
class MissionSectionsTest {

    @Test
    fun `start indices accumulate one header plus cards per section`() {
        val starts = sectionStartIndices(
            listOf("running" to 2, "blocked" to 1, "done" to 5),
        )
        assertEquals(0, starts["running"])
        assertEquals(3, starts["blocked"]) // 1 header + 2 cards
        assertEquals(5, starts["done"])    // + 1 header + 1 card
    }

    @Test
    fun `empty sections still claim their header slot`() {
        val starts = sectionStartIndices(listOf("running" to 0, "review" to 3))
        assertEquals(0, starts["running"])
        assertEquals(1, starts["review"])
    }

    @Test
    fun `no sections no indices`() {
        assertEquals(emptyMap<String, Int>(), sectionStartIndices(emptyList()))
    }
}
