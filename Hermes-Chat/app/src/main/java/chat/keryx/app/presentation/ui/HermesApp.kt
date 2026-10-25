package chat.keryx.app.presentation.ui

import chat.keryx.app.presentation.tour.tourTarget
import chat.keryx.app.presentation.tour.KeryxTour
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.combinedClickable
import chat.keryx.app.presentation.ui.components.HeraldConfig
import chat.keryx.app.presentation.ui.components.LocalHeraldConfig
import chat.keryx.app.presentation.ui.components.keryxDuskSky
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.repeatOnLifecycle
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.nav.KeryxDest
import chat.keryx.app.presentation.ui.nav.KeryxNavHost
import chat.keryx.app.presentation.ui.nav.rememberKeryxNav
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ui.components.KeryxType
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HermesApp(viewModel: ChatViewModel) {
    val loggedIn by viewModel.isLoggedIn.collectAsState()
    if (!loggedIn) {
        LoginScreen(viewModel)
        return
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val currentRoom by viewModel.currentRoom.collectAsState()
    val linkHealth by viewModel.linkHealth.collectAsState()
    val heraldIds by viewModel.prefs.agentMatrixId.collectAsState()
    val heraldAccents by viewModel.prefs.heraldAccents.collectAsState()
    val hapticsEnabled by viewModel.prefs.hapticsEnabled.collectAsState()
    val awaitingReply by viewModel.awaitingReply.collectAsState()

    // The navigation spine (2.0): full-screen places live on this stack above the chat floor.
    val nav = rememberKeryxNav()

    // An assist summon walks the app home: whatever place was open sinks away, the drawer
    // closes, and ChatScreen's own collector focuses the composer.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var seen = viewModel.assistSummon.value
        viewModel.assistSummon.collect { n ->
            if (n > seen) {
                seen = n
                nav.home()
                drawerState.close()
            }
        }
    }
    val openSpace: (KeryxDest) -> Unit = { dest ->
        focusManager.clearFocus()
        keyboard?.hide()
        scope.launch { drawerState.close() }
        nav.open(dest)
    }

    // Tap-In asked for (the cloud, a run row, the shade): a layer on the nav stack (2.16).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.tapInRequested.collect { requested ->
            if (requested) {
                openSpace(KeryxDest.TapIn)
                viewModel.consumeTapIn()
            }
        }
    }

    // A keryx:// link to a place (2.16): walk there once.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.spaceRequest.collect { route ->
            val dest = when (route) {
                "missions" -> KeryxDest.Missions
                "runs" -> KeryxDest.Runs
                "archive" -> KeryxDest.Archive
                "projects" -> KeryxDest.Projects
                "bots" -> KeryxDest.Bots
                else -> null
            }
            if (route != null) {
                dest?.let(openSpace)
                viewModel.consumeSpaceRequest()
            }
        }
    }

    // A mission alert's tap (2.14.1): walk to Missions; the board opens the card's sheet and
    // consumes the request itself, so it survives the screen not being composed yet.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.missions.openTaskRequest.collect { taskId ->
            if (taskId != null) openSpace(KeryxDest.Missions)
        }
    }

    // The drawer can be opened by swipe, not just the menu button — the moment the gesture commits
    // (targetValue flips to Open) drop focus and hide the IME so the keyboard never sits on top of
    // the drawer blocking the room list.
    androidx.compose.runtime.LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            focusManager.clearFocus()
            keyboard?.hide()
            // The drawer is about to cover the room: photograph it for the crossing (3.0).
            chat.keryx.app.presentation.ui.components.RoomCrossingCue.drawerOpening()
        } else if (chat.keryx.app.presentation.ui.components.RoomCrossingCue.drawerOpen) {
            // …and if no room is picked, that photo is let go once the drawer is shut.
            chat.keryx.app.presentation.ui.components.RoomCrossingCue.drawerClosing()
        }
    }

    // Surface one-shot status messages (e.g. room-photo set result) regardless of which screen
    // triggered them, so failures are never silent. 2.16: on the themed notice line, not the
    // system Toast — it wears the theme, stands above the composer, and can offer an Undo.
    val snack = chat.keryx.app.presentation.ui.components.rememberKeryxSnack()
    androidx.compose.runtime.LaunchedEffect(snack) {
        viewModel.toasts.collect { msg -> snack.show(msg) }
    }
    androidx.compose.runtime.LaunchedEffect(snack) {
        viewModel.notices.collect { notice -> snack.show(notice) }
    }

    // Drawer-open assist for gestures that start on a horizontal scrollable (code block, wide
    // table). Those consume the pointer themselves, so the drawer's own drag never starts — but
    // their *unconsumed* leftover delta flows here through nested scroll. A rightward drag on a
    // block already at its left edge accumulates and, past a thumb-sized threshold, opens the
    // drawer: swiping right anywhere finally means "open the drawer", even over code.
    val drawerAssistThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    val drawerAssist = remember(drawerState, drawerAssistThresholdPx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput ||
                    drawerState.currentValue != DrawerValue.Closed || available.x <= 0f
                ) return androidx.compose.ui.geometry.Offset.Zero
                pulled += available.x
                if (pulled >= drawerAssistThresholdPx) {
                    pulled = 0f
                    scope.launch { drawerState.open() }
                }
                return androidx.compose.ui.geometry.Offset(available.x, 0f)
            }
            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                pulled = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    // `/new` in a bot's forever-chat (2.17.3): it never resets on its own, so ask which of the
    // two things a fresh start can mean here — a clean working context in THIS conversation, or
    // a new Bot Chat (this one retired, kept under the bot's past chats). Dismissing sends nothing.
    val botNewChoice by viewModel.botNewChoice.collectAsState()
    botNewChoice?.let { bot ->
        AlertDialog(
            shape = RoundedCornerShape(chat.keryx.app.presentation.ui.components.KeryxRadius.sheet),
            onDismissRequest = { viewModel.resolveBotNew(null) },
            title = { Text("Start fresh with ${bot.label}?", fontSize = KeryxType.titleLarge) },
            text = {
                Text(
                    "Compact keeps this conversation and clears ${bot.label}'s working context.\n\n" +
                        "A fresh Bot Chat retires this one — it stays readable under ${bot.label}'s " +
                        "past chats — and starts a new one.",
                    fontSize = KeryxType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveBotNew(true) }) { Text("Fresh Bot Chat") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveBotNew(false) }) { Text("Compact") }
            },
        )
    }

    // 2.3 §1: the configured heralds, resolved once for the whole app — every bubble, sigil and
    // spinner below reads its sender's light out of this. (Body left at its original indent so the
    // wrapper stays a two-line diff.)
    // 2.5: the tick vocabulary, resolved once. Provided here rather than built at each call site
    // so that Settings ▸ Interface ▸ "Haptic Feedback" has exactly one place to be obeyed.
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    // NOT keyed on hapticsEnabled: the instance must outlive every flip of the switch. Consumers
    // capture it inside blocks that never restart (pointerInput keyed on a message id), so handing
    // out a fresh instance per flip strands them on the old one — which on device looks like a
    // switch that is backwards. One permanent instance, reading the flag live.
    val hapticsOn = androidx.compose.runtime.rememberUpdatedState(hapticsEnabled)
    val keryxHaptics = remember(hapticFeedback, scope) {
        chat.keryx.app.presentation.ui.components.KeryxHaptics(
            hapticFeedback, { hapticsOn.value }, scope,
        )
    }

    // The completion tick: the agent stopped working. Fired on the awaiting -> idle edge and only
    // while the app is actually resumed — a phone in a pocket gets a notification for a finished
    // turn, and buzzing twice more for the same event is one signal too many.
    val hapticLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(hapticLifecycle, keryxHaptics) {
        hapticLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var wasAwaiting = viewModel.awaitingReply.value
            viewModel.awaitingReply.collect { awaiting ->
                if (wasAwaiting && !awaiting) keryxHaptics.completion()
                wasAwaiting = awaiting
            }
        }
    }

    // The way from a media card or a prose chip to the artifact viewer (2.13). Provided above
    // the nav host, not around the chat alone, so the Archive's media rows open it too.
    // Remembered so every bubble reading the local is not recomposed each time this screen is.
    val artifactOpener = remember(nav, viewModel.transportIsDirect) {
        chat.keryx.app.presentation.artifact.ArtifactOpener(canReadPaths = viewModel.transportIsDirect) { ref ->
            openSpace(KeryxDest.Artifact(path = ref.path, name = ref.name, roomId = ref.roomId, eventId = ref.eventId))
        }
    }
    // The demo (3.0 first run): leaving it is the sign-in, on a fresh process spine.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    fun leaveDemo() {
        viewModel.doors.leaveDemo()
        chat.keryx.app.presentation.ui.components.relaunchApp(appContext)
    }

    // The guided tour (3.0): where each pointable control is, one controller for every way in,
    // and the moves that take the app to the place a stop lives in (the chat, the drawer).
    val tourRegistry = remember { chat.keryx.app.presentation.tour.TourRegistry() }
    fun tourContext() = KeryxTour.Context(
        direct = viewModel.transportIsDirect,
        link = viewModel.hub.reasoningCaps.value != null,
        demo = viewModel.isDemo,
    )
    val tour = remember(viewModel) {
        chat.keryx.app.presentation.tour.TourController(
            // The chat and the drawer are always composed, so what they wear is the truth;
            // a space's targets only exist once it is open, and go by the door rules.
            catalog = {
                KeryxTour.catalog(tourContext()).filter {
                    (it.place != chat.keryx.core.tour.TourPlace.CHAT && it.place != chat.keryx.core.tour.TourPlace.DRAWER) ||
                        tourRegistry.isWorn(it.id)
                }
            },
            builtIn = { preset, ids -> KeryxTour.builtIn(preset, ids, tourContext()) },
        )
    }
    val tourStop = tour.run?.let { it.index to it.step.place }
    LaunchedEffect(tourStop) {
        val place = tourStop?.second ?: return@LaunchedEffect
        focusManager.clearFocus()
        keyboard?.hide()
        when (place) {
            chat.keryx.core.tour.TourPlace.CHAT -> { nav.home(); drawerState.close() }
            chat.keryx.core.tour.TourPlace.DRAWER -> { nav.home(); drawerState.open() }
            else -> KeryxDest.fromRoute(place)?.let { drawerState.close(); nav.open(it) }
        }
    }
    // However it ends (Done, the ✕, BACK), a tour that opened the drawer closes it again.
    val touring = tour.run != null
    var wasTouring by remember { mutableStateOf(false) }
    LaunchedEffect(touring) {
        if (wasTouring && !touring) drawerState.close()
        wasTouring = touring
    }
    // The agent pointing (3.0, keryx_tour): each call runs on the tour on screen, the same
    // engine and the same answers Desktop's renderer gives gui_tour, and what it did goes back
    // to the tool waiting on Hermes Link. Off screen, nothing is drawn and the agent is told.
    val tourLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel) {
        viewModel.agentTourCalls.collect { call ->
            if (!viewModel.agentTourCallOpen(call)) return@collect
            val result = if (!tourLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                chat.keryx.core.tour.TourResult(
                    success = false,
                    error = "Keryx isn't on screen on the phone right now, so nothing was shown.",
                )
            } else {
                val (action, problem) = chat.keryx.core.tour.TourAction.parse(call.args)
                if (action == null) chat.keryx.core.tour.TourResult(success = false, error = problem)
                else tour.act(action)
            }
            chat.keryx.app.util.KLog.i("KeryxTour") { "agent ${call.callId}: ${result.toJson()}" }
            viewModel.answerAgentTour(call, result.toJson())
        }
    }

    // First-visit hints (3.0): a space opened for the first time says what it's for, once its
    // header has arrived and held still. Never over a tour, and never twice.
    val hintDest = nav.top
    LaunchedEffect(hintDest, touring) {
        if (touring) return@LaunchedEffect
        val step = chat.keryx.app.presentation.tour.KeryxHints.due(hintDest, viewModel.prefs.hintsSeen)
            ?: return@LaunchedEffect
        val id = step.target ?: return@LaunchedEffect
        val worn = kotlinx.coroutines.withTimeoutOrNull(HINT_ARRIVAL_WAIT_MS) {
            while (!tourRegistry.isWorn(id)) kotlinx.coroutines.delay(50)
            true
        } ?: return@LaunchedEffect
        // The space's own arrival (a rise of a few dp) finishes first.
        kotlinx.coroutines.delay(HINT_SETTLE_MS)
        if (worn && nav.top == hintDest && tour.hint(step)) viewModel.prefs.markHintSeen(id)
    }

    // A tour asked for on arrival (the demo) starts before the chat has drawn: the stops are
    // chosen from what is worn when it starts, so the model pill, a beat behind the composer,
    // was left out. Wait until what the screen wears has held still a moment (at once from
    // Settings, where it already has).
    suspend fun awaitTourFloor() {
        kotlinx.coroutines.withTimeoutOrNull(TOUR_ARRIVAL_WAIT_MS) {
            var worn = emptyList<String>()
            var stillSince = System.currentTimeMillis()
            while (true) {
                val now = KeryxTour.ALL.filter(tourRegistry::isWorn)
                if (now != worn) { worn = now; stillSince = System.currentTimeMillis() }
                if (KeryxTour.COMPOSER in worn && System.currentTimeMillis() - stillSince >= TOUR_ARRIVAL_SETTLE_MS) break
                kotlinx.coroutines.delay(50)
            }
        }
    }

    // Settings' "Take the tour": the chat floor first, then the first stop.
    LaunchedEffect(Unit) {
        viewModel.tourRequested.collect { preset ->
            if (preset == null) return@collect
            viewModel.consumeTour()
            nav.home()
            awaitTourFloor()
            chat.keryx.app.util.KLog.i("KeryxTour") { "start $preset worn=${KeryxTour.ALL.filter(tourRegistry::isWorn)}" }
            val result = tour.start(preset)
            if (!result.success) result.error?.let { viewModel.toast(it) }
        }
    }

    // "What's new" (3.0): once, on the first launch of the release for someone who used Keryx
    // before it (the welcome marks a fresh install seen), and from Settings › About. Never in the
    // demo, never over a tour or a hint, and not on a launch that opened on an approval: that
    // launch is for answering it, so the look waits for the next one.
    LaunchedEffect(Unit) {
        if (viewModel.isDemo || !viewModel.prefs.whatsNewDue) return@LaunchedEffect
        awaitTourFloor()
        val atRest = tour.run == null && nav.top == null &&
            tourRegistry.isWorn(KeryxTour.COMPOSER) && !tourRegistry.isWorn(KeryxTour.APPROVAL)
        if (atRest) viewModel.requestWhatsNew()
    }
    LaunchedEffect(Unit) {
        viewModel.whatsNewRequested.collect { asked ->
            if (!asked) return@collect
            viewModel.consumeWhatsNew()
            nav.home()
            awaitTourFloor()
            if (tour.startSteps { ids -> KeryxTour.whatsNew(ids, tourContext()) }) viewModel.prefs.markWhatsNewSeen()
        }
    }

    CompositionLocalProvider(
        chat.keryx.app.presentation.tour.LocalTourRegistry provides tourRegistry,
        chat.keryx.app.presentation.ui.components.LocalKeryxHaptics provides keryxHaptics,
        chat.keryx.app.presentation.artifact.LocalArtifactOpener provides artifactOpener,
        chat.keryx.app.presentation.ui.components.LocalKeryxSnack provides snack,
        LocalHeraldConfig provides HeraldConfig(
            ids = chat.keryx.core.model.Heralds.parseIds(heraldIds),
            overrides = heraldAccents,
        )
    ) {
    Box(Modifier.fillMaxSize()) {
    // While the tour talks, the app under it is not there for TalkBack either: the card is
    // the only thing to read, as it is the only thing to touch.
    Box(Modifier.fillMaxSize().then(if (touring) Modifier.clearAndSetSemantics { } else Modifier)) {
    // The activity window's notice layer: above the composer at the floor; on the bottom edge
    // when the drawer or a place covers the composer (the notice must never sit on its text).
    chat.keryx.app.presentation.ui.components.KeryxSnackLayer(
        modifier = Modifier.fillMaxSize(),
        // Tap-In carries its own input (the steer bar), so a notice clears it there too.
        clearInput = (nav.atFloor || nav.top == KeryxDest.TapIn) && drawerState.targetValue == DrawerValue.Closed,
    ) {
    KeryxNavHost(
        nav = nav,
        originOf = { dest -> if (dest == KeryxDest.TapIn) viewModel.cloudBounds else null },
        root = {
    ModalNavigationDrawer(
        modifier = Modifier.nestedScroll(drawerAssist),
        drawerState = drawerState,
        // The scrim carries a breath of the accent: the room dims into the void, not into grey.
        scrimColor = androidx.compose.ui.graphics.lerp(Color.Black, MaterialTheme.colorScheme.primary, 0.14f).copy(alpha = 0.55f),
        drawerContent = {
            // BACK closes the open drawer; without this it fell through and left the app (QA 2.19.1).
            androidx.activity.compose.BackHandler(enabled = drawerState.targetValue == DrawerValue.Open) {
                scope.launch { drawerState.close() }
            }
            // Where a press lands in the drawer is where Bloom opens the next room from (3.0).
            // Observed on the Initial pass and never consumed, so every row behaves as before.
            val sheetOrigin = androidx.compose.runtime.remember { floatArrayOf(0f, 0f) }
            ModalDrawerSheet(
                modifier = Modifier
                    .onGloballyPositioned { val p = it.positionInRoot(); sheetOrigin[0] = p.x; sheetOrigin[1] = p.y }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                    e.changes.firstOrNull()?.let {
                                        chat.keryx.app.presentation.ui.components.RoomCrossingCue.noteTap(
                                            androidx.compose.ui.geometry.Offset(it.position.x + sheetOrigin[0], it.position.y + sheetOrigin[1]),
                                        )
                                    }
                                }
                            }
                        }
                    },
                drawerShape = DrawerShape,
                drawerContainerColor = Color.Transparent,
            ) {
                NavigationDrawerContent(
                    viewModel = viewModel,
                    onRoomSelected = { room ->
                        viewModel.selectRoom(room)
                        scope.launch { drawerState.close() }
                    },
                    onOpenSpace = openSpace,
                    // Visible while open OR mid-swing, so the emblem is alive as it slides in.
                    drawerVisible = drawerState.currentValue == DrawerValue.Open ||
                        drawerState.targetValue == DrawerValue.Open,
                    // A new session from the drawer's plus lands you IN it: the drawer
                    // slides away as the ViewModel moves onto the new room.
                    onConversationCreated = { scope.launch { drawerState.close() } },
                )
            }
        }
    ) {
        // The whole-screen backdrop: the living dusk sky (AGSL on 13+, static aurora before) —
        // bubbles and bars sit on top of it (transparent), so the sky reads as the actual room.
        // The ambient void (the accent pools adrift behind the chat) rides inside the sky
        // shader now — one dithered pass, so no gradient edge can survive it.
        // The Call is a full-screen overlay drawn over the whole room (below), not a Dialog —
        // see CallScreen for why. Its state lives here so the top bar can open it.
        // In the ViewModel, not saved state: a rotation keeps the call, but a process the system
        // killed must not reopen it by itself and start the microphone (2.19.1).
        var showCall by viewModel.callShown
        // The floor's sky rests while a place or the Call covers it (2.10): every place paints
        // its own opaque sky, so two shaders were running for one visible backdrop.
        Box(modifier = Modifier.fillMaxSize().keryxDuskSky(animate = nav.atFloor && !showCall)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column {
                TopAppBar(
                    title = {
                        Column {
                            chat.keryx.app.presentation.ui.components.KeryxWordmark(fontSize = KeryxType.headline)
                            currentRoom?.let { room ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Bot Mode (2.8): a bot's forever-chat wears the bot — its
                                    // sigil in its own light, its name, and the one-word chip
                                    // that says this conversation never resets.
                                    val botHere = viewModel.bots.botForSession(room.id)
                                    // A plain session on another profile (2.17.3) wears its
                                    // agent's sigil too — just not the forever-chat chip.
                                    val agentHere = botHere ?: viewModel.bots.agentOf(room.id)
                                    if (agentHere != null) {
                                        val light = chat.keryx.app.presentation.ui.components.botLightFor(agentHere.name, agentHere.label, agentHere.isDefault)
                                        chat.keryx.app.presentation.ui.components.HeraldSigil(light, fontSize = KeryxType.caption)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = if (botHere != null) botHere.label else room.name,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = KeryxType.body,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // The two chips below print 10sp of accent on a 14% wash of
                                    // the same accent. On parchment that is 2.77:1 — the label
                                    // was there and could not be read. The GROUND keeps the raw
                                    // accent (it is a light); the TEXT takes keryxAccentInk,
                                    // which hands the accent straight back on the void.
                                    val chipInk = chat.keryx.app.presentation.ui.components.keryxAccentInk()
                                    val chipShape = RoundedCornerShape(
                                        chat.keryx.app.presentation.ui.components.KeryxRadius.chip
                                    )
                                    if (botHere != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Bot Chat",
                                            color = chipInk,
                                            fontSize = KeryxType.micro,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .clip(chipShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                    // Which agent profile answers in this room (from the gateway's
                                    // room→profile routing map): a small tinted chip by the name.
                                    val caps by viewModel.hub.reasoningCaps.collectAsState()
                                    caps?.roomProfiles?.get(room.id)
                                        // "default" is the unnamed home profile — a "Default" chip
                                        // is noise; only the named secondaries earn the badge.
                                        ?.takeIf { !it.equals("default", ignoreCase = true) }
                                        ?.let { profile ->
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = profile.replaceFirstChar { it.uppercase() },
                                            color = chipInk,
                                            fontSize = KeryxType.micro,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .clip(chipShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        // The drawer button carries what the drawer holds (2.10): how many
                        // rooms have news you have not looked at — the one you are in
                        // excepted, you are looking at it. The Desktop's sidebar toggle does
                        // the same, and for the same reason: a closed drawer must not hide that
                        // something behind it wants you.
                        val rooms by viewModel.rooms.collectAsState()
                        val unreadRooms = remember(rooms, currentRoom?.id) {
                            rooms.count { it.hasUnread && it.id != currentRoom?.id }
                        }
                        // Something behind the drawer WAITS on you (2.14): a decision held in
                        // another session, or a mission blocked on your word. That is not news,
                        // it is a summons — so it wears the warm status colour, breathes, and the
                        // glyph gives one small nudge when the count rises. The room you are in is
                        // excepted: its request is already on screen.
                        val waiting by viewModel.sessions.needsYouIds.collectAsState()
                        val missionsWaiting by viewModel.missions.needsYouCount.collectAsState()
                        val urgent = waiting.count { it != currentRoom?.id } + missionsWaiting
                        val nudge = remember { Animatable(0f) }
                        var lastUrgent by remember { mutableStateOf(0) }
                        val reducedNudge by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
                        LaunchedEffect(urgent) {
                            if (urgent > lastUrgent && !reducedNudge) {
                                nudge.snapTo(1f)
                                nudge.animateTo(0f, spring(dampingRatio = 0.25f, stiffness = Spring.StiffnessMediumLow))
                            }
                            lastUrgent = urgent
                        }
                        // Read inside the badge's own scope below: read here, the pulse recomposed the
                        // whole app root every frame while something waited on you (3.0 perf).
                        val orb = chat.keryx.app.presentation.ui.components.breathingAlphaState(active = urgent > 0, low = 0.45f, periodMillis = 2200)
                        IconButton(
                            onClick = { focusManager.clearFocus(); scope.launch { drawerState.open() } },
                            modifier = Modifier.tourTarget(KeryxTour.DRAWER_BUTTON),
                        ) {
                            BadgedBox(badge = {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = urgent > 0,
                                    enter = chat.keryx.app.presentation.ui.components.keryxPop(),
                                    exit = chat.keryx.app.presentation.ui.components.keryxVanish(),
                                ) {
                                    // The badge stays solid and the breath is a halo around it: breathing the
                                    // fill itself took the number down to 1.35:1 on parchment at the low end
                                    // of each breath (3.0 accessibility pass).
                                    val warn = chat.keryx.app.presentation.ui.components.KeryxStatus.warn
                                    Badge(
                                        containerColor = warn,
                                        contentColor = chat.keryx.app.presentation.ui.components.contrastColorFor(warn),
                                        modifier = Modifier.drawBehind {
                                            drawCircle(
                                                warn.copy(alpha = 0.45f * orb.value),
                                                radius = size.maxDimension / 2f + 3.dp.toPx(),
                                            )
                                        },
                                    ) { Text(if (urgent > 9) "9+" else urgent.toString()) }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = urgent == 0 && unreadRooms > 0,
                                    enter = chat.keryx.app.presentation.ui.components.keryxPop(),
                                    exit = chat.keryx.app.presentation.ui.components.keryxVanish(),
                                ) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = chat.keryx.app.presentation.ui.components.contrastColorFor(MaterialTheme.colorScheme.primary),
                                    ) { Text(if (unreadRooms > 99) "99+" else unreadRooms.toString()) }
                                }
                            }) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.Sidebar,
                                    contentDescription = when {
                                        urgent > 0 -> "Menu, $urgent waiting on you"
                                        unreadRooms > 0 -> "Menu, $unreadRooms unread"
                                        else -> "Menu"
                                    },
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.graphicsLayer { rotationZ = 14f * nudge.value },
                                )
                            }
                        }
                    },
                    actions = {
                        // Hermes Link health, whispered: a tiny dot that breathes while tokens flow,
                        // dims when idle, warms red when the gateway is unreachable. Tap opens the
                        // Gateway — the live who/what/how of the system Keryx is pointed at. The dot
                        // reports on the link, so it lands on the space that is about the link; the
                        // Workshop is reached from the drawer, where you go looking for it on purpose.
                        chat.keryx.app.presentation.ui.components.LinkHealthDot(health = linkHealth, onClick = {
                            viewModel.hub.refreshReasoningCaps()
                            openSpace(KeryxDest.Gateway)
                        })
                        // Direct door: the rows ARE sessions, so "new session" means a new row.
                        // ONE tap makes it and lands you in it — no sheet, no title prompt (the
                        // first exchange titles it). Long-press for the sheet when you do want a
                        // title or a temporary session. (It used to send /new here, which resets
                        // the OPEN session in place: the glyph said "new" and the roster gained
                        // nothing; then it opened the sheet, which was a form standing between
                        // you and a blank page.) This is the only new-session glyph on the direct
                        // door — the drawer's plus is Matrix-only now. Offered even with no
                        // session open: it is how the first one gets made.
                        if (viewModel.transportIsDirect) {
                            var showNewSession by remember { mutableStateOf(false) }
                            var creating by remember { mutableStateOf(false) }
                            val haptics = chat.keryx.app.presentation.ui.components.LocalKeryxHaptics.current
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .tourTarget(KeryxTour.NEW_SESSION)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .combinedClickable(
                                        enabled = !creating,
                                        onClick = {
                                            creating = true
                                            // New is "another one of THESE": in a bot's chat or
                                            // session, the fresh session runs as that bot.
                                            viewModel.sessions.createSession("", profile = viewModel.bots.agentOf(currentRoom?.id)) { err ->
                                                creating = false
                                                if (err != null) viewModel.toast("Couldn't start a session. ${chat.keryx.core.model.FriendlyError.of(err.toString())}")
                                            }
                                        },
                                        onLongClick = {
                                            haptics.press()
                                            showNewSession = true
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.NewChat,
                                    contentDescription = "New session (hold for options)",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = if (creating) 0.4f else 1f),
                                )
                            }
                            if (showNewSession) {
                                chat.keryx.app.presentation.ui.components.NewChatSheet(
                                    viewModel = viewModel,
                                    onDismiss = { showNewSession = false },
                                )
                            }
                        }
                        // A scheduled run, open as a room: the pin is right here, where you
                        // finished reading it. Only over a run the Runs board knows (never over
                        // a conversation — those pin from the drawer), and it reads the run's
                        // own flag so the glyph and the shelf never disagree.
                        currentRoom?.let { room ->
                            val cronBoard by viewModel.hub.cron.collectAsState()
                            val board = cronBoard.data
                            if (board != null && board.isRun(room.id)) {
                                val pinned = board.isPinned(room.id)
                                IconButton(onClick = { viewModel.hub.cronSetPinned(room.id, !pinned) }) {
                                    Icon(
                                        if (pinned) chat.keryx.app.presentation.ui.components.KeryxGlyphs.PinFilled
                                        else chat.keryx.app.presentation.ui.components.KeryxGlyphs.Pin,
                                        contentDescription = if (pinned) "Unpin this run" else "Pin this run",
                                        tint = if (pinned) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                        if (currentRoom != null) {
                            // Matrix: a room is a profile, and "new session" there means /new —
                            // one tap sends it, same auto-send the command palette does, so the
                            // gateway's fresh-session reply lands in the chat itself.
                            // While a turn is LIVE, /new invalidates the running generation on the
                            // gateway (the run's result is discarded), so an accidental tap here
                            // was a silent run-killer (08-25 diagnosis) — confirm exactly when
                            // there is something to lose; idle taps stay one-tap.
                            var confirmNewSession by remember { mutableStateOf(false) }
                            val liveTurn by viewModel.liveTurnSigns.collectAsState()
                            if (!viewModel.transportIsDirect) IconButton(onClick = {
                                if (liveTurn || awaitingReply) confirmNewSession = true
                                else {
                                    viewModel.composer.recordCommandUse("/new")
                                    viewModel.sendMessage("/new")
                                }
                            }) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.NewChat,
                                    contentDescription = "New session",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (confirmNewSession) {
                                AlertDialog(
                                    shape = RoundedCornerShape(
                                        chat.keryx.app.presentation.ui.components.KeryxRadius.sheet
                                    ),
                                    onDismissRequest = { confirmNewSession = false },
                                    title = { Text("Start a new session?", fontSize = KeryxType.titleLarge) },
                                    text = {
                                        Text(
                                            "The agent is still working — /new ends the current run and its result is discarded.",
                                            fontSize = KeryxType.body,
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirmNewSession = false
                                            viewModel.composer.recordCommandUse("/new")
                                            viewModel.sendMessage("/new")
                                        }) { Text("End run & start new", color = MaterialTheme.colorScheme.error) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { confirmNewSession = false }) { Text("Cancel") }
                                    },
                                )
                            }
                            // Find in chat (2.16).
                            IconButton(onClick = { viewModel.setFindOpen(!viewModel.findOpen.value) }) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.Search,
                                    contentDescription = "Find in chat",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            // Reasoning moved to the composer footer (2.2, the Talaria
                            // treatment) — the dial now lives where the thinking happens.
                            // The Call (1.22): a voice conversation with this room's agent. Needs
                            // both voice endpoints; a missing one gets a pointer, not a dead mic.
                            IconButton(onClick = {
                                if (viewModel.voice.callReady()) showCall = true
                                else viewModel.toast("Set the STT and TTS endpoints in Settings → Voice first")
                            }) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.Phone,
                                    contentDescription = "Call",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
                // The demo says so, and the way out to a real gateway is always one tap.
                if (viewModel.isDemo) DemoStrip(onConnect = ::leaveDemo)
                }
            }
        ) { paddingValues ->
            // Skill Forge opens from two places — the Workshop's Skills rows (direct ViewModel call)
            // and in-chat SkillDistilled pills (via this CompositionLocal, since the render chain
            // doesn't carry the ViewModel). One shared target keeps a single hosted sheet.
            androidx.compose.runtime.CompositionLocalProvider(
                chat.keryx.app.presentation.ui.components.LocalSkillForgeOpener provides viewModel.hub::openSkillForge,
                // Quick-action chips (⟦keryx:ask⟧) send their option text as a normal message.
                chat.keryx.app.presentation.ui.components.LocalQuickActionSender provides viewModel::sendMessage,
            ) {
                ChatScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
            val skillForgeTarget by viewModel.hub.skillForgeTarget.collectAsState()
            skillForgeTarget?.let { name ->
                chat.keryx.app.presentation.ui.components.SkillForgeSheet(
                    skillName = name,
                    viewModel = viewModel,
                    onDismiss = viewModel.hub::closeSkillForge,
                )
            }
        }
        if (showCall) {
            chat.keryx.app.presentation.ui.components.CallScreen(
                viewModel = viewModel,
                roomName = currentRoom?.name ?: "Keryx",
                onDismiss = { showCall = false },
            )
        }
        }
    }
        },
        content = { dest ->
            when (dest) {
                KeryxDest.Archive -> chat.keryx.app.presentation.ui.components.ArchiveScreen(
                    viewModel = viewModel,
                    onDismissRequest = nav::back,
                )
                KeryxDest.Projects -> chat.keryx.app.presentation.ui.components.ProjectsSpace(
                    viewModel = viewModel,
                    // Project sessions can live outside the roster (cron runs, desktop work) —
                    // the name rides along so the floor can title them.
                    onOpenSession = { id, name -> viewModel.openSessionById(id, name); nav.back() },
                    onClose = nav::back,
                )
                KeryxDest.Shipyard -> chat.keryx.app.presentation.ui.components.ShipyardSpace(
                    viewModel = viewModel,
                    onClose = nav::back,
                )
                KeryxDest.Runs -> chat.keryx.app.presentation.ui.components.RunsSpace(
                    viewModel = viewModel,
                    // A cron run lives outside every roster — the title travels so the floor
                    // can name the room it adopts (direct door; Matrix reads in-space).
                    onOpenSession = { id, name -> viewModel.openSessionById(id, name); nav.back() },
                    onClose = nav::back,
                )
                KeryxDest.Bots -> chat.keryx.app.presentation.ui.components.BotsSpace(
                    viewModel = viewModel,
                    // A bot's chat is adopted as a roster row by the delegate; landing on the
                    // floor is the same move a run makes.
                    onOpened = { nav.back() },
                    onOpenRuns = { nav.open(KeryxDest.Runs) },
                    onOpenGroup = { room -> nav.open(KeryxDest.Group(room.roomId, room.name)) },
                    onClose = nav::back,
                )
                KeryxDest.Missions -> chat.keryx.app.presentation.ui.components.MissionsScreen(
                    viewModel = viewModel,
                    onDismissRequest = nav::back,
                )
                KeryxDest.Gateway -> chat.keryx.app.presentation.ui.components.GatewaySpace(
                    viewModel = viewModel,
                    health = linkHealth,
                    onDismiss = nav::back,
                )
                KeryxDest.Settings -> chat.keryx.app.presentation.ui.components.SettingsPlace(
                    viewModel = viewModel,
                    onClose = nav::back,
                )
                KeryxDest.TapIn -> {
                    val feed by viewModel.tapInFeed.collectAsState()
                    chat.keryx.app.presentation.tapin.TapInHost(
                        viewModel = viewModel,
                        itemsNewestFirst = feed.items,
                        structured = feed.structured,
                        onClose = nav::back,
                        standalone = false,
                    )
                }
                // A group chat (2.18): a hosted room of bots, followed while it is open.
                is KeryxDest.Group -> chat.keryx.app.presentation.ui.components.GroupChatSpace(
                    dest = dest,
                    viewModel = viewModel,
                    onClose = nav::back,
                )
                // A page the agent wrote (2.13): the one place that carries its own arguments.
                is KeryxDest.Artifact -> chat.keryx.app.presentation.artifact.ArtifactSpace(
                    dest = dest,
                    viewModel = viewModel,
                    onClose = nav::back,
                )
            }
        },
    )
    }
    }
    chat.keryx.app.presentation.tour.TourOverlay(
        controller = tour,
        registry = tourRegistry,
        onCta = { id ->
            when (id) {
                // The full tour ends on the gateway: one more Hermes, added where gateways live.
                KeryxTour.CTA_ADD_GATEWAY -> {
                    viewModel.jumpToSetting(chat.keryx.app.presentation.ui.components.SettingsRow.CONNECTION_GATEWAY.id)
                    openSpace(KeryxDest.Settings)
                }
                // The demo's tour ends on the real thing: out of the demo, into the sign-in.
                KeryxTour.CTA_CONNECT -> leaveDemo()
            }
        },
    )
    }
    }
}

/**
 * Hermes Link health as a single quiet dot: accent and breathing while tokens flow, steady when the
 * last turn/probe reached the gateway, dim when untested, warm red when unreachable, gone when the
 * side-channel is off. Tapping it toasts the state in words.
 */
/**
 * The reasoning control, dream-styled: a frosted rounded panel with a soft accent-gradient border
 * (same vocabulary as the reaction bar), effort levels drawn with rising intensity glyphs, and the
 * display/override actions tucked below a hairline. Effort selections persist via `--global`.
 */
@Composable
internal fun ReasoningMenu(
    expanded: Boolean,
    caps: chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?,
    onDismiss: () -> Unit,
    onCommand: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    // Compact (2.16.1): the panel took the model's full name as its width and 48 dp rows, so it
    // stood tall and wide off the pill. Now a capped width, one-line header, and short rows.
    val rowHeight = 38.dp
    val rowPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 220.dp),
        shape = shape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 16.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), accent2.copy(alpha = 0.22f))),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "REASONING",
                color = accent,
                fontSize = KeryxType.micro,
                letterSpacing = 2.4.sp,
            )
            Text(
                // The menu adapts to what the active brain actually supports (via
                // /keryx/capabilities): a local vLLM brain is a binary thinking switch, cloud
                // models take the full effort scale. Until the probe answers, show the generic
                // scale with a neutral subtitle.
                text = when {
                    caps == null -> "effort persists across sessions"
                    caps.mode == "binary" -> "${caps.model.ifBlank { "local brain" }} · on/off switch"
                    caps.mode == "none" -> "${caps.model.ifBlank { "model" }} · no reasoning dial"
                    caps.scope == "session" -> "${caps.model.ifBlank { "model" }} · this session's ladder"
                    else -> "${caps.model.ifBlank { "model" }} · effort scale"
                },
                color = chat.keryx.app.theme.quietInk(alpha = 0.55f),
                fontSize = KeryxType.micro,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        // Render exactly the levels the gateway declares for the active brain (it knows what
        // the serving stack validates — e.g. the local qwen stack takes low/medium/xhigh, not
        // the whole generic scale). The full generic ladder is only the no-caps fallback.
        val glyphFor = mapOf(
            "none" to "·", "minimal" to "▁", "low" to "▁▃", "medium" to "▁▃▅",
            "high" to "▁▃▅▇", "xhigh" to "▁▃▅▇█", "max" to "█████", "ultra" to "█████",
        )
        // Scope of a pick: this session (the gateway's own default for /reasoning) or every
        // session (--global, written to config.yaml). The old menu sent --global for every
        // tap, so one pick in a scratch room rewrote the profile default for good.
        var everySession by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        val noDial = caps?.mode == "none"
        val entries: List<Triple<String, String, String>> = if (noDial) {
            emptyList()
        } else if (!caps?.levels.isNullOrEmpty()) {
            caps!!.levels.map { arg ->
                val label = chat.keryx.core.model.ReasoningEffort.label(arg, caps.labels)
                val glyph = glyphFor[arg] ?: if (caps.mode == "binary" && arg != "none") "▁▃▅▇" else "·"
                Triple(arg, label, glyph)
            }
        } else {
            listOf("none", "minimal", "low", "medium", "high", "xhigh").map { arg ->
                Triple(arg, chat.keryx.core.model.ReasoningEffort.label(arg), glyphFor[arg] ?: "·")
            }
        }
        entries.forEach { (arg, label, glyph) ->
            val isCurrent = caps?.current == arg
            DropdownMenuItem(
                text = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            glyph,
                            color = accent.copy(alpha = 0.75f),
                            fontSize = KeryxType.micro,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            // Five blocks need the room: at 32 dp X-High's ladder wrapped a block
                            // onto a second line under the row.
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(end = 8.dp).width(44.dp),
                        )
                        Text(
                            label,
                            fontSize = KeryxType.body,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) accent else Color.Unspecified,
                        )
                    }
                },
                onClick = { onCommand(if (everySession) "$arg --global" else arg) },
                modifier = Modifier.height(rowHeight),
                contentPadding = rowPadding,
            )
        }
        if (noDial) {
            Text(
                "This model takes no effort level — Hermes sends none.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = KeryxType.caption,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            DropdownMenuItem(
                text = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            if (everySession) "Every session" else "This session only",
                            fontSize = KeryxType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = everySession,
                            onCheckedChange = { everySession = it },
                            modifier = Modifier.padding(start = 8.dp).scale(0.8f),
                        )
                    }
                },
                onClick = { everySession = !everySession },
                modifier = Modifier.height(rowHeight + 4.dp),
                contentPadding = rowPadding,
            )
        }
        HorizontalDivider(
            color = accent.copy(alpha = 0.12f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        DropdownMenuItem(
            text = { Text("Show reasoning", fontSize = KeryxType.body) },
            onClick = { onCommand("show") },
            modifier = Modifier.height(rowHeight),
            contentPadding = rowPadding,
        )
        DropdownMenuItem(
            text = { Text("Hide reasoning", fontSize = KeryxType.body) },
            onClick = { onCommand("hide") },
            modifier = Modifier.height(rowHeight),
            contentPadding = rowPadding,
        )
        DropdownMenuItem(
            text = {
                Text("Reset session override", fontSize = KeryxType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            onClick = { onCommand("reset") },
            modifier = Modifier.height(rowHeight),
            contentPadding = rowPadding,
        )
        // Steering left this menu in 2.6.2: mid-turn the composer's send button IS the steer
        // (tap steers, hold queues) — a verb hidden behind the reasoning pill was the
        // affordance nobody found.
    }
}

/** How long a tour asked for on arrival waits for the chat to be drawn, and settles after. */
private const val TOUR_ARRIVAL_WAIT_MS = 3_000L
private const val TOUR_ARRIVAL_SETTLE_MS = 700L

/** How long a first-visit hint waits for its space's header, and the pause after it arrives. */
private const val HINT_ARRIVAL_WAIT_MS = 2_000L
private const val HINT_SETTLE_MS = 450L

/**
 * The demo's one line of chrome (3.0 first run): it says this is a demo and is the way out,
 * a quiet accent-edged pill under the top bar.
 */
@Composable
private fun DemoStrip(onConnect: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    // The word and the arrow are text-sized, so they take the accent's ink, not its light: the
    // bare accent read 2.68:1 on the pill's own tint on parchment (3.0 accessibility pass).
    // The pill sits on the sky's glow at the top of the screen, a deeper ground than its own 10%
    // wash: held to both (a 32% wash stands in for the glow, as measured on both grounds).
    val page = MaterialTheme.colorScheme.background
    val ink = chat.keryx.app.theme.KeryxInk.readable(
        chat.keryx.app.presentation.ui.components.keryxAccentInk(),
        listOf(accent.copy(alpha = 0.10f).compositeOver(page), accent.copy(alpha = 0.32f).compositeOver(page)),
    )
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Surface(
            onClick = onConnect,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            color = accent.copy(alpha = 0.10f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp).heightIn(min = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // One paragraph, so at large text it wraps as a sentence does, not as two blocks
                // side by side (200% font split it into three ragged lines).
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)) { append("Demo") }
                        append("  ·  Connect your gateway")
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = KeryxType.caption,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
