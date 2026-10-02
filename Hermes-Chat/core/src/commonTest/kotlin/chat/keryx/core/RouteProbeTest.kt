package chat.keryx.core

import chat.keryx.core.model.RouteProbe
import chat.keryx.core.model.Routed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A missing route hides a panel; a route saying "nothing here" or "no" must not. The bodies are
 * what each server actually sends (dashboard SPA catch-all, FastAPI, aiohttp, a real route).
 */
class RouteProbeTest {

    @Test
    fun theServersOwnMissingRouteAnswersAreMissing() {
        assertTrue(RouteProbe.isMissingRoute(404, """{"detail":"No such API endpoint: /api/learning/graph"}"""))
        assertTrue(RouteProbe.isMissingRoute(404, """{"detail": "Not Found"}"""))
        assertTrue(RouteProbe.isMissingRoute(404, "404: Not Found"))
        assertTrue(RouteProbe.isMissingRoute(404, ""))
        // The catch-all is GET-only: a POST to a route this dashboard never had is a 405.
        assertTrue(RouteProbe.isMissingRoute(405, """{"detail":"Method Not Allowed"}"""))
    }

    @Test
    fun aRouteThatFindsNothingIsNotMissing() {
        assertFalse(RouteProbe.isMissingRoute(404, """{"detail":"No update receipt found (no `hermes update` run recorded)."}"""))
        assertFalse(RouteProbe.isMissingRoute(404, """{"detail":"Session not found"}"""))
        assertFalse(RouteProbe.isMissingRoute(400, """{"detail":"memory node id is stale — refresh the graph"}"""))
        assertFalse(RouteProbe.isMissingRoute(200, "{}"))
    }

    @Test
    fun classify_keepsTheServersWordsOnARefusal() {
        assertEquals(Routed.Ok("{}"), RouteProbe.classify(200, "{}"))
        assertEquals(Routed.Missing, RouteProbe.classify(404, """{"detail":"No such API endpoint: /api/x"}"""))
        assertEquals(
            Routed.Failed("Replacement would put memory at 2,400/2,200 chars. Shorten the new content.", 400),
            RouteProbe.classify(400, """{"detail":"Replacement would put memory at 2,400/2,200 chars. Shorten the new content."}"""),
        )
    }

    @Test
    fun serverWords_readsEveryShapeTheGatewaysUse() {
        assertEquals("nope", RouteProbe.serverWords(400, """{"detail":"nope"}"""))
        assertEquals("field required", RouteProbe.serverWords(422, """{"detail":[{"loc":["body","id"],"msg":"field required","type":"missing"}]}"""))
        assertEquals("an update was just started — let it finish", RouteProbe.serverWords(409, """{"error":{"message":"an update was just started — let it finish"}}"""))
        assertEquals("HTTP 502", RouteProbe.serverWords(502, "<html>Bad Gateway</html>"))
    }

    @Test
    fun map_turnsAnUnreadablePayloadIntoAFailureNotACrash() {
        assertEquals(Routed.Ok(3), Routed.Ok("abc").map { it.length })
        assertTrue(Routed.Ok("abc").map<Int> { null } is Routed.Failed)
        val missing: Routed<String> = Routed.Missing
        assertEquals(Routed.Missing, missing.map { it.length })
    }
}
