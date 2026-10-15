package chat.keryx.app.presentation.tour

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
    const val MISSIONS = "door-missions"
    const val RUNS = "door-runs"
    const val BOTS = "door-bots"
    const val ARCHIVE = "door-archive"
    const val MACHINE = "drawer-status"

    /** Ends the tour by opening Settings › Gateways. */
    const val CTA_ADD_GATEWAY = "add-gateway"

    /** What this gateway puts on screen: the direct door, and whether Hermes Link answers. */
    data class Context(val direct: Boolean, val link: Boolean)

    fun catalog(ctx: Context): List<TourTarget> = buildList {
        add(TourTarget(COMPOSER, "Message composer", TourPlace.CHAT))
        add(TourTarget(MODEL, "Model picker", TourPlace.CHAT))
        if (ctx.direct) add(TourTarget(NEW_SESSION, "New session", TourPlace.CHAT))
        add(TourTarget(DRAWER_BUTTON, "Menu: sessions and places", TourPlace.CHAT))
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
     */
    fun builtIn(preset: TourPreset, available: Set<String>, ctx: Context): List<TourStep> {
        val quick = listOf(
            TourStep(COMPOSER, "Ask for anything", "Give your agent a job in plain words. It keeps working if you leave, and Keryx lets you know when it's done."),
            TourStep(MODEL, "Which brain answers", "Switch the model for this session, or for every session from here on."),
            TourStep(NEW_SESSION, "A fresh page", "Start a new session. Hold it for options, like a title or a temporary session."),
            TourStep(DRAWER_BUTTON, "Everything else", "Your sessions and the rest of Keryx are behind this button."),
        )
        val full = quick + listOf(
            TourStep(LENSES, "What needs you", "Narrow the list to sessions waiting on you, running right now, or unread."),
            TourStep(MISSIONS, "Missions", "Hand your agent longer jobs as cards on a board. It works through them and asks when it needs you."),
            TourStep(RUNS, "Runs", "Scheduled jobs, and what each run turned up."),
            TourStep(BOTS, "Bots", "Your agent's other profiles. Each keeps its own memory, skills and chats."),
            TourStep(ARCHIVE, "Archive", "Everything ever said, searchable on the phone, even with the gateway down."),
            TourStep(
                MACHINE, "Your gateway",
                "The link, the model and the Hermes version. Tap it for the Gateway; the gear opens Settings, where this tour lives too." +
                    if (ctx.direct) " Run more than one Hermes? Add it and switch between them here." else "",
                cta = if (ctx.direct) TourCta("Add a gateway", CTA_ADD_GATEWAY) else null,
            ),
        )
        return (if (preset == TourPreset.QUICK) quick else full).filter { it.target == null || it.target in available }
    }
}
