package ai.paai.link

import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.messaging.StoredMessage
import ai.paai.link.core.util.DeviceId
import ai.paai.link.data.toEntity
import ai.paai.link.data.toStored
import org.junit.Test
import kotlin.test.assertEquals

class MappingTest {
    @Test
    fun `stored message survives the Room entity round trip`() {
        for (status in MessageStatus.entries) {
            val m = StoredMessage(
                DeviceId.random(), DeviceId.random(), true, DeviceId.random(), "Arun", DeviceId.random(), true,
                "नमस्ते", 1_700_000_000_000, 1_700_000_000_500, status, 3, 42, true,
            )
            assertEquals(m, m.toEntity().toStored())
        }
    }
}
