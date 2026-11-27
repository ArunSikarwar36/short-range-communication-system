package ai.paai.link.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun newerVersionsAreDetected() {
        assertTrue(UpdateManager.isNewer("0.5.0", "0.4.1"))
        assertTrue(UpdateManager.isNewer("0.10.0", "0.9.9"))
        assertTrue(UpdateManager.isNewer("1.0", "0.9.9"))
        assertTrue(UpdateManager.isNewer("0.4.1.1", "0.4.1"))
    }

    @Test
    fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateManager.isNewer("0.5.0", "0.5.0"))
        assertFalse(UpdateManager.isNewer("0.4.1", "0.5.0"))
        assertFalse(UpdateManager.isNewer("0.5", "0.5.0"))
        assertFalse(UpdateManager.isNewer("0.5.0-beta", "0.5.0"))
    }
}
