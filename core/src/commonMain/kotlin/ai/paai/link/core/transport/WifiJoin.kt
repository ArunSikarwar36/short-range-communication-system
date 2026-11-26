package ai.paai.link.core.transport

/**
 * Lets phones without Wi-Fi Direct (iPhones) join an Android host's Wi-Fi Direct group as an
 * ordinary Wi-Fi client. The host's group is a WPA2 network; joining it only gives an IP link.
 * Identity and encryption are still the normal Noise handshake + pairing code over TCP, so knowing
 * the Wi-Fi password does not let anyone talk to or listen to SRCS.
 */
object WifiJoin {
    /** Android's Wi-Fi Direct group owner always uses this address. */
    const val HOST_ADDRESS = "192.168.49.1"

    /**
     * Standard Wi-Fi QR payload (`WIFI:T:WPA;S:…;P:…;;`), understood by the iPhone Camera app and
     * Android's Wi-Fi settings. `\ ; , : "` are escaped with a backslash.
     */
    fun qrPayload(networkName: String, passphrase: String): String {
        require(networkName.isNotEmpty()) { "network name is empty" }
        require(passphrase.length in 8..63) { "WPA passphrase must be 8-63 characters" }
        return "WIFI:T:WPA;S:${escape(networkName)};P:${escape(passphrase)};;"
    }

    private fun escape(s: String) = buildString {
        for (c in s) {
            if (c in "\\;,:\"") append('\\')
            append(c)
        }
    }
}
