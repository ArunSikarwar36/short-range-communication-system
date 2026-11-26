package ai.paai.link.core

import ai.paai.link.core.transport.WifiJoin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WifiJoinTest {
    @Test
    fun `typical Android group produces the standard Wi-Fi QR text`() {
        assertEquals(
            "WIFI:T:WPA;S:DIRECT-aB-Pixel 8;P:x7Kq2mPz;;",
            WifiJoin.qrPayload("DIRECT-aB-Pixel 8", "x7Kq2mPz"),
        )
    }

    @Test
    fun `special characters are escaped`() {
        assertEquals(
            """WIFI:T:WPA;S:a\;b\,c\:d\"e\\f;P:p\;ss\:word;;""",
            WifiJoin.qrPayload("""a;b,c:d"e\f""", "p;ss:word"),
        )
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { WifiJoin.qrPayload("", "password1") }
        assertFailsWith<IllegalArgumentException> { WifiJoin.qrPayload("DIRECT-x", "short") }
        assertFailsWith<IllegalArgumentException> { WifiJoin.qrPayload("DIRECT-x", "p".repeat(64)) }
    }
}
