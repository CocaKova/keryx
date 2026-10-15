package chat.keryx.app.presentation.pair

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * QR reads for pairing (3.0), plain ZXing on the phone: camera frames by their luminance plane,
 * screenshots by their pixels. Text out, nothing kept.
 */
object QrDecode {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** A camera frame's Y plane ([rowStride] may pad each row past [width]). */
    fun luminance(y: ByteArray, rowStride: Int, width: Int, height: Int): String? =
        read(PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false))

    /** ARGB pixels, as a Bitmap hands them out. */
    fun argb(pixels: IntArray, width: Int, height: Int): String? =
        read(RGBLuminanceSource(width, height, pixels))

    private fun read(source: LuminanceSource): String? {
        // The hybrid binarizer suits camera light; a flat screenshot sometimes reads only with the
        // global one.
        for (binarize in listOf(::HybridBinarizer, ::GlobalHistogramBinarizer)) {
            val reader = QRCodeReader()
            try {
                return reader.decode(BinaryBitmap(binarize(source)), hints).text
            } catch (_: ReaderException) {
                // next binarizer
            } finally {
                reader.reset()
            }
        }
        return null
    }
}
