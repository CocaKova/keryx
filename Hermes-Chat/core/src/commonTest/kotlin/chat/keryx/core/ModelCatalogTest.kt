package chat.keryx.core

import chat.keryx.core.model.ModelCatalog
import chat.keryx.core.model.ModelSwitchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogTest {

    // The live 08-28 shape, trimmed: two rows both flagged current, one of them dead.
    private val payload = """
    {"model":"qwen3.8-27b","provider":"custom","providers":[
      {"slug":"custom","name":"custom","is_current":true,"authenticated":false,
       "source":"configured-current","models":["qwen3.8-27b"],"warning":"not authenticated"},
      {"slug":"silas-brain","name":"qwen3.8-27b","is_current":true,"authenticated":true,
       "source":"user-config","models":["qwen3.8-27b"],
       "capabilities":{"qwen3.8-27b":{"fast":false,"reasoning":true}},"warning":""},
      {"slug":"anthropic","name":"Anthropic","is_current":false,"authenticated":true,
       "source":"canonical","models":["claude-opus-5","claude-sonnet-5"],
       "capabilities":{"claude-sonnet-5":{"fast":true,"reasoning":true}}},
      {"slug":"openrouter","name":"OpenRouter","is_current":false,"authenticated":false,
       "source":"canonical","models":["x"]},
      {"slug":"moa","name":"MoA","is_current":false,"authenticated":true,"source":"virtual","models":[]},
      {"name":"no slug","models":["y"]}
    ]}
    """.trimIndent()

    private fun parse(s: String) = ModelCatalog.parse(Json.parseToJsonElement(s).jsonObject)

    @Test
    fun `parses the live shape and keeps only usable providers`() {
        val c = parse(payload)
        assertEquals("qwen3.8-27b", c.model)
        assertEquals(5, c.providers.size) // the slug-less row is dropped
        assertEquals(listOf("silas-brain", "anthropic"), c.usable.map { it.slug })
    }

    @Test
    fun `current is judged by model name not the flag`() {
        val c = parse(payload)
        val brain = c.usable.first { it.slug == "silas-brain" }.models.single()
        val opus = c.usable.first { it.slug == "anthropic" }.models.first()
        assertTrue(c.isCurrent(brain))
        assertFalse(c.isCurrent(opus))
        // two rows say is_current; the dead one must not surface as usable
        assertEquals(2, c.providers.count { it.isCurrent })
    }

    @Test
    fun `capabilities ride on the choice`() {
        val sonnet = parse(payload).usable.first { it.slug == "anthropic" }.models.first { it.name == "claude-sonnet-5" }
        assertTrue(sonnet.fast && sonnet.reasoning)
        val opus = parse(payload).usable.first { it.slug == "anthropic" }.models.first { it.name == "claude-opus-5" }
        assertFalse(opus.fast)
    }

    @Test
    fun `switch outcomes read applied deferred and confirm`() {
        fun o(s: String) = ModelSwitchOutcome.parse(Json.parseToJsonElement(s).jsonObject)
        val applied = o("""{"key":"model","value":"claude-opus-5","scope":"session","warning":"","confirm_required":false}""")
        assertEquals("session", applied.scope); assertFalse(applied.deferred); assertFalse(applied.confirmRequired)
        val deferred = o("""{"key":"model","value":"x","scope":"session","deferred":true}""")
        assertTrue(deferred.deferred)
        val confirm = o("""{"confirm_required":true,"confirm_message":"pricey","warning":"pricey"}""")
        assertTrue(confirm.confirmRequired); assertEquals("pricey", confirm.message)
        assertEquals("session", confirm.scope)
    }
}
