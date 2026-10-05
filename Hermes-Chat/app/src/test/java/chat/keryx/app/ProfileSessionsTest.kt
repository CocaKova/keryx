package chat.keryx.app

import chat.keryx.app.transport.direct.DirectTransport
import chat.keryx.app.transport.direct.GatewayRest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2.17.3: sessions started on another profile join the drawer from the cross-profile page.
 * Which rows are "foreign" is decided by the launch list's own tags, never by assuming the
 * gateway runs as `default`.
 */
class ProfileSessionsTest {

    private fun row(id: String, profile: String, archived: Boolean = false) = GatewayRest.SessionRow(
        id = id, title = id, preview = "", startedAt = 0, lastActive = 0, messageCount = 1,
        isActive = false, archived = archived, pinned = false, source = "tui", profile = profile,
    )

    @Test fun launchProfileRowsStayInTheLaunchList() {
        val launch = listOf(row("a", "default"))
        val all = listOf(row("a", "default"), row("b", "default"), row("j1", "juno"))
        assertEquals(listOf("j1"), DirectTransport.foreignRows(all, launch).map { it.id })
    }

    @Test fun aGatewayLaunchedAsAnotherProfileTreatsDefaultAsForeign() {
        val launch = listOf(row("t1", "theo"))
        val all = listOf(row("t1", "theo"), row("t2", "theo"), row("d1", "default"))
        assertEquals(listOf("d1"), DirectTransport.foreignRows(all, launch).map { it.id })
    }

    @Test fun emptyLaunchListFallsBackToDefaultAndDropsArchivedAndUntagged() {
        val all = listOf(row("d1", "default"), row("j1", "juno"), row("j2", "juno", archived = true), row("x", ""))
        assertEquals(listOf("j1"), DirectTransport.foreignRows(all, emptyList()).map { it.id })
    }

    @Test fun anIdAlreadyListedNeverDuplicates() {
        // A row the launch list carries is never added twice, whatever it is tagged.
        val launch = listOf(row("same", "default"))
        val all = listOf(row("same", "juno"))
        assertEquals(emptyList<String>(), DirectTransport.foreignRows(all, launch).map { it.id })
    }
}
