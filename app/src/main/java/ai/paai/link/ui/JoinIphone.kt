package ai.paai.link.ui

import ai.paai.link.core.transport.WifiJoin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR modules for [text]; one matrix cell per module, including a 2-module quiet zone. */
fun encodeQr(text: String): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"),
)

/**
 * Shown on the group owner. iPhones cannot use Wi-Fi Direct, but they can join the owner's
 * Wi-Fi Direct group as a normal Wi-Fi network and then link to SRCS on this phone over TCP.
 */
@Composable
fun JoinIphoneDialog(networkName: String, passphrase: String, onDismiss: () -> Unit) {
    val matrix = remember(networkName, passphrase) { runCatching { encodeQr(WifiJoin.qrPayload(networkName, passphrase)) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an iPhone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. On the iPhone, open the Camera, point it at this code and tap “Join network”.")
                if (matrix != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(matrix, Modifier.size(220.dp)) }
                }
                Text("Or join it in iPhone Settings → Wi-Fi:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().background(LinkColors.Background).padding(10.dp)) {
                        Text("Network   $networkName", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Text("Password  $passphrase", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    }
                }
                Text("2. Open SRCS on the iPhone. It connects to this phone at ${WifiJoin.HOST_ADDRESS}.")
                Text("3. Check that both phones show the same pairing code, then confirm.")
                Hint("Keep this phone hosting the group. The iPhone can only reach other phones through it. The Wi-Fi password alone does not let anyone into SRCS: every phone still has to be paired.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun QrCode(matrix: BitMatrix, modifier: Modifier) {
    Canvas(modifier.background(Color.White).semantics { contentDescription = "Wi-Fi QR code" }) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
