package chat.keryx.core

import chat.keryx.core.model.AgentDelivery
import chat.keryx.core.model.AgentNotices
import chat.keryx.core.model.Heralds
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentNoticeTest {
    private fun msg(content: String, sender: SenderType = SenderType.HERMES, delivery: AgentDelivery? = null) =
        Message(id = "1", roomId = "r", sender = sender, content = content, timestamp = 1L, senderName = "Hermes", agentDelivery = delivery)

    @Test fun botChatSpeaksAsTheBot() {
        val n = AgentNotices.compose(msg("Done.\nMore"), conversation = "Bot Chat", botLabel = "Theo", botHandle = "theo")
        assertEquals("Theo", n.speaker)
        assertEquals("Theo", n.conversation)
        assertEquals("bot:theo", n.speakerKey)
        assertEquals("Done.", n.line)
        assertFalse(n.relayed)
        assertEquals("${Heralds.SIGIL} Theo", n.title)
    }

    @Test fun plainSessionKeepsTheSessionName() {
        val n = AgentNotices.compose(msg("hi"), conversation = "Inbox")
        assertEquals("Hermes", n.speaker)
        assertEquals("Inbox", n.conversation)
        assertEquals("${Heralds.SIGIL} Hermes · Inbox", n.title)
    }

    @Test fun deliveryMakesTheOtherAgentTheSpeaker() {
        val d = AgentDelivery.parse("Message from 🤖 Juno (@juno): Draft is ready, take a look.")!!
        val n = AgentNotices.compose(msg("", sender = SenderType.SYSTEM, delivery = d), "Bot Chat", botLabel = "Theo", botHandle = "theo")
        assertTrue(n.relayed)
        assertEquals("Juno", n.speaker)
        assertEquals("agent:juno", n.speakerKey)
        assertEquals("Draft is ready, take a look.", n.line)
        assertEquals("Juno → Theo", n.title)
    }

    @Test fun lineStripsMarkersAndHandlesMedia() {
        assertEquals("🖼 Photo", AgentNotices.line("", MediaKind.IMAGE, ""))
        assertEquals("📎 notes.pdf", AgentNotices.line("", MediaKind.FILE, "notes.pdf"))
        assertEquals("New message", AgentNotices.line("   ", null, ""))
        val long = "x".repeat(400)
        assertEquals(160, AgentNotices.line(long, null, "").length)
    }
}
