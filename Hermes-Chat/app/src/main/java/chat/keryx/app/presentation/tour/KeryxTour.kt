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

    /** The agent's own instruments, worn only while they're up: the plan it's ticking off and
     *  the approval it stopped on. */
    const val FLIGHT_PLAN = "flight-plan"
    const val APPROVAL = "approval"

    /** Every id above, in tour order. */
    val ALL = listOf(FLIGHT_PLAN, APPROVAL, COMPOSER, MODEL, NEW_SESSION, DRAWER_BUTTON, LENSES, MISSIONS, RUNS, BOTS, ARCHIVE, MACHINE)

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
        // The demo's tour opens by saying what this is, in the middle of the screen.
        val welcome = if (ctx.demo) listOf(
            TourStep(
                null, "Welcome to Keryx",
                "This is a demo: a pretend gateway on your phone, and its agent is in the middle of a job. " +
                    "Here's a quick look around before you connect your own.",
            ),
        ) else emptyList()
        // What an agent at work looks like, when there is one on screen: the demo opens on one.
        val atWork = listOf(
            TourStep(
                FLIGHT_PLAN, "Its plan",
                "Your agent breaks a job into steps and ticks them off as it goes. While it works, Keryx keeps a " +
                    "live notice of the job, so you can put the phone away.",
            ),
            TourStep(
                APPROVAL, "It asks first",
                "Before anything risky, like deleting a folder, your agent stops and asks. Answer here or right " +
                    "from the notification." + if (ctx.demo) " Try it when the tour's done, and watch it finish the job." else "",
            ),
        )
        val quick = welcome + atWork + listOf(
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
            if (ctx.demo) TourStep(
                MACHINE, "Your gateway",
                "With your own Hermes, this shows the link, the model and the version. Everything you just saw " +
                    "works the same on a real gateway: connect yours and it's live.",
                cta = TourCta("Connect your gateway", CTA_CONNECT),
            ) else TourStep(
                MACHINE, "Your gateway",
                "The link, the model and the Hermes version. Tap it for the Gateway; the gear opens Settings, where this tour lives too." +
                    if (ctx.direct) " Run more than one Hermes? Add it and switch between them here." else "",
                cta = if (ctx.direct) TourCta("Add a gateway", CTA_ADD_GATEWAY) else null,
            ),
        )
        return (if (preset == TourPreset.QUICK) quick else full).filter { it.target == null || it.target in available }
    }

    /** Which release [whatsNew] describes. Stored once it has shown, so it shows once per release. */
    const val WHATS_NEW_RELEASE = "3.0"

    /**
     * "What's new in 3.0", for someone who used Keryx before it: a handful of stops on the real
     * controls, narrowed to what [available] holds like the built-in tour. A fresh install never
     * sees it (the welcome marks it seen); Settings › About replays it.
     */
    fun whatsNew(available: Set<String>, ctx: Context): List<TourStep> = listOfNotNull(
        TourStep(
            null, "What's new in Keryx 3.0",
            "A quick look at what changed. Skip it if you like: it stays in Settings › About.",
        ),
        // The agent pointing comes through Hermes Link on the direct door; Matrix never sees it.
        if (ctx.direct) TourStep(
            COMPOSER, "Ask where something is",
            "Ask your agent \"where are my scheduled jobs?\" and it can point at the place, right here on " +
                "the phone. Your gateway needs keryx-stream 0.6 or newer.",
        ) else null,
        TourStep(
            MODEL, "Chat memory",
            "The ring beside the model now says how many replies this chat has room for, and only once it " +
                "matters. Tap it to see what's using the room.",
        ),
        if (ctx.direct) TourStep(
            LENSES, "A redrawn drawer",
            "Each chat has its own colour and mark, a ring shows the ones at work, and these filters narrow " +
                "the list to what needs you.",
        ) else TourStep(
            DRAWER_BUTTON, "A redrawn drawer",
            "Each chat has its own colour and mark, and a ring shows the ones at work.",
        ),
        TourStep(
            MISSIONS, "Cards in plain words",
            "A mission card now opens on what's going on and the one thing to do next. The technical " +
                "details are still there, under Details.",
        ),
        TourStep(
            MACHINE, "Look and updates",
            "Settings › Appearance has new thinking styles and five ways to move between chats. " +
                if (ctx.direct) "With a recent keryx-stream, the Gateway's Update panel lets you pick Stable releases " +
                    "or Edge. To add another Hermes, run hermes keryx pair on it and scan the code." else
                    "The tour and the space hints live in Settings › About.",
        ),
    ).filter { it.target == null || it.target in available }
}
