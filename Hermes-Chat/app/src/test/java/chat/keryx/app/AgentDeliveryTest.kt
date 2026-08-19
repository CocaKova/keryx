package chat.keryx.app

import chat.keryx.app.domain.model.AgentDelivery
import chat.keryx.app.domain.model.AgentDeliveryCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shapes from desktop's AGENT_MESSAGE_RE suite, ported through Talaria — kept byte-compatible. */
class AgentDeliveryTest {

    @Test
    fun `robot glyph form`() {
        val d = AgentDelivery.parse("Message from 🤖 Hermes: hello there")!!
        assertEquals("Hermes", d.sender)
        assertEquals("Hermes", d.handle)
        assertEquals("hello there", d.body)
    }

    @Test
    fun `named handle form`() {
        val d = AgentDelivery.parse("Message from 🤖 Eats Tests (@mr-tester): run them all")!!
        assertEquals("Eats Tests", d.sender)
        assertEquals("mr-tester", d.handle)
        assertEquals("run them all", d.body)
    }

    @Test
    fun `emoji-less form`() {
        val d = AgentDelivery.parse("Message from Sterling: ledger is reconciled")!!
        assertEquals("Sterling", d.sender)
        assertEquals("ledger is reconciled", d.body)
    }

    @Test
    fun `legacy bracket form`() {
        val d = AgentDelivery.parse("[Message from agent 'Owl'] scroll approved")!!
        assertEquals("Owl", d.sender)
        assertEquals("Owl", d.handle)
        assertEquals("scroll approved", d.body)
    }

    @Test
    fun `anchored - cannot fire mid-prose or on lookalikes`() {
        assertNull(AgentDelivery.parse("I got a Message from 🤖 Hermes: hello"))
        // The OUT-OF-BAND mid-turn convention IS the human — must never be claimed.
        assertNull(
            AgentDelivery.parse(
                "[OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered mid-turn] hey",
            ),
        )
        assertNull(AgentDelivery.parse("just some text"))
    }

    @Test
    fun `multiline body survives`() {
        val d = AgentDelivery.parse("Message from 🤖 Sy: line one\nline two")!!
        assertEquals("line one\nline two", d.body)
    }

    @Test
    fun `an ordinary chat message is never a delivery`() {
        // The gate that keeps every normal Matrix message out of the notice path.
        assertNull(AgentDelivery.parse("morning — did the deploy land?"))
        assertNull(AgentDelivery.parse("Message received, thanks"))
    }
}

/** The SENT half, plus the shapes a real 0.20.2 gateway produced on 2026-08-17. */
class AgentDeliveryCommandTest {

    private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `canonical delivery command`() {
        val cmd = "hermes -p turqoise chat --in ~ -c \"Bot Chat\" -Q -q \"Message from 🤖 Hermes (@hermes): hi there\""
        assertEquals("turqoise", AgentDeliveryCommand.targetOf(cmd))
    }

    @Test
    fun `wrapped in a compound command`() {
        val cmd = "cd ~ && timeout 240 hermes -p mr-tester chat --in \"~\" -Q -q \"Message from 🤖 Hermes: hello\""
        assertEquals("mr-tester", AgentDeliveryCommand.targetOf(cmd))
    }

    @Test
    fun `the shape a live 0-20-2 gateway actually stored`() {
        val cmd = "hermes -p theo chat -c \"Inbox\" --create-if-missing --max-turns 1 -Q " +
            "-q \"Message from 🤖 SILAS (@default): ping from the sender-side test, reply with just pong\""
        assertEquals("theo", AgentDeliveryCommand.targetOf(cmd))
        assertEquals(
            "theo",
            AgentDeliveryCommand.targetOfCall("terminal", """{"command": ${quoted(cmd)}, "timeout": 120}"""),
        )
    }

    @Test
    fun `an ordinary command is not a delivery`() {
        assertNull(AgentDeliveryCommand.targetOf("hermes -p sterling chat -q \"summarise the ledger\""))
        assertNull(AgentDeliveryCommand.targetOf("ls -p /tmp"))
        assertNull(AgentDeliveryCommand.targetOf("grep -p 'Message from' notes.md"))
    }

