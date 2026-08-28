package chat.keryx.core

import chat.keryx.core.protocol.ShipyardParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payloads captured 2026-08-28 off the gateway module itself (`_shipyard_routes` driven
 * against a scratch repo) — the wire truth, not hand-written ideals.
 */
class ShipyardParserTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test
    fun repos_parseWithBranchAndSource() {
        val repos = ShipyardParser.parseRepos(j("""{"repos": [{"path": "/home/u/workspace/talaria", "label": "Talaria", "source": "project", "branch": "master"}, {"path": "/home/u/workspace/charon", "label": "Charon", "source": "project", "branch": null}]}"""))!!
        assertEquals(2, repos.size)
        assertEquals("master", repos[0].branch)
        assertNull(repos[1].branch)
        assertEquals("project", repos[1].source)
    }

    @Test
    fun status_readsCountsAndCleanFlag() {
        val st = ShipyardParser.parseStatus(j("""{"path": "/home/u/r", "status": {"branch": "master", "defaultBranch": "master", "detached": false, "ahead": 0, "behind": 0, "staged": 0, "unstaged": 2, "untracked": 1, "conflicted": 0, "changed": 2, "added": 3, "removed": 1, "files": [{"path": "f.txt", "staged": false, "unstaged": true, "untracked": false, "conflicted": false}]}}"""))!!
        assertEquals("master", st.branch)
        assertEquals(2, st.changed)
        assertEquals(3, st.added)
        assertFalse(st.clean)
        // A repo that stopped being one answers status: null.
        assertNull(ShipyardParser.parseStatus(j("""{"path": "/x", "status": null}""")))
    }

    @Test
    fun review_untrackedIsQuestionMark_stagedIsA() {
        val r = ShipyardParser.parseReview(j("""{"files": [{"path": "f.txt", "added": 2, "removed": 1, "status": "M", "staged": false}, {"path": "g.txt", "added": 1, "removed": 0, "status": "A", "staged": true}], "base": null, "path": "/home/u/r", "scope": "uncommitted"}"""))!!
        assertEquals("uncommitted", r.scope)
        assertNull(r.base)
        assertEquals(2, r.files.size)
        assertTrue(r.files[1].staged)
        val u = ShipyardParser.parseReview(j("""{"files": [{"path": "g.txt", "added": 1, "removed": 0, "status": "?", "staged": false}], "base": null, "scope": "uncommitted"}"""))!!
        assertTrue(u.files[0].untracked)
    }

    @Test
    fun diff_carriesClipFlagsHonestly() {
        val d = ShipyardParser.parseDiff(j("""{"diff": "diff --git a/f.txt b/f.txt\n@@ -1,2 +1,3 @@\n a\n-b\n+B\n+c", "clipped": false, "omittedLines": 0, "totalLines": 9, "file": "f.txt", "scope": "uncommitted", "staged": false}"""))!!
        assertEquals("f.txt", d.file)
        assertFalse(d.clipped)
        val big = ShipyardParser.parseDiff(j("""{"diff": "+x", "clipped": true, "omittedLines": 3500, "totalLines": 6000, "file": "big.txt"}"""))!!
        assertTrue(big.clipped)
        assertEquals(3500, big.omittedLines)
    }

    @Test
    fun commitContext_splitsRecentSubjects() {
        val c = ShipyardParser.parseCommitContext(j("""{"diff": "diff --git a/f.txt b/f.txt\n+c\n\n# New (untracked) files:\n#   g.txt\n", "recent": "one\ninit"}"""))!!
        assertEquals(listOf("one", "init"), c.recentSubjects)
        assertTrue(c.diff.contains("# New (untracked) files"))
    }

    @Test
    fun commitResult_andShipInfo() {
        val c = ShipyardParser.parseCommitResult(j("""{"ok": true, "sha": "292f266", "pushed": false}"""))
        assertNotNull(c)
        assertEquals("292f266", c.sha)
        assertFalse(c.pushed)
        val noPr = ShipyardParser.parseShipInfo(j("""{"ghReady": true, "pr": null}"""))!!
        assertTrue(noPr.ghReady)
        assertNull(noPr.prUrl)
        val pr = ShipyardParser.parseShipInfo(j("""{"ghReady": true, "pr": {"url": "https://github.com/o/r/pull/7", "state": "OPEN", "number": 7}}"""))!!
        assertEquals(7, pr.prNumber)
        assertEquals("https://github.com/o/r/pull/7", pr.prUrl)
    }
}
