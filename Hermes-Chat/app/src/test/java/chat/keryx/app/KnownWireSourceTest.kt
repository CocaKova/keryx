package chat.keryx.app

import chat.keryx.core.model.KnownWire
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every dotted wire name in the app's source is declared in [KnownWire] (2.19), so the drift
 * script checks it against Hermes. A new `rpc.request("x.y")` or event branch that skips the
 * table fails here, not on the phone after the next `hermes update`.
 */
class KnownWireSourceTest {

    /** Dotted literals that are not Hermes wire names: files, intents, notification channels. */
    private val notWire = setOf(
        "trixnity.db", "keryx_archive.db", "config.yaml", "page.html",
        "chat.keryx.app.android", "android.shortcut.conversation",
    )

    @Test
    fun `every dotted wire literal is in KnownWire`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val literal = Regex("\"([a-z_]+(?:\\.[a-z_]+)+)\"")
        val found = root.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] } }
            .toSortedSet()
        val undeclared = found.filterNot { it in KnownWire.ALL_DOTTED || it in notWire || it.startsWith("keryx.") }
        assertEquals(emptyList<String>(), undeclared)
    }
}
