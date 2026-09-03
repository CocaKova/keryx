package chat.keryx.core

import chat.keryx.core.model.ModelCatalog
import chat.keryx.core.model.ModelPicker
import chat.keryx.core.model.ModelPricing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPickerTest {

    // A local brain (user endpoint on the LAN), one single-lab cloud login with a long tail, one
    // aggregator across three labs with a featured shortlist and free-tier gating, and MoA.
    private val payload = """
    {"model":"qwen3.8-27b","provider":"silas-brain","providers":[
      {"slug":"silas-brain","name":"qwen3.8-27b","is_current":true,"authenticated":true,
       "source":"user-config","is_user_defined":true,"api_url":"http://spark-ef6b.tail5ff3bd.ts.net:8000/v1",
       "models":["qwen3.8-27b"],"capabilities":{"qwen3.8-27b":{"fast":false,"reasoning":true}}},
      {"slug":"anthropic","name":"Anthropic","is_current":false,"authenticated":true,"source":"canonical",
       "models":["claude-opus-5","claude-sonnet-5","claude-haiku-4-5","claude-opus-4-1","claude-sonnet-4-5",
                 "claude-opus-4","claude-sonnet-4","claude-3-7-sonnet","claude-3-5-haiku"],
       "capabilities":{"claude-sonnet-5":{"fast":true,"reasoning":true}}},
      {"slug":"nous","name":"Nous","is_current":false,"authenticated":true,"source":"built-in",
       "total_models":30,"free_tier":true,
       "models":["anthropic/claude-opus-5","anthropic/claude-sonnet-5","anthropic/claude-opus-4-1",
                 "openai/gpt-5.6","openai/gpt-5.6-mini","openai/gpt-5.5","openai/gpt-5.4","openai/gpt-5.3","openai/gpt-5.2","openai/gpt-5.1",
                 "moonshotai/kimi-k2.5","moonshotai/kimi-k2"],
       "featured_models":["anthropic/claude-opus-5","anthropic/claude-sonnet-5","openai/gpt-5.6","openai/gpt-5.6-mini","moonshotai/kimi-k2.5"],
       "unavailable_models":["anthropic/claude-opus-5"],
       "pricing":{"anthropic/claude-opus-5":{"input":"$15.00","output":"$75.00","cache":"$1.50","free":false},
                  "moonshotai/kimi-k2":{"input":"free","output":"free","cache":null,"free":true},
                  "openai/gpt-5.6":{"input":"$1.25","output":"$10.00","cache":"$0.125","free":false,"discount_percent":30,"was_input":"$1.75"}}},
      {"slug":"moa","name":"MoA","is_current":false,"authenticated":true,"source":"virtual","models":["moa"]},
      {"slug":"openrouter","name":"OpenRouter","authenticated":false,"source":"canonical","models":["x/y"]}
    ]}
    """.trimIndent()

    private val catalog = ModelCatalog.parse(Json.parseToJsonElement(payload).jsonObject)

    @Test
    fun `parser keeps pricing featured and tier facts`() {
        val nous = catalog.usable.first { it.slug == "nous" }
        assertTrue(nous.freeTier); assertEquals(30, nous.totalModels)
        val opus = nous.models.first { it.name == "anthropic/claude-opus-5" }
        assertTrue(opus.featured); assertTrue(opus.unavailable)
        assertEquals("$15.00", opus.pricing?.input); assertEquals("$1.50", opus.pricing?.cache)
        val kimi = nous.models.first { it.name == "moonshotai/kimi-k2" }
        assertTrue(kimi.pricing!!.free); assertNull(kimi.pricing.cache)
        val gpt = nous.models.first { it.name == "openai/gpt-5.6" }
        assertEquals(30, gpt.pricing?.discountPercent); assertEquals("$1.75", gpt.pricing?.wasInput)
        val brain = catalog.usable.first { it.slug == "silas-brain" }
        assertTrue(brain.isUserDefined); assertEquals("http://spark-ef6b.tail5ff3bd.ts.net:8000/v1", brain.apiUrl)
        assertEquals("anthropic", opus.lab); assertEquals("claude-opus-5", opus.shortName)
        assertEquals("", brain.models.single().lab); assertEquals("qwen3.8-27b", brain.models.single().shortName)
    }

    @Test
    fun `where it runs is the first axis local then cloud then aggregator then virtual`() {
        val plan = ModelPicker.plan(catalog)
        assertEquals(listOf("silas-brain", "anthropic", "nous", "moa"), plan.sections.map { it.key })
        assertEquals(
            listOf(ModelPicker.Kind.LOCAL, ModelPicker.Kind.CLOUD, ModelPicker.Kind.AGGREGATOR, ModelPicker.Kind.VIRTUAL),
            plan.sections.map { it.kind },
        )
        assertEquals("qwen3.8-27b", plan.current?.name)
        assertEquals("spark-ef6b.tail5ff3bd.ts.net · 1 model", plan.sections[0].subtitle)
        assertEquals("30 models · 3 labs · free tier", plan.sections[2].subtitle)
    }

    @Test
    fun `private endpoints are recognised and public ones are not`() {
        for (u in listOf(
            "http://localhost:8000/v1", "http://127.0.0.1:1234", "http://192.168.50.253:8642/v1",
            "http://10.1.2.3", "http://172.20.0.5:11434", "http://100.93.255.23:8000", "http://spark:8000/v1",
            "https://spark-ef6b.tail5ff3bd.ts.net/v1", "http://brain.local:8000", "http://[::1]:8000",
        )) assertTrue(ModelPicker.isPrivateEndpoint(u), u)
        for (u in listOf(
            "https://api.openai.com/v1", "https://openrouter.ai/api/v1", "http://172.32.0.1", "http://100.200.1.1",
            "https://inference-api.nousresearch.com/v1", "",
        )) assertFalse(ModelPicker.isPrivateEndpoint(u), u)
        assertEquals(ModelPicker.Kind.LOCAL, ModelPicker.kindOf(catalog.usable.first { it.slug == "silas-brain" }))
    }

    @Test
    fun `an aggregator splits by lab with the featured few shown and the tail folded`() {
        val nous = ModelPicker.plan(catalog).sections.first { it.key == "nous" }
        assertEquals(listOf("Anthropic", "OpenAI", "Moonshot"), nous.groups.map { it.title })
        val openai = nous.groups[1]
        assertEquals(listOf("openai/gpt-5.6", "openai/gpt-5.6-mini"), openai.shown.map { it.name })
        assertEquals(5, openai.folded); assertFalse(openai.expanded)
        assertEquals(12, nous.count)
        // Opened: every row of the lab, in the gateway's order.
        val opened = ModelPicker.plan(catalog, expanded = setOf("nous/openai")).sections.first { it.key == "nous" }.groups[1]
        assertEquals(7, opened.shown.size); assertEquals(0, opened.folded); assertTrue(opened.expanded)
    }

    @Test
    fun `a single-lab login is one flat group folded past the head`() {
        val anthropic = ModelPicker.plan(catalog).sections.first { it.key == "anthropic" }.groups.single()
        assertNull(anthropic.title)
        assertEquals(ModelPicker.SINGLE_LAB_FOLD, anthropic.shown.size)
        assertEquals(3, anthropic.folded)
        // A fold that would hide a single row shows it instead.
        val seven = catalog.copy(providers = catalog.providers.map { p ->
            if (p.slug == "anthropic") p.copy(models = p.models.take(7)) else p
        })
        val g = ModelPicker.plan(seven).sections.first { it.key == "anthropic" }.groups.single()
        assertEquals(7, g.shown.size); assertEquals(0, g.folded)
        // Local rows never fold: they are the machines you own.
        assertTrue(ModelPicker.plan(catalog).sections.first { it.key == "silas-brain" }.groups.single().expanded)
    }

    @Test
    fun `a query flattens the picker to matches only and opens every fold`() {
        val plan = ModelPicker.plan(catalog, recents = listOf("anthropic|claude-opus-5"), query = "sonnet")
        assertTrue(plan.sections.none { it.key == "recent" })
        assertEquals(listOf("anthropic", "nous"), plan.sections.map { it.key })
        val direct = plan.sections[0].groups.single()
        assertEquals(listOf("claude-sonnet-5", "claude-sonnet-4-5", "claude-sonnet-4", "claude-3-7-sonnet"), direct.shown.map { it.name })
        assertTrue(direct.expanded); assertEquals(0, direct.folded)
        // Lab and provider names are searchable too, and terms AND together.
        val byLab = ModelPicker.plan(catalog, query = "moonshot k2.5")
        assertEquals(listOf("moonshotai/kimi-k2.5"), byLab.sections.single().groups.single().shown.map { it.name })
        assertTrue(ModelPicker.plan(catalog, query = "zzz").isEmpty)
    }

    @Test
    fun `recents resolve against the live catalog and skip the current model`() {
        val recents = listOf("silas-brain|qwen3.8-27b", "nous|openai/gpt-5.6", "anthropic|claude-sonnet-5", "openrouter|x/y", "nous|gone/model")
        val plan = ModelPicker.plan(catalog, recents = recents)
        val recent = plan.sections.first().also { assertEquals("recent", it.key) }.groups.single()
        // current skipped, logged-out provider skipped, unknown model skipped
        assertEquals(listOf("openai/gpt-5.6", "claude-sonnet-5"), recent.shown.map { it.name })
        val pushed = ModelPicker.pushRecent(listOf("a|b", "c|d"), catalog.usable[1].models[0])
        assertEquals(listOf("anthropic|claude-opus-5", "a|b", "c|d"), pushed)
        assertEquals(listOf("a|b", "c|d"), ModelPicker.pushRecent(listOf("a|b", "c|d"), catalog.usable[1].models[0]).drop(1))
    }

    @Test
    fun `lab names and compact prices read like a person wrote them`() {
        assertEquals("OpenAI", ModelPicker.labName("openai"))
        assertEquals("xAI", ModelPicker.labName("x-ai"))
        assertEquals("Some Lab", ModelPicker.labName("some-lab"))
        assertEquals("$3", ModelPricing.compact("$3.00"))
        assertEquals("$0.15", ModelPricing.compact("$0.15"))
        assertEquals("$0.0018", ModelPricing.compact("$0.0018"))
        assertEquals("$180", ModelPricing.compact("$180.00"))
        assertEquals("free", ModelPricing.compact("free"))
        assertEquals("?", ModelPricing.compact("?"))
    }

    @Test
    fun `an empty catalog plans nothing`() {
        assertTrue(ModelPicker.plan(null).isEmpty)
        assertNull(ModelPicker.plan(null).current)
    }
}
