package chat.keryx.app.presentation.tour

import chat.keryx.app.KeryxText
import chat.keryx.app.R
import chat.keryx.core.tour.TourCta
import chat.keryx.core.tour.TourPlace
import chat.keryx.core.tour.TourPreset
import chat.keryx.core.tour.TourStep
import chat.keryx.core.tour.TourTarget

/**
 * What the tour can point at in Keryx, and the app's own tour (3.0). The ids are the
 * `[data-tour="…"]` an agent passes back, so they are a wire contract: never rename casually.
 *
 * The catalog is per gateway, the way the screen is: Bots and the new-session button are the
 * direct door's, Runs needs Hermes Link. The host narrows it once more to what the screen wears
 * right now ([TourRegistry.isWorn]), so neither the built-in tour nor the agent can point at an
 * empty place. The door rules here are the floor under that, for places not composed yet.
 */
object KeryxTour {
    const val COMPOSER = "composer"
    const val MODEL = "model-pill"
    const val NEW_SESSION = "new-session"
    const val DRAWER_BUTTON = "drawer-button"
    const val LENSES = "drawer-lenses"
    /** The bot lights across the top of the drawer (3.0). */
    const val BOT_LIGHTS = "drawer-bot-lights"
    const val MISSIONS = "door-missions"
    const val RUNS = "door-runs"
    const val BOTS = "door-bots"
    const val ARCHIVE = "door-archive"
    const val MACHINE = "drawer-status"

    /** The agent's own instruments, worn only while they're up: the plan it's ticking off and
     *  the approval it stopped on. */
    const val FLIGHT_PLAN = "flight-plan"
    const val APPROVAL = "approval"

    /** Every id above, in tour order. */
    val ALL = listOf(FLIGHT_PLAN, APPROVAL, COMPOSER, MODEL, NEW_SESSION, DRAWER_BUTTON, BOT_LIGHTS, LENSES, MISSIONS, RUNS, BOTS, ARCHIVE, MACHINE)

    /** Ends the tour by opening Settings › Gateways. */
    const val CTA_ADD_GATEWAY = "add-gateway"

    /** Ends the demo's tour by leaving the demo for the sign-in (3.0 first run). */
    const val CTA_CONNECT = "connect-gateway"

    /** A launch extra asking for the tour on arrival: a [TourPreset] wire name. */
    const val EXTRA_TOUR = "chat.keryx.app.TOUR"

    /** What this gateway puts on screen: the direct door, whether Hermes Link answers, and
     *  whether it is the demo (no gateway yet, so the tour ends on getting one). */
    data class Context(val direct: Boolean, val link: Boolean, val demo: Boolean = false)

    fun catalog(ctx: Context): List<TourTarget> = buildList {
        if (ctx.direct) add(TourTarget(FLIGHT_PLAN, "Flight Plan: the agent's steps for this job", TourPlace.CHAT))
        if (ctx.direct) add(TourTarget(APPROVAL, "Approval the agent is waiting on", TourPlace.CHAT))
        add(TourTarget(COMPOSER, "Message composer", TourPlace.CHAT))
        add(TourTarget(MODEL, "Model picker", TourPlace.CHAT))
        if (ctx.direct) add(TourTarget(NEW_SESSION, "New session", TourPlace.CHAT))
        add(TourTarget(DRAWER_BUTTON, "Menu: sessions and places", TourPlace.CHAT))
        if (ctx.direct) add(TourTarget(BOT_LIGHTS, "Bot lights: every bot, one tap from its chat", TourPlace.DRAWER))
        if (ctx.direct) add(TourTarget(LENSES, "Session filters: needs you, running, unread", TourPlace.DRAWER))
        add(TourTarget(MISSIONS, "Missions board", TourPlace.DRAWER))
        if (ctx.link) add(TourTarget(RUNS, "Runs: scheduled jobs", TourPlace.DRAWER))
        if (ctx.direct) add(TourTarget(BOTS, "Bots: agent profiles", TourPlace.DRAWER))
        add(TourTarget(ARCHIVE, "Archive: search everything", TourPlace.DRAWER))
        add(TourTarget(MACHINE, "Gateway status and Settings", TourPlace.DRAWER))
    }

