package chat.keryx.core

import chat.keryx.core.model.ImageFormat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFormatTest {

    private fun bytes(header: String, pad: Int = 0): ByteArray =
        header.map { it.code.toByte() }.toByteArray() + ByteArray(pad)

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(20)

    /** A WebP header with [chunk] as the first chunk and [flags] as the VP8X flags byte. */
    private fun webp(chunk: String, flags: Int): ByteArray {
        val out = ByteArray(32)
        fun put(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        put(0, "RIFF"); put(8, "WEBP"); put(12, chunk)
        out[20] = flags.toByte()
        return out
    }

    @Test
    fun `a gif is admitted on either version`() {
        assertTrue(ImageFormat.mayAnimate(bytes("GIF89a", pad = 20)))
        assertTrue(ImageFormat.mayAnimate(bytes("GIF87a", pad = 20)))
    }

    @Test
    fun `stills are not offered to the animator`() {
        assertFalse(ImageFormat.mayAnimate(PNG))
        assertFalse(ImageFormat.mayAnimate(JPEG))
    }

    @Test
    fun `a webp animates only when its own header says so`() {
        assertTrue(ImageFormat.mayAnimate(webp("VP8X", flags = 0x02)))
        // The same extended chunk with every other flag set and animation clear.
        assertFalse(ImageFormat.mayAnimate(webp("VP8X", flags = 0x3D)))
        // Plain lossy/lossless WebP carries no VP8X to flag anything with.
        assertFalse(ImageFormat.mayAnimate(webp("VP8 ", flags = 0x02)))
        assertFalse(ImageFormat.mayAnimate(webp("VP8L", flags = 0x02)))
    }

    @Test
    fun `a truncated file is never a match`() {
        // These arrive off a network mid-flight: reading past the end must answer false, not throw.
        assertFalse(ImageFormat.mayAnimate(ByteArray(0)))
        assertFalse(ImageFormat.mayAnimate(bytes("GIF")))
        assertFalse(ImageFormat.mayAnimate(bytes("RIFF")))
        // Both fourccs present, the flags byte never delivered.
        assertFalse(ImageFormat.mayAnimate(webp("VP8X", flags = 0x02).copyOf(20)))
    }

    @Test
    fun `the extension is not consulted`() {
        // reaction.gif that is really a PNG: the bytes decide, so it takes the still path and
        // renders — rather than being handed to an animator that would refuse it and show nothing.
        assertFalse(ImageFormat.mayAnimate(PNG))
    }
}
