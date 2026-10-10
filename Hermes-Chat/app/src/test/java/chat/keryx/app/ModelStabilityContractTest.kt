package chat.keryx.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile

/**
 * app/compose_stability.conf declares every `chat.keryx.core.model` class stable (3.0 perf §1),
 * so Compose compares them by equals and skips rows whose data didn't change. That is only true
 * while the classes hold no mutable state: a stable class that mutates in place would leave a
 * skipped row showing old data. This walks the compiled package and fails on an instance field
 * that can change (a `var`), or one declared as a mutable collection or an array.
 *
 * It can't see a `val` holding a MutableList typed as List; keep those out of the package too.
 */
class ModelStabilityContractTest {

    private val mutableTypes = setOf(
        java.util.ArrayList::class.java, java.util.HashMap::class.java,
        java.util.LinkedHashMap::class.java, java.util.HashSet::class.java,
        java.util.LinkedHashSet::class.java, java.util.ArrayDeque::class.java,
        java.util.TreeMap::class.java, java.util.TreeSet::class.java,
    )

    private fun violations(cls: Class<*>): List<String> = cls.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .mapNotNull { f ->
            when {
                !Modifier.isFinal(f.modifiers) -> "${cls.name}.${f.name} is a var"
                f.type.isArray -> "${cls.name}.${f.name} is an array"
                f.type in mutableTypes -> "${cls.name}.${f.name} is a ${f.type.simpleName}"
                else -> null
            }
        }

    /** Every class compiled into the package (and its sub-packages), from wherever the core
     *  module's jvm output sits on the test classpath: a classes directory or a jar. */
    private fun modelClasses(): List<Class<*>> {
        val prefix = "chat/keryx/core/model/"
        val root = File(chat.keryx.core.model.LiveRate::class.java.protectionDomain.codeSource.location.toURI())
        val names = if (root.isDirectory) {
            File(root, prefix).walk().filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        } else {
            JarFile(root).use { jar ->
                jar.entries().asSequence().map { it.name }
                    .filter { it.startsWith(prefix) && it.endsWith(".class") }.toList()
            }
        }
        return names.map { it.removeSuffix(".class").replace('/', '.') }
            .filterNot { it.endsWith("\$\$serializer") || it.endsWith("\$WhenMappings") }
            .filterNot { Regex("""\$\d""").containsMatchIn(it) }   // lambdas, anonymous objects
            .map { Class.forName(it, false, javaClass.classLoader) }
    }

    @Test
    fun `core model classes hold no mutable state`() {
        val classes = modelClasses()
        assertTrue("found no core.model classes; the classpath scan is broken", classes.size > 50)
        val found = classes.flatMap(::violations)
        assertTrue(
            "These classes are declared stable in app/compose_stability.conf but can change in place. " +
                "Make them immutable or move them out of chat.keryx.core.model:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the check catches a mutable class`() {
        // StreamRateMeter is a mutable accumulator kept outside the package for this reason.
        assertFalse(violations(chat.keryx.core.transport.StreamRateMeter::class.java).isEmpty())
    }
}
