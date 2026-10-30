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
    // bare path (`/home/me/o'brien.png`), never at its end, where it is a closing quote.
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

    // ---- `[Image attached at: <path>]` — the same photos on a native-vision turn ---------
    // When the model takes the pixels itself, Hermes builds the turn as one text part (the
    // caption, then an `[Image attached at: <path>]` line per photo; `[Image attached: <url>]`
    // for a linked one — `agent/image_routing.py`, `build_native_content_parts`) followed by the
    // image parts, and the turn's injected context — recalled memory, plugin context — rides
    // after them as one more text part (`agent/turn_context.py`, `_append_multimodal_context`).
    // The transcript keeps that list flattened: the text, one `[screenshot]` per image, then
    // the context. A reloaded chat showed all of it in the user's own bubble, the memory block
    // reading like a system prompt (device, 2026-10-25).
    private val ATTACHED_LINE = Regex("""^[\t ]*\[Image attached(?: at)?: ([^\n\]]+)\][\t ]*$""")

    // One stand-in per image part: the store's `[screenshot]` (with the detail a caller chose),
    // the `inline_images=false` read's `[image]`, and the line the transport shows that as.
    // A context part can follow the last one on the same line (the parts are joined bare).
    private val STAND_IN = Regex("""^[\t ]*(?:\[screenshot(?: detail=\w+)?\]|\[image\]|🖼 image)""")

    // Recalled memory's fence (`agent/memory_context.py`) — never the user's words, wherever it sits.
    private val MEMORY_FENCE = Regex("""<memory-context>[\s\S]*?(?:</memory-context>|$)""")

    // A tagged block on lines of its own, the way context is fenced for the model.
    private val FENCED_BLOCK = Regex("""^<([A-Za-z][\w-]*)>\n[\s\S]*?\n</\1>[\t ]*$""", RegexOption.MULTILINE)

    private fun hasUserRewrite(text: String): Boolean =
        hasImageRef(text) || text.contains("[Image attached") || text.contains("<memory-context>")

    private fun userImageRef(line: String): Ref? {
        val v = (IMAGE_REF_LINE.find(line) ?: ATTACHED_LINE.find(line))?.groupValues?.get(1)?.let(::unquote)
        return v?.takeIf(::looksLikeAddress)?.let(::ref)
    }

    /**
     * A user row as the transcript stores it, back to what the user sent: the caption, one ref
     * per photo in either stored form, the image stand-ins dropped, and what the turn appended
     * after them — fenced context the model reads — left out. Anything else found there stays
     * visible. A row without a photo keeps its stand-ins (nothing to say they were images).
     */
    fun splitUserRow(text: String): Split {
        if (!hasUserRewrite(text)) return Split(text, emptyList())
        val lines = text.split('\n')
        val refs = ArrayList<Ref>()
        val prose = ArrayList<String>()
        var tail = ""
        var i = 0
        while (i < lines.size) {
            val line = lines[i++]
            val ref = userImageRef(line)
            val stand = if (refs.isEmpty()) null else STAND_IN.find(line)
            if (ref != null) {
                refs += ref
            } else if (stand != null) {
                // The stand-ins close the user's part; the rest of the row was appended to it.
                var rest = line.substring(stand.range.last + 1)
                while (rest.isEmpty() && i < lines.size) {
                    val next = STAND_IN.find(lines[i]) ?: break
                    rest = lines[i++].substring(next.range.last + 1)
                }
                tail = (listOf(rest) + lines.subList(i, lines.size)).joinToString("\n")
                break
            } else {
                prose += line
            }
        }
        if (refs.isEmpty()) {
            return Split(if (MEMORY_FENCE.containsMatchIn(text)) MEMORY_FENCE.replace(text, "").trim() else text, emptyList())
        }
        val said = MEMORY_FENCE.replace(prose.joinToString("\n"), "").trim()
        val appended = FENCED_BLOCK.replace(MEMORY_FENCE.replace(tail, ""), "").trim()
        return Split(listOf(said, appended).filter { it.isNotEmpty() }.joinToString("\n\n"), refs)
    }

    /** How a lifted media row's id names the row it came out of: `<row id>#media:<n>`. */
    const val MEDIA_ID_SEP = "#media:"

    /**
     * Every finished agent row's `MEDIA:` lines (and every user row's photos, [splitUserRow]) lifted
     * out into media rows of their own, right after the prose that named them, ids
     * `<row>`[MEDIA_ID_SEP]`<n>`. The prose row stays when anything is left of it. [onRef] hears
     * each lifted id and the path it names, so a transport can fetch the bytes later.
     * The media rows keep the sender, so a user photo stays on the user's side.
     */
    fun expand(list: List<Message>, onRef: (id: String, path: String) -> Unit = { _, _ -> }): List<Message> {
        fun carries(m: Message): Boolean = !m.isStreaming && m.mediaKind == null && when (m.sender) {
            SenderType.HERMES -> hasTag(m.content)
            SenderType.ME -> hasUserRewrite(m.content)
            else -> false
        }
        if (list.none(::carries)) return list
        return buildList(list.size + 4) {
            for (m in list) {
                if (!carries(m)) { add(m); continue }
                val split = if (m.sender == SenderType.ME) splitUserRow(m.content) else split(m.content)
                if (split.refs.isEmpty()) {
                    // A user row can lose a memory block without carrying a photo.
                    add(if (m.sender == SenderType.ME && split.text != m.content) m.copy(content = split.text) else m)
                    continue
                }
                if (split.text.isNotBlank() || m.toolCalls.isNotEmpty() || m.reasoning != null) add(m.copy(content = split.text))
                split.refs.forEachIndexed { i, ref ->
                    val id = "${m.id}$MEDIA_ID_SEP$i"
                    onRef(id, ref.path)
                    add(
                        Message(
                            id = id, roomId = m.roomId, sender = m.sender,
                            content = "", timestamp = m.timestamp,
                            senderId = m.senderId, senderName = m.senderName,
                            mediaUrl = ref.path, mediaKind = ref.kind, fileName = ref.name,
                            replyToId = m.replyToId,
                        )
                    )
                }
            }
        }
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
