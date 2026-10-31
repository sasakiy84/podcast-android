package io.github.sasakiy84.podcast

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import java.io.File
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PodcastAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tappingIncrementUpdatesTheVisibleCount(): Unit {
        compose.setContent { PodcastApp() }
        compose.onNodeWithText("リセット").assertIsNotEnabled()
        compose.onNodeWithText("カウントを増やす").performClick()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("リセット").assertIsEnabled()
        val directory = File(requireNotNull(System.getProperty("podcast.screenshotDir")))
        check(directory.mkdirs() || directory.isDirectory)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "counter.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
    }

    @Test
    fun resetReturnsTheCountToZero(): Unit {
        compose.setContent { PodcastApp() }
        repeat(2) { compose.onNodeWithText("カウントを増やす").performClick() }
        compose.onNodeWithText("リセット").performClick()
        compose.onNodeWithText("0").assertIsDisplayed()
        compose.onNodeWithText("リセット").assertIsNotEnabled()
    }

    @Test
    fun countSurvivesSavedStateRestoration(): Unit {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PodcastApp() }
        compose.onNodeWithText("カウントを増やす").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("1").assertIsDisplayed()
    }
}
