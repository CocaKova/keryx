package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient.HubJob
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.CronHumanize
import chat.keryx.core.model.CronJobCard
import chat.keryx.core.model.CronRun

/**
 * RUNS — the harvest of Talaria's CronSpace (plan §5): what this gateway does on a schedule,
 * as something you READ. You don't converse with the Daily Brief; each job is one card, its
 * runs beneath it, the newest run's own headline instead of the prompt that produced it, and
 * an unread ledger that counts only what landed since you last looked. Jobs (the sibling tab)
 * manages the schedules; this reads their work.
 */
@Composable
internal fun RunsTab(viewModel: ChatViewModel) {
    val panel by viewModel.hub.cron.collectAsState()
    var openRunId by remember { mutableStateOf<String?>(null) }

    val board = panel.data
    openRunId?.let { id ->
        board?.runsById?.get(id)?.let { session ->
            SessionTranscript(session = session, viewModel = viewModel, onBack = { openRunId = null })
            return
        }
    }

    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What runs on a schedule — read, don't converse",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )
            val unread = board?.unread
            if (unread != null && unread.any) {
                TextButton(onClick = { viewModel.hub.cronMarkAllSeen() }) {
                    Text("Mark ${unread.total} read", fontSize = 12.sp)
                }
            }
        }
        when {
            board == null -> PanelLoading()
            board.cards.isEmpty() -> Text(
                "Nothing is scheduled on this gateway.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(board.cards, key = { it.name }) { card ->
                    RunCard(
                        card = card,
                        job = board.jobsByName[card.name],
                        unreadCount = board.unread.countFor(card.name),
                        isNew = { board.unread.isNew(it) },
                        viewModel = viewModel,
                        onOpenRun = { run ->
                            viewModel.hub.cronMarkSeen(run.id)
                            openRunId = run.id
                        },
                    )
                }
            }
        }
    }
}

/** Stable identity hues: people find "the gold one" faster than a name in a column of names. */
private val RUN_TINTS = listOf(
    Color(0xFFE55A00), Color(0xFF8B5CF6), Color(0xFF2E8B8B),
    Color(0xFFB8860B), Color(0xFF5F7A2E), Color(0xFFAD5A6B),
)

@Composable
private fun RunCard(
    card: CronJobCard,
    job: HubJob?,
    unreadCount: Int,
    isNew: (String) -> Boolean,
    viewModel: ChatViewModel,
    onOpenRun: (CronRun) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val good = KeryxStatus.good
    val bad = KeryxStatus.bad
    var open by rememberSaveable("runs-${card.name}") { mutableStateOf(false) }
    val tint = RUN_TINTS[CronHumanize.tintIndex(card.name, RUN_TINTS.size)]

    // The newest run's own headline — fetched once per run id (the delegate caches), so a
    // settled card costs nothing on re-composition or poll.
    val digest by produceState<chat.keryx.core.model.CronDigest?>(null, card.latest?.id) {
        value = card.latest?.let { viewModel.hub.cronDigest(it.id) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(onSurface.copy(alpha = 0.04f))
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint.copy(alpha = 0.85f)))
            Spacer(Modifier.width(8.dp))
            Text(
                card.name,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (unreadCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "$unreadCount new",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(if (open) "▾" else "▸", color = quiet.copy(alpha = 0.6f), fontSize = 11.sp)
        }

        // The meta line: schedule in words, distance to the next run, last verdict.
        val meta = buildList {
            job?.let { j ->
                CronHumanize.schedule(j.scheduleDisplay).takeIf { it.isNotBlank() }?.let { add(it) }
                j.nextRunAt?.let { CronHumanize.nextIn(it, System.currentTimeMillis()) }?.let { add(it) }
                if (!j.enabled) add("paused")
            }
            if (!card.scheduled) add("job no longer scheduled")
            if (card.neverRun) add("no runs yet")
        }
        if (meta.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 2.dp)) {
                val failed = job?.lastStatus?.contains("error", ignoreCase = true) == true ||
                    job?.lastError?.isNotBlank() == true
                if (job != null && !card.neverRun) {
                    Text(
                        if (failed) "✕" else "✓",
                        fontSize = 9.sp,
                        color = if (failed) bad else good.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    meta.joinToString(" · "),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    color = quiet.copy(alpha = 0.8f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // The newest run's own words — title and lead, never the prompt that produced it.
        val d = digest
        if (!card.neverRun && d?.title != null) {
            Text(
                d.title!!,
                fontSize = 12.5.sp, color = onSurface.copy(alpha = 0.85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 5.dp),
            )
            d.lead?.let {
                Text(
                    it,
                    fontSize = 11.5.sp, color = quiet.copy(alpha = 0.85f), lineHeight = 15.sp,
                    maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, top = 1.dp),
                )
            }
        }

        if (open && card.runs.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            card.runs.take(20).forEach { run ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenRun(run) }
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                ) {
                    // The unread dot dies the moment the run is opened — the ledger, visible.
                    Box(
                        Modifier
                            .padding(start = 12.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                if (isNew(run.id)) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // The gateway titles runs "<job> · <when>"; the job half is the card.
                        run.title.substringAfter(" · ", run.title),
                        fontSize = 12.sp, color = onSurface.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("read ▸", fontSize = 10.sp, color = quiet.copy(alpha = 0.6f))
                }
            }
            if (card.runs.size > 20) {
                Text(
                    "${card.runs.size - 20} older runs in Workshop ▸ Sessions",
                    fontSize = 10.5.sp, color = quiet.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                )
            }
        }
    }
}
