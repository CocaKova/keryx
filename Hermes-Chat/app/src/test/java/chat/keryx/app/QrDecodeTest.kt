package chat.keryx.app

import chat.keryx.app.presentation.pair.QrDecode
import chat.keryx.core.pair.PairingLink
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing scanner's reads (3.0): a camera frame (padded rows) and a screenshot both give back
 * exactly what `hermes keryx pair` encoded, and it parses.
 */
class QrDecodeTest {
    private val payload = "keryx://pair?v=1&gw=https://box.tail1.ts.net:9119&link=http://box.tail1.ts.net:8646" +
        "&code=Qm9vdHN0cmFwLWNvZGUtMTIz&name=box"

    private fun matrix(scale: Int = 6) = QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
    ).let { m -> Triple(m, m.width * scale, scale) }

    @Test
    fun `a camera frame with padded rows reads back`() {
        val (m, side, scale) = matrix()
        val stride = side + 64 // CameraX planes often pad each row
        val y = ByteArray(stride * side) { 0x7F }
        for (row in 0 until side) for (col in 0 until side) {
            y[row * stride + col] = if (m.get(col / scale, row / scale)) 0x10 else 0xF0.toByte()
        }
        val text = QrDecode.luminance(y, stride, side, side)
        assertEquals(payload, text)
        assertTrue(PairingLink.parse(text) is PairingLink.Parsed.Ok)
    }

    @Test
    fun `a screenshot reads back`() {
        val (m, side, scale) = matrix(4)
        val pixels = IntArray(side * side) { i ->
            if (m.get((i % side) / scale, (i / side) / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(payload, QrDecode.argb(pixels, side, side))
    }

    @Test
    fun `an image without a code reads nothing`() {
        assertNull(QrDecode.argb(IntArray(200 * 200) { 0xFFFFFFFF.toInt() }, 200, 200))
    }
}
