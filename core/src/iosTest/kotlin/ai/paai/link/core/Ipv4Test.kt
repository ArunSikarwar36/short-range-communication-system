package ai.paai.link.core

import ai.paai.link.core.transport.ConnectionLostException
import ai.paai.link.core.transport.ipv4ToSAddr
import ai.paai.link.core.transport.sAddrToIpv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Ipv4Test {
    @Test
    fun addressesRoundTripInNetworkByteOrder() {
        // 192.168.49.1 is stored as bytes c0 a8 31 01; on little-endian that is 0x0131a8c0.
        assertEquals(0x0131a8c0u, ipv4ToSAddr("192.168.49.1"))
        for (a in listOf("127.0.0.1", "0.0.0.0", "255.255.255.255", "10.0.2.2", "192.168.49.1")) {
            assertEquals(a, sAddrToIpv4(ipv4ToSAddr(a)))
        }
    }

    @Test
    fun invalidAddressesAreRejected() {
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "a.b.c.d", "1..2.3", "-1.2.3.4")) {
            assertFailsWith<ConnectionLostException>(bad) { ipv4ToSAddr(bad) }
        }
    }
}
