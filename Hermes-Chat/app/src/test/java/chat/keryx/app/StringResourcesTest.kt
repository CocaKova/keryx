package chat.keryx.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation must say the same thing in shape: the same keys, the same placeholders,
 * the same plural items. A missing %1$s is a crash at the first getString with arguments; a
 * missing key is a silent fall back to English mid-screen.
 */
class StringResourcesTest {
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private data class Entry(val key: String, val texts: List<String>)

    private fun load(dir: File): Map<String, Entry> {
        val out = linkedMapOf<String, Entry>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sorted().forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val root = doc.documentElement
            for (i in 0 until root.childNodes.length) {
                val n = root.childNodes.item(i) as? Element ?: continue
                if (n.getAttribute("translatable") == "false") continue
                val name = n.getAttribute("name")
                val texts = when (n.tagName) {
                    "string" -> listOf(n.textContent)
                    "plurals", "string-array" -> (0 until n.childNodes.length).mapNotNull { (n.childNodes.item(it) as? Element)?.textContent }
                    else -> continue
                }
                out["${n.tagName}/$name"] = Entry(name, texts)
            }
        }
        return out
    }

    private val spec = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXcb%]""")
    private fun placeholders(s: String): Set<String> =
        spec.findAll(s).map { it.value }.filter { it != "%%" }.toSet()

    private val base by lazy { load(File(res, "values")) }
    private val locales by lazy {
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && f.listFiles()?.any { it.name.startsWith("strings") } == true }
            .orEmpty().sortedBy { it.name }
    }

    @Test fun everyLocaleFolderIsOffered() {
        val offered = KeryxLocale.SUPPORTED.filter { it != "en" }.map { tag ->
            val parts = tag.split('-')
            if (parts.size == 2) "values-${parts[0]}-r${parts[1]}" else "values-$tag"
        }.toSet()
        val folders = locales.map { it.name }.toSet()
        assertTrue("folders not in KeryxLocale.SUPPORTED: ${folders - offered}", (folders - offered).isEmpty())
        assertTrue("offered but no folder: ${offered - folders}", (offered - folders).isEmpty())
    }

    @Test fun translationsMatchEnglishInShape() {
        val problems = mutableListOf<String>()
        for (dir in locales) {
            val tr = load(dir)
            (base.keys - tr.keys).forEach { problems += "${dir.name}: missing $it" }
            (tr.keys - base.keys).forEach { problems += "${dir.name}: stray $it" }
            for ((k, e) in tr) {
                val en = base[k] ?: continue
                val want = en.texts.flatMap { placeholders(it) }.toSet()
                e.texts.forEach { t ->
                    val got = placeholders(t)
                    // A plural item may drop the number ("one" → "a file"), never invent one.
                    val ok = if (k.startsWith("plurals/")) want.containsAll(got) else got == want
                    if (!ok) problems += "${dir.name}: $k placeholders $got vs English $want"
                }
            }
        }
        assertTrue(problems.take(80).joinToString("\n", prefix = "${problems.size} problems:\n"), problems.isEmpty())
    }
}
