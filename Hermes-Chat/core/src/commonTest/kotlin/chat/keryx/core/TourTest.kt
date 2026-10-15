package chat.keryx.core

import chat.keryx.core.tour.TourAction
import chat.keryx.core.tour.TourEngine
import chat.keryx.core.tour.TourPlace
import chat.keryx.core.tour.TourPreset
import chat.keryx.core.tour.TourRun
import chat.keryx.core.tour.TourSelector
import chat.keryx.core.tour.TourSide
import chat.keryx.core.tour.TourStep
import chat.keryx.core.tour.TourTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tour's wire and engine (3.0): Hermes's `gui_tour` arguments, checked with tour_tool.py's
 * own messages, and the answers in the shape the Desktop renderer gives, so the agent's habits
 * carry from the Desktop to the phone.
 */
class TourTest {
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun parse(json: String) = TourAction.parse(args(json))

    private val catalog = listOf(
        TourTarget("composer", "Composer", TourPlace.CHAT),
        TourTarget("door-missions", "Missions", TourPlace.DRAWER),
    )
    private val builtIn: (TourPreset) -> List<TourStep> = { preset ->
        val quick = listOf(TourStep("composer", "Ask", "Type here.", place = TourPlace.CHAT))
        if (preset == TourPreset.QUICK) quick else quick + TourStep("door-missions", "Missions", "Cards.", place = TourPlace.DRAWER)
    }
    private fun apply(run: TourRun?, action: TourAction) = TourEngine.apply(run, action, catalog, builtIn)

    @Test
    fun `selectors name a target by id, in the Desktop's form or bare`() {
        assertEquals("composer", TourSelector.idOf("[data-tour=\"composer\"]"))
        assertEquals("composer", TourSelector.idOf("[data-tour='composer']"))
        assertEquals("door-missions", TourSelector.idOf("door-missions"))
        assertNull(TourSelector.idOf("div.sidebar > button:nth-child(2)"))
        assertEquals("[data-tour=\"composer\"]", TourSelector.of("composer"))
    }

    @Test
    fun `arguments are checked with tour_tool's own messages`() {
        assertEquals("action must be one of: targets, show, start, next, prev, stop.", parse("""{"action":"dance"}""").second)
        assertEquals("side must be one of: top, right, bottom, left.", parse("""{"action":"show","selector":"composer","side":"up"}""").second)
        assertEquals("preset must be one of: quick, full.", parse("""{"action":"start","preset":"long"}""").second)
        assertEquals(
            "preset picks a built-in tour; pass steps or preset, not both.",
            parse("""{"action":"start","preset":"quick","steps":[{"title":"x"}]}""").second,
        )
        assertEquals("show needs a selector (and/or title/text for the popover).", parse("""{"action":"show"}""").second)
        assertEquals("steps[1] needs a selector and/or title/text.", parse("""{"action":"start","steps":[{"title":"a"},{"side":"top"}]}""").second)
        assertEquals("Keryx has no preview pane; use surface='app'.", parse("""{"action":"show","surface":"preview","title":"x"}""").second)
    }

    @Test
    fun `start without steps is the built-in tour, full unless told quick`() {
        val full = assertIs<TourAction.Start>(parse("""{"action":"start"}""").first)
        assertNull(full.steps)
        assertEquals(TourPreset.FULL, full.preset)
        val quick = assertIs<TourAction.Start>(parse("""{"action":"START","preset":"quick"}""").first)
        assertEquals(TourPreset.QUICK, quick.preset)
    }

    @Test
    fun `a step's selector, side and words come through`() {
        val show = assertIs<TourAction.Show>(parse("""{"action":"show","selector":"[data-tour=\"composer\"]","title":"Here","text":"Type.","side":"top"}""").first)
        assertEquals(TourStep("composer", "Here", "Type.", TourSide.TOP), show.step)
    }

    @Test
    fun `targets lists what exists here, with where it lives`() {
        val (run, result) = apply(null, TourAction.Targets)
        assertNull(run)
        val json = result.toJson()
        val first = json["targets"]!!.jsonArray[0].jsonObject
        assertEquals("[data-tour=\"composer\"]", first["selector"]!!.jsonPrimitive.content)
        assertEquals("chat", first["place"]!!.jsonPrimitive.content)
        assertEquals("true", first["stable"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a selector that names nothing here is refused with the re-scan hint`() {
        val step = assertIs<TourAction.Show>(parse("""{"action":"show","selector":"[data-tour=\"model-pill\"]"}""").first).step
        val (run, result) = apply(null, TourAction.Show(step))
        assertNull(run)
        assertEquals(false, result.success)
        assertEquals("No element matches selector(s): [data-tour=\"model-pill\"]", result.error)
        assertNotNull(result.hint)
        // CSS the phone can't read is named back as written.
        val css = assertIs<TourAction.Show>(parse("""{"action":"show","selector":"#app > div"}""").first).step
        assertEquals("No element matches selector(s): #app > div", apply(null, TourAction.Show(css)).second.error)
    }

    @Test
    fun `an agent's step learns where its target lives`() {
        val (run, _) = apply(null, TourAction.Show(TourStep("door-missions", text = "Long jobs go here.")))
        assertEquals(TourPlace.DRAWER, run!!.step.place)
        assertEquals(false, run.paged)
    }

    @Test
    fun `paging walks the steps and the last next ends it`() {
        var (run, result) = apply(null, TourAction.Start(null, TourPreset.FULL))
        assertEquals(2, result.steps)
        assertEquals(0, result.activeStep)
        apply(run, TourAction.Prev).let { (r, res) -> run = r; assertEquals(0, res.activeStep) }
        apply(run, TourAction.Next).let { (r, res) -> run = r; assertEquals(1, res.activeStep) }
        val (end, done) = apply(run, TourAction.Next)
        assertNull(end)
        assertEquals(true, done.done)
    }

    @Test
    fun `next with nothing on screen says so, and stop always succeeds`() {
        assertEquals("No tour is running — start one first.", apply(null, TourAction.Next).second.error)
        assertTrue(apply(null, TourAction.Stop).second.success)
    }

    @Test
    fun `start_at is clamped and a built-in tour with no stops here fails in words`() {
        val (run, _) = apply(null, TourAction.Start(null, TourPreset.QUICK, startAt = 9))
        assertEquals(0, run!!.index)
        val (none, res) = TourEngine.apply(null, TourAction.Start(null, TourPreset.FULL), emptyList()) { emptyList() }
        assertNull(none)
        assertEquals("None of the built-in tour stops are on this phone.", res.error)
    }

    @Test
    fun `answers carry only the fields they have`() {
        val json: JsonObject = apply(null, TourAction.Stop).second.toJson()
        assertEquals(setOf("success", "action"), json.keys)
    }
}
