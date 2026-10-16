package chat.keryx.app.presentation.tour

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

    private val HINTS: Map<String, TourStep> = mapOf(
        MISSIONS to TourStep(
            MISSIONS, "Missions",
            "Longer jobs live here as cards. Your agents pick them up and work through them, and a card that " +
                "needs you moves to the top. Tap + to hand one out.",
        ),
        RUNS to TourStep(
            RUNS, "Runs",
            "Jobs on a schedule, like a morning brief or a nightly check. Each run's result lands here. " +
                "Ask your agent and it can set one up for you.",
        ),
        BOTS to TourStep(
            BOTS, "Bots",
            "Each bot is a profile of your Hermes, with its own memory, skills and chats. Open one to talk " +
                "to it, or put a few in a group chat.",
        ),
        GATEWAY to TourStep(
            GATEWAY, "Your gateway",
            "How the Hermes you're connected to is doing, and what's behind it: its model and settings, " +
                "jobs, skills, memory and tools.",
        ),
        TAP_IN to TourStep(
            TAP_IN, "Tap-In",
            "Your agent at work, full screen: what it's thinking, the tools it runs and the helpers it sends " +
                "out. You can steer it from here too.",
        ),
        ARTIFACT to TourStep(
            ARTIFACT, "A page your agent made",
            "Reports, mock-ups and pages your agent writes open here, on the phone. Save one or share it " +
                "with the buttons up top.",
        ),
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

    /** The hint [dest] should show now, given the ones already [seen]; null = nothing to say. */
    fun due(dest: KeryxDest?, seen: Set<String>): TourStep? {
        val id = dest?.let(::idFor) ?: return null
        return HINTS[id]?.takeIf { id !in seen }
    }
}
