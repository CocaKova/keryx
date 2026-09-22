package chat.keryx.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import chat.keryx.app.KeryxApp
import chat.keryx.app.MainActivity
import chat.keryx.app.notify.KeryxNotifications
import chat.keryx.app.presentation.ui.components.KeryxStatus
import chat.keryx.app.theme.BackgroundDark
import chat.keryx.app.theme.BackgroundLight
import chat.keryx.app.theme.HermesAmber
import chat.keryx.app.theme.SurfaceDark
import chat.keryx.app.theme.SurfaceLight
import chat.keryx.app.theme.SurfaceVariantDark
import chat.keryx.app.theme.SurfaceVariantLight
import chat.keryx.app.theme.TextPrimaryDark
import chat.keryx.app.theme.TextPrimaryLight
import chat.keryx.app.theme.TextSecondaryDark
import chat.keryx.app.theme.TextSecondaryLight
import chat.keryx.core.model.Heralds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The home-screen widget (2.13 Part B): the agent, glanced at.
 *
 * One card, four lines — the link, the newest session and what it last said, the turn in
 * flight if there is one, a mono footer that says when this was true. It is the run notice's
 * sibling on the other surface: same facts, same tap (the room; the turn itself while one is
 * running), and it never asks the gateway for anything the process does not already hold or
 * can peek at without a live agent ([WidgetSnapshot]).
 *
 * Glance draws it as RemoteViews, so the launcher owns the paint and the theme: colours are
 * handed over as day/night pairs built from the app's own tokens, and the launcher picks. No
 * palette lives here. Nothing animates — a widget is a photograph, and the refresh cadence
 * ([WidgetRefreshWorker] plus [refresh] from the run path) is what keeps it honest.
 */
class KeryxWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = withContext(Dispatchers.IO) { runCatching { WidgetSnapshot.read(context) }.getOrNull() }
            ?: WidgetState.empty(WidgetState.Link.OFFLINE)
        val accent = (context.applicationContext as? KeryxApp)
            ?.settingsRepository?.accentColorHex
            ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
            ?: HermesAmber
        val stamp = runCatching { LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }.getOrDefault("")
        provideContent {
            GlanceTheme(colors = colorsFor(accent)) {
                Card(state, stamp)
            }
        }
    }

    /**
     * The app's two grounds as one day/night scheme. Built from the theme's own tokens (the
     * same values `HermesChatTheme` seats in its schemes) rather than a copy of them, so a
     * retuned parchment or void reaches the widget without a second edit.
     */
    private fun colorsFor(accent: Color) = ColorProviders(
        light = lightColorScheme(
            primary = accent,
            background = BackgroundLight,
            surface = SurfaceLight,
            surfaceVariant = SurfaceVariantLight,
            onBackground = TextPrimaryLight,
            onSurface = TextPrimaryLight,
            onSurfaceVariant = TextSecondaryLight,
        ),
        dark = darkColorScheme(
            primary = accent,
            background = BackgroundDark,
            surface = SurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onBackground = TextPrimaryDark,
            onSurface = TextPrimaryDark,
            onSurfaceVariant = TextSecondaryDark,
        ),
    )

    @Composable
    private fun Card(state: WidgetState, stamp: String) {
        val context = LocalContext.current
        val colors = GlanceTheme.colors
        val ink = colors.onSurface
        val faded = colors.onSurfaceVariant
        // Same signals the transcript's status dots use, both grounds — the launcher picks.
        val signal = when (state.link) {
            WidgetState.Link.LIVE -> KeryxStatus.goodDayNight
            WidgetState.Link.RECONNECTING -> KeryxStatus.warnDayNight
            WidgetState.Link.OFFLINE -> KeryxStatus.idleDayNight
        }.let { ColorProvider(day = it.day, night = it.night) }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(colors.surface)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity(openIntent(context, state)))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // The link, as a dot and a word — the one line that must be readable at arm's length.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(signal)) {}
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = state.title,
                    style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = state.preview,
                style = TextStyle(color = ink, fontSize = 13.sp),
                maxLines = 2,
                modifier = GlanceModifier.fillMaxWidth(),
            )
            Spacer(GlanceModifier.defaultWeight())
            // The turn in flight wears the accent: it is the one thing on the card that is
            // happening rather than done. At rest the slot stays empty rather than saying so.
            state.run?.let { run ->
                Text(
                    text = run.line,
                    style = TextStyle(color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(4.dp))
            }
            Text(
                text = listOf(Heralds.SIGIL, state.link.word, stamp).filter { it.isNotBlank() }.joinToString(" · "),
                style = TextStyle(color = faded, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
        }
    }

    /**
     * The same intent the notices build ([KeryxNotifications] `tapIntent`): the room by id, and
     * `EXTRA_TAP_IN` while a turn runs so the tap lands inside it. Rebuilt on every draw — the
     * extras are the state.
     */
    private fun openIntent(context: Context, state: WidgetState): Intent =
        Intent(context, MainActivity::class.java).apply {
            // NEW_TASK because the launcher fires this from no activity of ours.
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            state.roomId?.let { putExtra(KeryxNotifications.EXTRA_ROOM_ID, it) }
            if (state.tapIn) putExtra(KeryxNotifications.EXTRA_TAP_IN, true)
        }

    companion object {
        /** Repaints are floored: the run path asks once a second and RemoteViews are not free. */
        private const val MIN_GAP_MS = 4_000L

        @Volatile private var lastAt = 0L
        @Volatile private var pending: Job? = null

        /**
         * Redraw every placed instance. Safe from any thread and any state; a no-op when no
         * widget is placed. Bursts coalesce: a call inside the floor schedules ONE trailing
         * repaint at the floor's edge, so the last word (a turn ending) is never dropped, only
         * delayed by at most [MIN_GAP_MS].
         */
        fun refresh(context: Context) {
            val app = context.applicationContext as? KeryxApp ?: return
            val now = System.currentTimeMillis()
            val wait = MIN_GAP_MS - (now - lastAt)
            if (wait <= 0) {
                lastAt = now
                app.appScope.launch { runCatching { KeryxWidget().updateAll(app) } }
                return
            }
            if (pending?.isActive == true) return
            pending = app.appScope.launch {
                delay(wait)
                lastAt = System.currentTimeMillis()
                runCatching { KeryxWidget().updateAll(app) }
            }
        }
    }
}

/** The manifest's receiver; also where the periodic refresh is born and buried with the widget. */
class KeryxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KeryxWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.cancel(context)
    }
}