    @Test
    fun `only a terminal call can be a delivery`() {
        val args = """{"command":"hermes -p theo chat -Q -q \"Message from 🤖 X: hi\""}"""
        assertEquals("theo", AgentDeliveryCommand.targetOfCall("terminal", args))
        assertNull(AgentDeliveryCommand.targetOfCall("write_file", args))
    }

    @Test
    fun `malformed or absent args never throw`() {
        assertNull(AgentDeliveryCommand.targetOfCall("terminal", ""))
        assertNull(AgentDeliveryCommand.targetOfCall("terminal", "{not json"))
        assertNull(AgentDeliveryCommand.targetOfCall("terminal", """{"command":42}"""))
    }

    // --- Keryx's own divergence ----------------------------------------------------------------

    @Test
    fun `Keryx reads the command straight out of a fenced call's args`() {
        // Keryx parses tool calls out of rendered text, so `args` is the command itself — no JSON
        // envelope to unwrap. Talaria's path only ever sees the JSON form.
        val cmd = "hermes -p juno chat -Q -q \"Message from 🤖 SILAS: status?\""
        assertEquals("juno", AgentDeliveryCommand.targetOfCall("terminal", cmd))
    }

    @Test
    fun `a multi-line fenced command still resolves`() {
        val cmd = "cd ~/workspace &&\n  hermes -p milo chat -Q \\\n    -q \"Message from 🤖 SILAS: ping\""
        assertEquals("milo", AgentDeliveryCommand.targetOfCall("terminal", cmd))
    }

    @Test
    fun `a fenced note is unwrapped before the boundary cut`() {
        // Keryx carries header-less tool output as a fenced note; the fence must not survive into
        // the reply, and the session_id cut still has to land.
        val note = "```\nsession_id: 20260819_101112_aa11bb\npong\n```"
        assertEquals("pong", AgentDeliveryCommand.replyText(note))
    }

    @Test
    fun `a fenced note with a language tag is unwrapped too`() {
        assertEquals("all set", AgentDeliveryCommand.replyText("```bash\nsession_id: abc\nall set\n```"))
    }

    // --- the boundary rule ---------------------------------------------------------------------

    @Test
    fun `reply drops the session_id bookkeeping line`() {
        assertEquals("pong", AgentDeliveryCommand.replyText("session_id: 20260817_173323_8ac183\npong"))
    }

    @Test
    fun `everything before the session_id boundary is bookkeeping, not the reply`() {
        val out = """
            Warning: Unknown toolsets: messaging
            Session 20260817_174136_c401e5 found but has no messages. Starting fresh.
            ⚠ Deprecated .env settings detected:
              ⚠ TERMINAL_CWD=/home/cocakova found in .env — this is deprecated.

            ┌─ Reasoning ──────────────────────────────────┐
            The user is sending a test message from the SILAS profile.
            No tool needed. Just reply "pong."

            session_id: 20260817_174136_c401e5
            pong
        """.trimIndent()
        assertEquals("pong", AgentDeliveryCommand.replyText(out))
    }

    @Test
    fun `a multi-line answer after the boundary survives whole`() {
        val out = "session_id: abc\nfirst line\n\nsecond line"
        assertEquals("first line\n\nsecond line", AgentDeliveryCommand.replyText(out))
    }

    @Test
    fun `no boundary means keep everything - a wrong cut is worse than noise`() {
        assertEquals("just the answer", AgentDeliveryCommand.replyText("just the answer"))
    }

    @Test
    fun `reply unwraps a JSON terminal payload`() {
        assertEquals(
            "on it",
            AgentDeliveryCommand.replyText("""{"output":"session_id: abc\non it","exit_code":0}"""),
        )
    }

    @Test
    fun `an echoed prefix in the reply is addressing, not content`() {
        assertEquals(
            "ledger reconciled",
            AgentDeliveryCommand.replyText("Message from 🤖 Sterling (@sterling): ledger reconciled"),
        )
    }

    @Test
    fun `a plain reply passes through untouched`() {
        assertEquals("all done\nno errors", AgentDeliveryCommand.replyText("all done\nno errors"))
    }
}
