package chat.keryx.core.model

/**
 * `MEDIA:<path>` — the Hermes convention an agent uses to hand a file to whoever is
 * listening. Platform adapters (Telegram, Discord…) turn it into an attachment; Desktop
 * (`apps/desktop/src/lib/chat-messages.ts`, `MEDIA_LINE_RE`/`MEDIA_TAG_RE`) turns it into a
 * `#media:` link served by `/api/files/download`. Keryx does the same: the tag leaves the
 * prose and becomes a media bubble the repository resolves against the gateway.
 *
 * Pure. Regexes are ported from desktop so both clients agree on what counts as a tag:
 * a whole line (optionally quoted/backticked, surrounding whitespace allowed) or an inline
 * mention. Inline tags are replaced by the file's name so the sentence still reads.
 */
object MediaTags {

    data class Ref(val path: String, val name: String, val kind: MediaKind)

    data class Split(val text: String, val refs: List<Ref>)

    private const val VALUE = """(`[^`\n]+`|"[^"\n]+"|'[^'\n]+'|\S+)"""
    private val LINE = Regex("""^[\t ]*[`"']?MEDIA:\s*$VALUE[`"']?[\t ]*$""", RegexOption.MULTILINE)
    private const val GONE = "\u0000"
    private val INLINE = Regex("""[`"']?MEDIA:\s*$VALUE[`"']?""")

    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    private val AUDIO = setOf("mp3", "wav", "ogg", "opus", "flac", "m4a")
    private val VIDEO = setOf("mp4", "webm", "mov", "mkv", "avi")

    fun hasTag(text: String): Boolean = text.contains("MEDIA:")

    /** Strip every tag out of [text]; return the prose that remains + the refs, in order. */
    fun split(text: String): Split {
        if (!hasTag(text)) return Split(text, emptyList())
        val refs = ArrayList<Ref>()
        // Whole-line tags first. Each becomes a marker, then the marker lines are dropped
        // whole — so a swallowed line takes its newline with it and the prose above and
        // below still join cleanly (consecutive tag lines included).
        var out = LINE.replace(text) { m ->
            refs += ref(unquote(m.groupValues[1]))
            GONE
        }
        if (refs.isNotEmpty()) out = out.lineSequence().filter { it != GONE }.joinToString("\n")
        out = INLINE.replace(out) { m ->
            val r = ref(unquote(m.groupValues[1]))
            refs += r
            "`${r.name}`"
        }
        return Split(out.trim(), refs)
    }

    fun kindOf(path: String): MediaKind {
        val ext = path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE -> MediaKind.IMAGE
            in AUDIO -> MediaKind.AUDIO
            in VIDEO -> MediaKind.VIDEO
            else -> MediaKind.FILE
        }
    }

    fun nameOf(path: String): String =
        path.substringBefore('?').substringBefore('#').trimEnd('/', '\\')
            .split('/', '\\').lastOrNull { it.isNotBlank() } ?: path

    private fun ref(path: String) = Ref(path = path, name = nameOf(path), kind = kindOf(path))

    private fun unquote(v: String): String {
        val t = v.trim()
        val q = t.firstOrNull() ?: return t
        return if (t.length >= 2 && (q == '"' || q == '\'' || q == '`') && t.last() == q) t.substring(1, t.length - 1) else t
    }
}
