package chat.keryx.app

import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.MessageParser
import chat.keryx.app.presentation.ui.components.groupChatItems
import chat.keryx.app.presentation.ui.components.isTelemetryMessage
import chat.keryx.app.presentation.ui.components.isToolMessage
import org.junit.Test

/** Replays the exact 2026-07-10 21:09 turn from the live room to find where batching broke. */
class ReplayIncidentTest {

    private fun msg(id: String, sender: SenderType, body: String, ts: Long) = Message(
        id = id, sessionId = "!room", sender = sender, content = body,
        timestamp = ts, senderId = if (sender == SenderType.ME) "@jonny:x" else "@silas:x",
        senderName = "x",
    )

    @Test
    fun replayTurn() {
        val chrono = listOf(
            msg("m1", SenderType.ME, "I noticed the trend monitor cron job is still set to glm. Can you ensure all cron jobs are set to use the main model?", 1),
            msg("m2", SenderType.HERMES, "⏰ Scheduling list\n💻 terminal\n```\npython3 -c \" ...\n```", 2),
            msg("m3", SenderType.HERMES, "Let me check what the current default model is for the \"custom\" provider.\n\n", 3),
            msg("m4", SenderType.HERMES, "```\npython3 -c \" ...\n``` (×2)\n🔎 Searching files for glm", 4),
            msg("m5", SenderType.HERMES, "Let me check the full config for any glm references.\n\n", 5),
            msg("m6", SenderType.HERMES, "💭 **Reasoning:**\n```\nThe GLM references are all in cached files and a backup config file - not in the active config.yaml.\n```\n\nGood news — none of the active cron jobs are actually set to GLM. Here's the situation:\n\n**All 6 cron jobs:**\n| Job | Model |\n|-----|-------|\n| Daily AI News Briefing | inherits session default |", 6),
            msg("m7", SenderType.HERMES, "qwen3.6-35b-nvfp4 · 13% · ~", 7),
        )
        // The accumulate-anchored tool message must classify as a TOOL message, not telemetry —
        // the ⏰ first line (a cronjob tool-progress line) must not demote the whole run.
        val m2 = chrono[1]
        org.junit.Assert.assertTrue(isToolMessage(m2))
        org.junit.Assert.assertFalse(isTelemetryMessage(m2))

        val items = groupChatItems(chrono.asReversed())
        val runs = items.filterIsInstance<ChatRenderItem.ToolRun>()
        org.junit.Assert.assertTrue("expected a batched tool run, got $items", runs.isNotEmpty())
        // The header-less fence continuation (m4) folds into the run, never a stray code bubble.
        org.junit.Assert.assertTrue(
            items.filterIsInstance<ChatRenderItem.Single>().none { it.message.id == "m4" },
        )
        // The answer (m6) stays a visible bubble; the footer (m7) merged into it.
        org.junit.Assert.assertTrue(
            items.filterIsInstance<ChatRenderItem.Single>().any { it.message.id == "m6" },
        )
    }

    @Test
    fun singleLineCheckinsStayTelemetry() {
        // The heartbeat contract: one line under a check-in prefix is always quiet telemetry,
        // even when it pattern-matches a tool shape.
        for (line in listOf(
            "⏰ Scheduling update",
            "⏰ Scheduling update (×2)",
            "⏳ Working — iteration 5/90",
            "🗜 Compacting context — summarizing earlier conversation…",
        )) {
            org.junit.Assert.assertTrue(line, MessageParser.isTelemetryMessage(line))
        }
    }

    @Test
    fun multiLineCronBriefStaysTelemetry() {
        // No tool content anywhere → a multi-line check-in block is still plumbing.
        val brief = "⏰ Daily AI News Briefing\nThree stories today.\nEveryone shipped models."
        org.junit.Assert.assertTrue(MessageParser.isTelemetryMessage(brief))
    }
}
