package io.github.sasakiy84.podcast

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNavigationTest {
    private val compose = createAndroidComposeRule<SettingsActivity>()
    @get:Rule
    val rules: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(
                object : org.junit.rules.ExternalResource() {
                    override fun before(): Unit {
                        // Navigation does not need an audio service. Robolectric cannot bind a
                        // MediaSession.
                        val application = org.robolectric.RuntimeEnvironment.getApplication()
                        org.robolectric.Shadows.shadowOf(application)
                            .declareComponentUnbindable(
                                android.content.ComponentName(
                                    application,
                                    PlaybackService::class.java,
                                )
                            )
                    }
                }
            )
            .around(compose)

    @Test
    fun backDismissesTheSpeedSheetBeforeFinishingTheSettingsActivity(): Unit {
        compose.onNodeWithText("再生速度 1.0×").performClick()
        compose.backGesture(cancel = true)
        compose.onNodeWithContentDescription("再生速度を調整").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithContentDescription("再生速度を調整").assertDoesNotExist()
        compose.onNodeWithText("設定").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
        compose.backGesture()
        assertTrue(compose.activity.isFinishing)
    }
}
