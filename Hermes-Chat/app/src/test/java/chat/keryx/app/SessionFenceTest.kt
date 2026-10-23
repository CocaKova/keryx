package chat.keryx.app

import chat.keryx.app.transport.direct.SessionFence
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 10-23: Hermes 0.22.4 refuses an unfenced session edit with 409 `client_upgrade_required`, so
 * opening a chat never cleared its unread mark (nor did pin, archive, rename, hide or delete
 * land). Every edit now reads the session's mutation snapshot and carries its identity; a
 * gateway with no snapshot route gets the edit as before.
 */
class SessionFenceTest {

    private class Gateway(vararg answers: Pair<Int, String>) {
        val calls = mutableListOf<Triple<String, String, String?>>()
        private val queue = ArrayDeque(answers.toList())
        suspend fun exchange(method: String, path: String, body: String?): Result<Pair<Int, String>> {
            calls += Triple(method, path, body)
            return Result.success(queue.removeFirst())
        }
    }

    private val snap = """{"session_id":"s1","exists":true,"runtime_revision":7,"runtime_generation":2}"""
    private val edit = buildJsonObject { put("unread", false); put("profile", "vega") }
    private var ids = 0
    private val newId = { "req-${++ids}" }

    private suspend fun patch(gw: Gateway) = SessionFence.mutate(
        { m, p, b -> gw.exchange(m, p, b) }, "PATCH", "/api/sessions/s1", "?profile=vega",
        build = { id -> "/api/sessions/s1" to SessionFence.withIdentity(edit, id).toString() },
        newRequestId = newId,
    )

    @Test fun aFencedGatewayGetsTheSnapshotsIdentity() = runTest {
        val gw = Gateway(200 to snap, 200 to """{"ok":true}""")
        assertTrue(patch(gw).isSuccess)
        assertEquals("GET" to "/api/sessions/s1/mutation-snapshot?profile=vega", gw.calls[0].first to gw.calls[0].second)
        val body = Json.parseToJsonElement(gw.calls[1].third!!).jsonObject
        assertEquals(JsonPrimitive("req-1"), body["request_id"])
        assertEquals(JsonPrimitive(7L), body["expected_revision"])
        assertEquals(JsonPrimitive(2L), body["expected_generation"])
        assertEquals(JsonPrimitive(false), body["unread"])
        assertEquals(JsonPrimitive("vega"), body["profile"])
    }

    @Test fun aGatewayWithoutTheRouteGetsTheEditAsBefore() = runTest {
        val gw = Gateway(404 to """{"detail":"Not Found"}""", 200 to "{}")
        assertTrue(patch(gw).isSuccess)
        val body = Json.parseToJsonElement(gw.calls[1].third!!).jsonObject
        assertNull(body["request_id"])
        assertNull(body["expected_revision"])
        assertEquals(setOf("unread", "profile"), body.keys)
    }

    @Test fun oneConflictRereadsTheRevisionAndSendsTheSameIntentAgain() = runTest {
        val gw = Gateway(
            200 to snap, 409 to """{"detail":"revision_conflict"}""",
            200 to snap.replace("\"runtime_revision\":7", "\"runtime_revision\":8"), 200 to "{}",
        )
        assertTrue(patch(gw).isSuccess)
        assertEquals(4, gw.calls.size)
        val retry = Json.parseToJsonElement(gw.calls[3].third!!).jsonObject
        assertEquals(JsonPrimitive("req-2"), retry["request_id"])
        assertEquals(JsonPrimitive(8L), retry["expected_revision"])
    }

    @Test fun aSecondConflictIsReportedNotLooped() = runTest {
        val conflict = 409 to """{"detail":"revision_conflict"}"""
        val gw = Gateway(200 to snap, conflict, 200 to snap, conflict)
        val r = patch(gw)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("409"))
        assertEquals(4, gw.calls.size)
    }

    @Test fun aGoneSessionSendsNothing() = runTest {
        val gw = Gateway(200 to """{"session_id":"s1","exists":false,"runtime_revision":0,"runtime_generation":null}""")
        assertTrue(patch(gw).isFailure)
        assertEquals(1, gw.calls.size)
    }

    @Test fun anUpgradeRefusalIsAFailureNotARetry() = runTest {
        val gw = Gateway(200 to snap, 409 to """{"detail":"client_upgrade_required: this app is out of sync"}""")
        assertTrue(patch(gw).isFailure)
        assertEquals(2, gw.calls.size)
    }

    @Test fun deleteCarriesItsIdentityInTheQuery() {
        val q = SessionFence.deleteQuery("vega", SessionFence.Identity("r 1", 3, 4))
        assertEquals("?profile=vega&request_id=r+1&expected_revision=3&expected_generation=4", q)
        assertEquals("", SessionFence.deleteQuery(null, null))
        assertEquals("?profile=vega", SessionFence.deleteQuery(" vega ", null))
    }

    @Test fun onlyRevisionAndGenerationConflictsRetry() {
        assertTrue(SessionFence.isConflict(409, """{"detail":"stale_generation"}"""))
        assertFalse(SessionFence.isConflict(409, """{"detail":"session_busy"}"""))
        assertFalse(SessionFence.isConflict(400, """{"detail":"revision_conflict"}"""))
    }
}
