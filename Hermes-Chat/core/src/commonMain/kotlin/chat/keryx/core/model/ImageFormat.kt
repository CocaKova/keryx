package chat.keryx.core.model

/**
 * What a picture's first bytes say about itself — specifically, whether it can move.
 *
 * An agent hands a file over as `MEDIA:/tmp/reaction.gif` ([MediaTags]), and until the download
 * lands the extension is the only claim Keryx has. Extensions lie: a `.gif` can be a PNG someone
 * renamed, and a `.webp` may or may not animate. So the render decision is made here, from the
 * real header, once the bytes exist.
 *
 * Pure and byte-only, so it is testable without a decoder. It deliberately answers the narrow
 * question "is this a container that CAN carry animation", not "does this animate" — the platform
 * decoder gets the last word on that. Over-answering costs one decoder call; under-answering
 * freezes the picture on frame one, which is the failure this exists to prevent.
 */
object ImageFormat {

    /** GIF (any GIF — a one-frame GIF is still a GIF) or a WebP whose header flags animation. */
    fun mayAnimate(bytes: ByteArray): Boolean = isGif(bytes) || isAnimatedWebp(bytes)

    /** `GIF87a` or `GIF89a`. Only 89a can actually animate, but 87a is cheap to admit. */
    fun isGif(bytes: ByteArray): Boolean =
        bytes.ascii(0, "GIF8") &&
            (bytes.ascii(4, "7a") || bytes.ascii(4, "9a"))

    /**
     * `RIFF....WEBP` with an extended-format `VP8X` chunk whose flags byte has the ANIMATION bit.
     *
     * That bit is the file's own declaration (VP8X flag layout `Rsv Rsv ICC Alpha Exif XMP ANIM
     * Rsv`, so the animation bit is 0x02) — cheaper and more exact than hunting for the `ANIM`
     * chunk, and a plain `VP8 `/`VP8L` WebP has no VP8X at all and is a still by construction.
     */
    fun isAnimatedWebp(bytes: ByteArray): Boolean =
        bytes.size >= 21 &&
            bytes.ascii(0, "RIFF") &&
            bytes.ascii(8, "WEBP") &&
            bytes.ascii(12, "VP8X") &&
            (bytes[20].toInt() and 0x02) != 0

    /** True when [text]'s ASCII bytes sit at [at]. Bounds-checked: a truncated file is not a match. */
    private fun ByteArray.ascii(at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > size) return false
        for (i in text.indices) if (this[at + i] != text[i].code.toByte()) return false
        return true
    }
}
