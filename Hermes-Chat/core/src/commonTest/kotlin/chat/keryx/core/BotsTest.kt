package chat.keryx.core

import chat.keryx.core.model.BotChatRef
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.BotRoster
import chat.keryx.core.model.BotsJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotsTest {
    private val now = 1_800_000_000_000L
    private fun bot(
        name: String,
        title: String = "",
        lastActive: Long = 0L,
        hidden: Boolean = false,
        managed: Boolean = false,
        isDefault: Boolean = false,
        displayName: String = "",
    ) = BotProfile(
        name = name, title = title, hidden = hidden, managed = managed, isDefault = isDefault,
        displayName = displayName,
        canonical = if (lastActive > 0) BotChatRef(id = "s-$name", resolvedId = "s-$name", lastActive = lastActive) else null,
    )

    // ---- labels, handles, tags ----------------------------------------------------------

    @Test fun defaultProfileAnswersToHermes() {
        val d = bot("default", isDefault = true)
        assertEquals("hermes", d.handle)
        assertTrue("hermes" in d.tags)
        assertTrue("default" in d.tags)
    }

    @Test fun labelPrefersTitleThenFriendlyNameThenPrettyName() {
        assertEquals("Research Buddy", bot("rb", title = "Research Buddy").label)
        assertEquals("Milo", bot("milo", displayName = "Milo").label)
        assertEquals("Mc Builder", bot("mc-builder").label)
    }

    @Test fun renamedBotStaysTaggableByTitleSlugAndOldName() {
        val b = bot("researcher", title = "Research Buddy")
        assertTrue("research-buddy" in b.tags)
        assertTrue("researchbuddy" in b.tags)
        assertTrue("researcher" in b.tags)
    }

    @Test fun slugAndPretty() {
        assertEquals("research-buddy", BotRoster.slug("  Research Buddy! "))
        assertEquals("", BotRoster.slug("!!!"))
        assertEquals("Theo", BotRoster.pretty("theo"))
        assertTrue(BotRoster.validName("mc-builder"))
        assertFalse(BotRoster.validName("Mc Builder"))
        assertFalse(BotRoster.validName("-x"))
    }

    // ---- routines -------------------------------------------------------------------------

    @Test fun routinesAreTaggedJobs() {
        val jobs = listOf("[bot:theo] Morning brief", "Daily digest", "[bot:Theo] Inbox sweep", "[bot:juno] Plan")
        assertEquals(listOf("[bot:theo] Morning brief", "[bot:Theo] Inbox sweep"), BotRoster.routinesOf(jobs, "theo"))
        assertEquals("Morning brief", BotRoster.routineLabel("[bot:theo] Morning brief"))
        assertNull(BotRoster.routineOwner("Daily digest"))
        assertEquals("[bot:theo]", BotRoster.routineTag("theo"))
    }

    // ---- canonical row ----------------------------------------------------------------

    @Test fun canonicalRowIsRootTitleOrBareTitle() {
        assertTrue(BotRoster.isCanonicalRow("Bot Chat", null))
        assertTrue(BotRoster.isCanonicalRow("Bot Chat (compacted)", "Bot Chat"))
        assertFalse(BotRoster.isCanonicalRow("Bot Chat", "Inbox"))
        assertFalse(BotRoster.isCanonicalRow("Group: Ops", ""))
    }

    // ---- ordering + activity --------------------------------------------------------------

    @Test fun activeInsideWindowOrBusy() {
        val fresh = bot("a", lastActive = now - 30_000)
        val stale = bot("b", lastActive = now - 120_000)
        val never = bot("c")
        assertTrue(BotRoster.isActive(fresh, now))
        assertFalse(BotRoster.isActive(stale, now))
        assertTrue(BotRoster.isActive(stale, now, busy = setOf("b")))
        assertFalse(BotRoster.isActive(never, now))
        assertEquals(listOf("a"), BotRoster.active(listOf(fresh, stale, never), now).map { it.name })
    }

    @Test fun orderActiveFirstThenRecencyHiddenLast() {
        val bots = listOf(
            bot("old", lastActive = now - 10 * 60_000),
            bot("hidden", lastActive = now - 1_000, hidden = true),
            bot("default", isDefault = true),
            bot("live", lastActive = now - 5_000),
            bot("recent", lastActive = now - 3 * 60_000),
        )
        assertEquals(listOf("live", "recent", "old", "default"), BotRoster.order(bots, now).map { it.name })
        assertEquals(
            listOf("live", "recent", "old", "default", "hidden"),
            BotRoster.order(bots, now, showHidden = true).map { it.name },
        )
    }

    @Test fun unreadIsActivityAfterTheLastLook() {
        val b = bot("theo", lastActive = now - 1_000)
        assertTrue(BotRoster.unread(b, emptyMap()))
        assertFalse(BotRoster.unread(b, mapOf("theo" to now)))
        assertTrue(BotRoster.unread(b, mapOf("theo" to now - 5_000)))
        assertFalse(BotRoster.unread(bot("never"), emptyMap()))
    }

    @Test fun searchMatchesNameHandleLabelAndRole() {
        val b = BotProfile(name = "sterling", title = "Ledger", description = "Business operator")
        assertTrue(BotRoster.matches(b, "ster"))
        assertTrue(BotRoster.matches(b, "ledg"))
        assertTrue(BotRoster.matches(b, "OPERATOR"))
        assertFalse(BotRoster.matches(b, "juno"))
        assertTrue(BotRoster.matches(b, "  "))
    }

    // ---- mentions --------------------------------------------------------------------------

    @Test fun mentionsResolveAgainstRosterOnceEach() {
        val roster = listOf(bot("theo"), bot("researcher", title = "Research Buddy"), bot("default", isDefault = true))
        val hit = BotRoster.mentions("@theo and @research-buddy, also @theo again and @nobody @hermes", roster)
        assertEquals(listOf("theo", "researcher", "default"), hit.map { it.name })
        assertTrue(BotRoster.mentions("mail me at jonny@example.com", roster).isEmpty())
        assertTrue(BotRoster.mentions("no tags here", roster).isEmpty())
    }

    @Test fun mentionNoteNamesEachAgentAndTeachesTheTool() {
        val note = BotRoster.mentionNote(listOf(bot("researcher", title = "Research Buddy")))
        assertTrue(note.startsWith("\n\n[@mentions resolved"))
        assertTrue("@researcher = agent profile \"researcher\" (\"Research Buddy\")" in note)
        assertTrue("message_agent" in note)
        assertEquals("", BotRoster.mentionNote(emptyList()))
    }

    // ---- the forever-chat rule ---------------------------------------------------------------

    @Test fun slashNewReroutesOnlyInsideTheCanonicalChat() {
        assertEquals("/compact", BotRoster.reroute("/new", inCanonicalChat = true))
        assertEquals("/compact", BotRoster.reroute("  /reset ", inCanonicalChat = true))
        assertNull(BotRoster.reroute("/new", inCanonicalChat = false))
        assertNull(BotRoster.reroute("/new topic", inCanonicalChat = true))
        assertNull(BotRoster.reroute("hello", inCanonicalChat = true))
    }

    // ---- JSON ---------------------------------------------------------------------------------

    private val listAnswer = """
        {"profiles":[
          {"name":"default","display_name":"","description":"","is_default":true,"has_avatar":false,
           "canonical_session":{"id":"20260817_173323_8ac183","resolved_id":"20260817_173323_8ac183",
             "root_title":"Bot Chat","title":"Bot Chat","preview":"Command ran successfully.",
             "started_at":1787006003.84,"last_active":1787006483.36,"message_count":6},
           "model":"qwen3.8-27b","provider":"custom"},
          {"name":"theo","display_name":"","description":"Ops","canonical_session":null,"model":"m","provider":"p",
           "ui_meta":{"hermes-bots":{"title":"Theo the Ops Bot","shape":"cloud","color":"#8b5cf6","hidden":true,"created":1}}},
          {"name":"","description":"ghost"}
        ],"bot_mode_protocol":true}
    """.trimIndent()

    @Test fun profilesListParses() {
        val snap = BotsJson.snapshot(Json.parseToJsonElement(listAnswer).jsonObject, now)
        assertEquals(2, snap.bots.size)
        val d = snap.byName("default")!!
        assertTrue(d.isDefault)
        assertFalse(d.managed)
        assertNotNull(d.canonical)
        assertEquals(1787006483360L, d.canonical!!.lastActive)
        assertEquals(6L, d.canonical!!.messageCount)
        val t = snap.byName("theo")!!
        assertTrue(t.managed)
        assertTrue(t.hidden)
        assertEquals("Theo the Ops Bot", t.title)
        assertEquals("Ops", t.description)
        assertNull(t.canonical)
        assertEquals("cloud", t.meta!!["shape"]!!.jsonPrimitive.content)
        assertTrue(snap.protocolEnabled)
        assertTrue(snap.messagingArmed)
    }

    @Test fun messagingArmedNeedsAManagedProfileAndTheSwitch() {
        val none = BotsJson.snapshot(Json.parseToJsonElement("""{"profiles":[{"name":"a"}],"bot_mode_protocol":true}""").jsonObject, now)
        assertFalse(none.messagingArmed)
        val off = BotsJson.snapshot(
            Json.parseToJsonElement("""{"profiles":[{"name":"a","ui_meta":{"hermes-bots":{}}}],"bot_mode_protocol":false}""").jsonObject,
            now,
        )
        assertTrue(off.bots[0].managed)
        assertFalse(off.messagingArmed)
    }

    @Test fun titleLookupPicksTheCanonicalRowOrNothing() {
        val hit = Json.parseToJsonElement(
            """{"sessions":[{"id":"x","title":"Inbox"},{"id":"y","resolved_id":"y2","title":"Bot Chat","last_active":1787006483}]}""",
        ).jsonObject
        val ref = BotsJson.canonicalFromList(hit)!!
        assertEquals("y", ref.id)
        assertEquals("y2", ref.openId)
        assertEquals(1787006483000L, ref.lastActive)
        assertNull(BotsJson.canonicalFromList(Json.parseToJsonElement("""{"sessions":[]}""").jsonObject))
        assertNull(BotsJson.canonicalFromList(Json.parseToJsonElement("""{"error":"nope"}""").jsonObject))
    }

    @Test fun metaPatchKeepsTheDesktopsKeys() {
        val existing = Json.parseToJsonElement("""{"shape":"cloud","color":"#8b5cf6","title":"Old","created":5,"groups":["ops"]}""").jsonObject
        val patched = BotsJson.metaPatch(existing, title = "New", hidden = true, now = now)
        assertEquals("cloud", patched["shape"]!!.jsonPrimitive.content)
        assertEquals("New", patched["title"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), patched["hidden"])
        assertEquals(5L, patched["created"]!!.jsonPrimitive.content.toLong())
        assertNotNull(patched["groups"])
        val unhidden = BotsJson.metaPatch(patched, title = null, hidden = false, now = now)
        assertNull(unhidden["hidden"])
        assertEquals("New", unhidden["title"]!!.jsonPrimitive.content)
        val fresh = BotsJson.metaPatch(null, title = "  ", hidden = null, now = now)
        assertNull(fresh["title"])
        assertEquals(now, fresh["created"]!!.jsonPrimitive.content.toLong())
    }
}
