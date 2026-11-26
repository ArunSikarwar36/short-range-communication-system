package ai.paai.link

import ai.paai.link.core.transport.WifiJoin
import ai.paai.link.ui.encodeQr
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import kotlin.test.assertEquals

class QrTest {
    /** Renders the matrix at 4 px per module, like a camera would see it, and decodes it again. */
    private fun decode(m: BitMatrix): String {
        val scale = 4
        val w = m.width * scale
        val h = m.height * scale
        val px = ByteArray(w * h) { i -> if (m[(i % w) / scale, (i / w) / scale]) 0 else 0xFF.toByte() }
        val src = object : LuminanceSource(w, h) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = px.copyOfRange(y * w, (y + 1) * w)
            override fun getMatrix(): ByteArray = px
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test
    fun `Wi-Fi join QR decodes back to the same payload`() {
        for ((ssid, pass) in listOf("DIRECT-aB-Pixel 8" to "x7Kq2mPz", "DIRECT-Zz-राम का फ़ोन" to """p;ss:word"\,1""")) {
            val payload = WifiJoin.qrPayload(ssid, pass)
            assertEquals(payload, decode(encodeQr(payload)))
        }
    }
}
