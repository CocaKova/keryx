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

    // The bare alternative stops at a quote: `MEDIA:/a/b.html` (the whole tag backticked, the
    // shape an agent writes when it names the tag in prose) used to yield the path WITH its
    // closing backtick — a card for a file that does not exist. An apostrophe may sit INSIDE a
    // bare path (`/home/sy/o'brien.png`), never at its end, where it is a closing quote.
    private const val VALUE = """(`[^`\n]+`|"[^"\n]+"|'[^'\n]+'|[^\s`"']+(?:'[^\s`"']+)*)"""
    private val LINE = Regex("""^[\t ]*[`"']?MEDIA:\s*$VALUE[`"']?[\t ]*$""", RegexOption.MULTILINE)
    private const val GONE = "\u0000"
    private val INLINE = Regex("""[`"']?MEDIA:\s*$VALUE[`"']?""")

    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    private val AUDIO = setOf("mp3", "wav", "ogg", "opus", "flac", "m4a")
    private val VIDEO = setOf("mp4", "webm", "mov", "mkv", "avi")

    fun hasTag(text: String): Boolean = text.contains("MEDIA:")

    /**
     * Only an address is a tag. The regexes take any non-space run after `MEDIA:`, which is
     * right for a real hand-off and wrong the moment an agent talks ABOUT the convention —
     * "`MEDIA:<absolute path>` one per line", "in MEDIA: form" — where `<absolute` and `form`
     * became file chips that could never download (device, 2026-09-22). A path on the host
     * starts at its root or the home; a URL names its scheme; Windows names a drive.
     */
    private val ADDRESS = Regex("""^(/|~/|[A-Za-z]:[\\/]|https?://)""")
    fun looksLikeAddress(value: String): Boolean = ADDRESS.containsMatchIn(value.trim())

    /** Strip every tag out of [text]; return the prose that remains + the refs, in order. */
    fun split(text: String): Split {
        if (!hasTag(text)) return Split(text, emptyList())
        val refs = ArrayList<Ref>()
        // Whole-line tags first. Each becomes a marker, then the marker lines are dropped
        // whole — so a swallowed line takes its newline with it and the prose above and
        // below still join cleanly (consecutive tag lines included).
        var out = LINE.replace(text) { m ->
            val v = unquote(m.groupValues[1])
            if (!looksLikeAddress(v)) m.value else { refs += ref(v); GONE }
        }
        if (refs.isNotEmpty()) out = out.lineSequence().filter { it != GONE }.joinToString("\n")
        out = INLINE.replace(out) { m ->
            val v = unquote(m.groupValues[1])
            if (!looksLikeAddress(v)) m.value else {
                val r = ref(v)
                refs += r
                "`${r.name}`"
            }
        }
        return Split(out.trim(), refs)
    }

    // ---- `@image:<path>` — the USER's attachments, as Hermes persists them --------------
    // A photo sent from the composer goes up as bytes (`image.attach_bytes`); what the
    // gateway stores in the transcript is the caption plus one `@image:<path>` line per file,
    // path quoted when it holds spaces (`session_history._build_persist_message_with_image_refs`,
    // desktop `directive-text.tsx` renders them as thumbnails). The live echo bubble shows the
    // bytes it just sent, but a reloaded session only has these lines — and until 2.13.7 they
    // were shown as text, an absolute path under the caption (device, 2026-09-24).
    private val IMAGE_REF_LINE = Regex("""^[\t ]*@image:\s*$VALUE[\t ]*$""", RegexOption.MULTILINE)

    fun hasImageRef(text: String): Boolean = text.contains("@image:")

    /** Strip every whole-line `@image:` directive out of [text]; the refs come back in order.
     *  Only an address counts (a sentence about the convention stays prose). */
    fun splitImageRefs(text: String): Split {
        if (!hasImageRef(text)) return Split(text, emptyList())
        val refs = ArrayList<Ref>()
        var out = IMAGE_REF_LINE.replace(text) { m ->
            val v = unquote(m.groupValues[1])
            if (!looksLikeAddress(v)) m.value else { refs += ref(v); GONE }
        }
        if (refs.isNotEmpty()) out = out.lineSequence().filter { it != GONE }.joinToString("\n")
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
