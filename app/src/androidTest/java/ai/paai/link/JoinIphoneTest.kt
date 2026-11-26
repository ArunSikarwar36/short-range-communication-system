package ai.paai.link

import ai.paai.link.core.transport.WifiJoin
import ai.paai.link.ui.JoinIphoneDialog
import ai.paai.link.ui.LinkTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class JoinIphoneTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun dialogShowsNetworkDetailsAndAScannableQrCode() {
        val ssid = "DIRECT-aB-Test phone"
        val pass = "x7Kq2mPz9"
        rule.setContent { LinkTheme { JoinIphoneDialog(ssid, pass) {} } }

        rule.onNodeWithText("Add an iPhone").assertIsDisplayed()
        rule.onNodeWithText("Network   $ssid").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Password  $pass").assertIsDisplayed()

        // Read the QR code from the pixels on screen, as a phone camera would.
        val bmp = rule.onNodeWithContentDescription("Wi-Fi QR code").performScrollTo().captureToImage().asAndroidBitmap()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px)))).text
        assertEquals(WifiJoin.qrPayload(ssid, pass), text)
    }
}
