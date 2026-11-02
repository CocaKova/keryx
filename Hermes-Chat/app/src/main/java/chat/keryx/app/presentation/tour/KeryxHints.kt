package chat.keryx.app.presentation.tour

import chat.keryx.app.KeryxText
import chat.keryx.app.R
import chat.keryx.app.presentation.ui.nav.KeryxDest
import chat.keryx.core.tour.TourStep

/**
 * First-visit hints (3.0): the first time a space opens, one spotlight on its header says what
 * the space is for, then never again (until Settings › About shows them again). Drawn by the
 * tour's own overlay as a single "Got it" stop.
 *
 * They're the app's, not the agent's: the ids stay out of [KeryxTour.catalog], so a hint is
 * never something `gui_tour` can be asked to point at. Each id is worn by its space's
 * [chat.keryx.app.presentation.ui.components.KeryxSpace] header (`hint = KeryxHints.X`).
 *
 * Wording assumes stock Hermes and says what the space does for you, in a sentence or two.
 */
object KeryxHints {
    const val MISSIONS = "hint-missions"
    const val RUNS = "hint-runs"
    const val BOTS = "hint-bots"
    const val GATEWAY = "hint-gateway"
    const val TAP_IN = "hint-tapin"
    const val ARTIFACT = "hint-artifact"

    /** Each hint's title and text (res/values/strings_app.xml). */
    private val HINTS: Map<String, Pair<Int, Int>> = mapOf(
        MISSIONS to (R.string.app_hint_missions_title to R.string.app_hint_missions_text),
        RUNS to (R.string.app_hint_runs_title to R.string.app_hint_runs_text),
        BOTS to (R.string.app_hint_bots_title to R.string.app_hint_bots_text),
        GATEWAY to (R.string.app_hint_gateway_title to R.string.app_hint_gateway_text),
        TAP_IN to (R.string.app_hint_tapin_title to R.string.app_hint_tapin_text),
        ARTIFACT to (R.string.app_hint_artifact_title to R.string.app_hint_artifact_text),
    )

    /** Every hint id, for the check that each is worn. */
    val ALL: Set<String> get() = HINTS.keys

    /** The hint a space shows on its first visit, if it has one. */
    fun idFor(dest: KeryxDest): String? = when (dest) {
        KeryxDest.Missions -> MISSIONS
        KeryxDest.Runs -> RUNS
        KeryxDest.Bots -> BOTS
        KeryxDest.Gateway -> GATEWAY
        KeryxDest.TapIn -> TAP_IN
        is KeryxDest.Artifact -> ARTIFACT
        else -> null
    }

    /** The hint [dest] should show now, given the ones already [seen]; null = nothing to say.
     *  [words] reads its strings: the app's language at run time, English in the JVM tests. */
    fun due(dest: KeryxDest?, seen: Set<String>, words: (Int) -> String = { KeryxText.get(it) }): TourStep? {
        val id = dest?.let(::idFor) ?: return null
        if (id in seen) return null
        val (title, text) = HINTS[id] ?: return null
        return TourStep(id, words(title), words(text))
    }
}
