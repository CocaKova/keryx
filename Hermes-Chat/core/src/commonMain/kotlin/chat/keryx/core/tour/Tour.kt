package chat.keryx.core.tour

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * The guided tour (3.0 "The Herald"): dim the screen, cut a window round one control, say what
 * it is for. One engine runs three ways in: the app's own tour (quick or full), a first-visit
 * hint, and the agent pointing at the real button ("where do I set a cron?").
 *
 * The wire is Hermes's own `gui_tour` (tools/tour_tool.py): actions targets / show / start /
 * next / prev / stop, steps of selector + title + text + side, `start` without steps = the
 * built-in tour (preset quick or full). Speaking it exactly means a tour the agent writes for the
 * Desktop and one it writes for the phone are the same call, and so are the answers it gets back.
 *
 * Where the phone differs, it is because a phone is not a page:
 * - A selector names a tour target by id (`[data-tour="composer"]`, or the bare id). There is no
 *   DOM to query, so positional CSS names nothing here.
 * - Every target knows the place it lives in (the chat, the drawer, a space). A step can point at
 *   something that is not on screen yet and the app goes there first, which the Desktop's
 *   built-in tour does with `navigate`/`pane` and its agent tours cannot do at all.
 */

enum class TourSide(val wire: String) {
    TOP("top"), RIGHT("right"), BOTTOM("bottom"), LEFT("left");

    companion object {
        fun of(wire: String?): TourSide? = entries.firstOrNull { it.wire == wire }
    }
}

enum class TourPreset(val wire: String) {
    QUICK("quick"), FULL("full");