    /**
     * The app's own tour, as stops narrowed to what [available] holds. Quick is the four things
     * a first chat needs (the Desktop's essentials: where to ask, which brain, a fresh page,
     * where the rest lives); full goes on into the drawer and ends on the gateway.
     *
     * [words] reads the stops' strings (res/values/strings_app.xml): the app's language at run
     * time, the English file in the plain-JVM tests.
     */
    fun builtIn(
        preset: TourPreset,
        available: Set<String>,
        ctx: Context,
        words: (Int) -> String = { KeryxText.get(it) },
    ): List<TourStep> {
        fun stop(target: String?, title: Int, text: Int, cta: TourCta? = null) =
            TourStep(target, words(title), words(text), cta = cta)
        // The demo's tour opens by saying what this is, in the middle of the screen.
        val welcome = if (ctx.demo) listOf(
            stop(null, R.string.app_tour_welcome_title, R.string.app_tour_welcome_text),
        ) else emptyList()
        // What an agent at work looks like, when there is one on screen: the demo opens on one.
        val atWork = listOf(
            stop(FLIGHT_PLAN, R.string.app_tour_plan_title, R.string.app_tour_plan_text),
            stop(
                APPROVAL, R.string.app_tour_approval_title,
                if (ctx.demo) R.string.app_tour_approval_text_demo else R.string.app_tour_approval_text,
            ),
        )
        val quick = welcome + atWork + listOf(
            stop(COMPOSER, R.string.app_tour_composer_title, R.string.app_tour_composer_text),
            stop(MODEL, R.string.app_tour_model_title, R.string.app_tour_model_text),
            stop(NEW_SESSION, R.string.app_tour_new_session_title, R.string.app_tour_new_session_text),
            stop(DRAWER_BUTTON, R.string.app_tour_drawer_title, R.string.app_tour_drawer_text),
        )
        val full = quick + listOf(
            stop(BOT_LIGHTS, R.string.app_tour_bot_lights_title, R.string.app_tour_bot_lights_text),
            stop(LENSES, R.string.app_tour_lenses_title, R.string.app_tour_lenses_text),
            stop(MISSIONS, R.string.app_tour_missions_title, R.string.app_tour_missions_text),
            stop(RUNS, R.string.app_tour_runs_title, R.string.app_tour_runs_text),
            stop(BOTS, R.string.app_tour_bots_title, R.string.app_tour_bots_text),
            stop(ARCHIVE, R.string.app_tour_archive_title, R.string.app_tour_archive_text),
            if (ctx.demo) stop(
                MACHINE, R.string.app_tour_gateway_title, R.string.app_tour_gateway_text_demo,
                cta = TourCta(words(R.string.app_tour_cta_connect), CTA_CONNECT),
            ) else stop(
                MACHINE, R.string.app_tour_gateway_title,
                if (ctx.direct) R.string.app_tour_gateway_text_direct else R.string.app_tour_gateway_text,
                cta = if (ctx.direct) TourCta(words(R.string.app_tour_cta_add_gateway), CTA_ADD_GATEWAY) else null,
            ),
        )
        return (if (preset == TourPreset.QUICK) quick else full).filter { it.target == null || it.target in available }
    }

    /** Which release [whatsNew] describes. Stored once it has shown, so it shows once per release. */
    // "3.0-b5": the beta.5 stops (bots, board, small things) show once more to beta.4 testers.
    const val WHATS_NEW_RELEASE = "3.0-b5"

    /**
     * "What's new in 3.0", for someone who used Keryx before it: a handful of stops on the real
     * controls, narrowed to what [available] holds like the built-in tour. A fresh install never
     * sees it (the welcome marks it seen); Settings › About replays it.
     */
    fun whatsNew(
        available: Set<String>,
        ctx: Context,
        words: (Int) -> String = { KeryxText.get(it) },
    ): List<TourStep> {
        fun stop(target: String?, title: Int, text: Int) = TourStep(target, words(title), words(text))
        return listOfNotNull(
            stop(null, R.string.app_whatsnew_title, R.string.app_whatsnew_text),
            // The agent pointing comes through Hermes Link on the direct door; Matrix never sees it.
            if (ctx.direct) stop(COMPOSER, R.string.app_whatsnew_point_title, R.string.app_whatsnew_point_text) else null,
            // Six stops at most, so it stays a look and not a tour. On the direct door the bots
            // up top stand in for the drawer stop and the memory ring gives way to pointing.
            if (!ctx.direct) stop(MODEL, R.string.app_whatsnew_memory_title, R.string.app_whatsnew_memory_text) else null,
            if (ctx.direct) stop(BOT_LIGHTS, R.string.app_tour_bot_lights_title, R.string.app_tour_bot_lights_text)
            else stop(DRAWER_BUTTON, R.string.app_whatsnew_drawer_title, R.string.app_whatsnew_drawer_text),
            stop(MISSIONS, R.string.app_whatsnew_board_title, R.string.app_whatsnew_board_text),
            stop(null, R.string.app_whatsnew_more_title, R.string.app_whatsnew_more_text),
            stop(
                MACHINE, R.string.app_whatsnew_look_title,
                if (ctx.direct) R.string.app_whatsnew_look_text_direct else R.string.app_whatsnew_look_text,
            ),
        ).filter { it.target == null || it.target in available }
    }
}
