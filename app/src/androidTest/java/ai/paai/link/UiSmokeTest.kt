package ai.paai.link

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsAndShowsBranding() {
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("SRCS", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // Both onboarding and the home screen show the "SRCS" title.
        rule.onAllNodes(androidx.compose.ui.test.hasText("SRCS", substring = true)).onFirst().assertIsDisplayed()
    }
}