    companion object {
        fun of(wire: String?): TourPreset? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where the app has to be for a target to exist. A route ("missions") names a space. */
object TourPlace {
    const val CHAT = "chat"
    const val DRAWER = "drawer"
}

/**
 * One highlighted moment. No [target] = narration: the card sits mid-screen over the dimmed app.
 * [place] is filled from the target's own when the step is resolved, so a step written by the
 * agent never has to know where things live.
 *
 * [cta] is the app's own: a step that ends the tour by doing something ("Connect your gateway",
 * "Add a gateway"), so the last stop flows into the next thing to do instead of a bare "Done".
 * Never on the wire; the agent's steps don't have one.
 */
data class TourStep(
    val target: String? = null,
    val title: String? = null,
    val text: String? = null,
    val side: TourSide? = null,
    val place: String? = null,
    val cta: TourCta? = null,
)

/** The button a built-in stop ends the tour with: its words, and what the host does ([id]). */
data class TourCta(val label: String, val id: String)

/** Something a tour can point at, as `targets` reports it. */
data class TourTarget(
    val id: String,
    val label: String,
    val place: String,
) {
    val selector: String get() = TourSelector.of(id)
}

object TourSelector {
    fun of(id: String): String = "[data-tour=\"$id\"]"

    private val attribute = Regex("""^\[\s*data-tour\s*=\s*["']?([^"'\]]+?)["']?\s*]$""")
    private val bare = Regex("""^[A-Za-z0-9_-]+$""")

    /**
     * The id a selector names: `[data-tour="x"]` (either quote, or none) or a bare `x`. Anything
     * else is CSS the phone can't resolve, so it names nothing (null), and the caller says so.
     */
    fun idOf(selector: String): String? {
        val s = selector.trim()
        attribute.matchEntire(s)?.let { return it.groupValues[1].trim() }
        return s.takeIf { bare.matches(it) }
    }
}

sealed interface TourAction {
    val verb: String

    data object Targets : TourAction { override val verb = "targets" }
    data class Show(val step: TourStep) : TourAction { override val verb = "show" }
    /** [steps] null = the app's own tour, [preset] picks which. */
    data class Start(val steps: List<TourStep>?, val preset: TourPreset, val startAt: Int = 0) : TourAction {
        override val verb = "start"
    }
    data object Next : TourAction { override val verb = "next" }
    data object Prev : TourAction { override val verb = "prev" }
    data object Stop : TourAction { override val verb = "stop" }

    companion object {
        private val VERBS = listOf("targets", "show", "start", "next", "prev", "stop")

        /**
         * A `gui_tour` call's arguments, checked the way tools/tour_tool.py checks them (same
         * messages), plus the one thing only the phone can know: whether a selector names
         * anything here. Left = the error to hand back.
         */
        fun parse(args: JsonObject): Pair<TourAction?, String?> {
            val verb = args.string("action")?.trim()?.lowercase().orEmpty()
            if (verb !in VERBS) return null to "action must be one of: ${VERBS.joinToString(", ")}."
            val surface = args.string("surface")?.trim()?.lowercase() ?: "app"
            if (surface == "preview") return null to "Keryx has no preview pane; use surface='app'."
            if (surface != "app") return null to "surface must be one of: app, preview."
            val sideWire = args.string("side")
            if (sideWire != null && TourSide.of(sideWire) == null) return null to "side must be one of: top, right, bottom, left."
            val presetWire = args.string("preset")
            val preset = presetWire?.let { TourPreset.of(it) ?: return null to "preset must be one of: quick, full." }
            val rawSteps = args["steps"]?.takeUnless { it is JsonNull }
            if (preset != null && rawSteps != null) return null to "preset picks a built-in tour; pass steps or preset, not both."
            return when (verb) {
                "targets" -> Targets to null
                "show" -> {
                    val step = stepOf(args) ?: return null to "show needs a selector (and/or title/text for the popover)."
                    Show(step) to null
                }
                "start" -> {
                    if (rawSteps == null) return Start(null, preset ?: TourPreset.FULL, args.int("step_index") ?: 0) to null
                    val list = rawSteps as? JsonArray
                    if (list == null || list.isEmpty()) return null to "start needs a non-empty steps array, or no steps for the built-in tour."
                    val steps = list.mapIndexed { i, el ->
                        val obj = el as? JsonObject ?: return null to "steps[$i] must be an object."
                        stepOf(obj) ?: return null to "steps[$i] needs a selector and/or title/text."
                    }
                    Start(steps, TourPreset.FULL, args.int("step_index") ?: 0) to null
                }
                "next" -> Next to null
                "prev" -> Prev to null
                else -> Stop to null
            }
        }

        /** A step from its wire fields; null when it has nothing to point at and nothing to say. */
        private fun stepOf(obj: JsonObject): TourStep? {
            val selector = obj.string("selector")?.takeIf { it.isNotBlank() }
            val title = obj.string("title")?.takeIf { it.isNotBlank() }
            val text = obj.string("text")?.takeIf { it.isNotBlank() }
            if (selector == null && title == null && text == null) return null
            // An unreadable selector is kept as written, so the engine can name it back in its
            // "no element matches" answer instead of quietly narrating without it.
            return TourStep(
                target = selector?.let { TourSelector.idOf(it) ?: it },
                title = title,
                text = text,
                side = TourSide.of(obj.string("side")),
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}

/**
 * One `keryx_tour` call the agent made (keryx-stream 0.6, tour.py), as the gateway's own
 * `tool.start` carried it: the session, the call id the answer goes back under, and the same
 * arguments `gui_tour` takes ([TourAction.parse] reads them).
 */
data class AgentTourCall(val sessionId: String, val callId: String, val args: JsonObject) {
    companion object {
        const val TOOL = "keryx_tour"

        /** The tour call a `tool.start` describes, if it is one. A deferred tool rides the
         *  `tool_call` bridge, so that is peeled first. */
        fun of(sessionId: String, toolId: String?, name: String?, args: JsonObject?): AgentTourCall? {
            if (toolId.isNullOrBlank()) return null
            val wire = chat.keryx.core.model.ToolWire.unwrap(name.orEmpty(), args)
            if (wire.name != TOOL) return null
            return AgentTourCall(sessionId, toolId, wire.args ?: return null)
        }
    }
}

/** A tour on screen: its steps, the one showing, and whether the user pages it ([paged], from
 *  `start`) or the agent narrates one highlight per call (`show`). */
data class TourRun(
    val steps: List<TourStep>,
    val index: Int,
    val paged: Boolean,
) {
    val step: TourStep get() = steps[index]
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == steps.lastIndex
}

/** What an action answers, in the shape the Desktop's renderer answers `gui_tour` with. */
data class TourResult(
    val success: Boolean,
    val action: String? = null,
    val error: String? = null,
    val hint: String? = null,
    val activeStep: Int? = null,
    val steps: Int? = null,
    val done: Boolean? = null,
    val targets: List<TourTarget>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("success", success)
        action?.let { put("action", it) }
        error?.let { put("error", it) }
        hint?.let { put("hint", it) }
        activeStep?.let { put("activeStep", it) }
        steps?.let { put("steps", it) }
        done?.let { put("done", it) }
        targets?.let { list ->
            put("targets", buildJsonArray {
                list.forEach { t ->
                    add(buildJsonObject {
                        put("selector", t.selector)
                        put("label", t.label)
                        put("role", "button")
                        put("stable", true)
                        put("place", t.place)
                    })
                }
            })
        }
    }
}

object TourEngine {
    private const val NOT_RUNNING = "No tour is running — start one first."

    /**
     * Run one action against the tour on screen. Pure: the caller owns the state and applies
     * the run this hands back. [catalog] is what can be pointed at on this gateway right now;
     * [builtIn] is the app's own tour for a preset, already narrowed to that catalog.
     */
    fun apply(
        run: TourRun?,
        action: TourAction,
        catalog: List<TourTarget>,
        builtIn: (TourPreset) -> List<TourStep>,
    ): Pair<TourRun?, TourResult> {
        val known = catalog.associateBy { it.id }
        fun resolve(step: TourStep): TourStep =
            step.target?.let { id -> known[id]?.let { step.copy(place = step.place ?: it.place) } } ?: step
        fun missing(steps: List<TourStep>): TourResult? {
            val gone = steps.mapNotNull { it.target }.filter { it !in known }.distinct()
            if (gone.isEmpty()) return null
            return TourResult(
                success = false,
                error = "No element matches selector(s): " + gone.joinToString(", ") { if (TourSelector.idOf(it) == it) TourSelector.of(it) else it },
                hint = "Re-scan with 'targets': only the targets it lists exist on this phone.",
            )
        }
        return when (action) {
            TourAction.Targets -> run to TourResult(success = true, targets = catalog)
            is TourAction.Show -> {
                missing(listOf(action.step))?.let { return run to it }
                TourRun(listOf(resolve(action.step)), 0, paged = false) to TourResult(success = true, action = action.verb)
            }
            is TourAction.Start -> {
                val steps = action.steps?.also { s -> missing(s)?.let { return run to it } }
                    ?: builtIn(action.preset).ifEmpty {
                        return run to TourResult(success = false, error = "None of the built-in tour stops are on this phone.")
                    }
                val at = action.startAt.coerceIn(0, steps.lastIndex)
                TourRun(steps.map(::resolve), at, paged = true) to
                    TourResult(success = true, action = action.verb, activeStep = at, steps = steps.size)
            }
            TourAction.Next -> {
                if (run == null) return null to TourResult(success = false, error = NOT_RUNNING)
                if (run.isLast) null to TourResult(success = true, action = action.verb, done = true)
                else run.copy(index = run.index + 1).let { it to TourResult(success = true, action = action.verb, activeStep = it.index) }
            }
            TourAction.Prev -> {
                if (run == null) return null to TourResult(success = false, error = NOT_RUNNING)
                run.copy(index = (run.index - 1).coerceAtLeast(0)).let { it to TourResult(success = true, action = action.verb, activeStep = it.index) }
            }
            TourAction.Stop -> null to TourResult(success = true, action = action.verb)
        }
    }
}
