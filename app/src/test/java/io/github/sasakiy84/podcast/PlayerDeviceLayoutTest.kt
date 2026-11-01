package io.github.sasakiy84.podcast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerDeviceLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val shows = (1..24).map { Podcast("https://example.org/$it", "番組$it") }
    private val episode = Episode("one", shows[0].feedUrl, "第一回", "https://example.org/audio.mp3")
    private val base =
        PodcastUiState(
            library = Library(shows, listOf(episode)),
            playback = PlaybackUi(episode.id, connected = true),
        )

    private var panelColor = 0
    private var controlColor = 0

    @Composable
    private fun ThemedApp(state: PodcastUiState): Unit {
        PodcastTheme {
            val colors = MaterialTheme.colorScheme
            SideEffect {
                panelColor = colors.surfaceContainer.toArgb()
                controlColor = colors.primary.toArgb()
            }
            PodcastApp(state)
        }
    }

    private fun insets(): Unit =
        compose.runOnIdle {
            val density = compose.activity.resources.displayMetrics.density
            val status = Insets.of(0, (24 * density).toInt(), 0, 0)
            val navigation = Insets.of(0, 0, 0, (24 * density).toInt())
            val insets =
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), status)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                    .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            for (i in 0 until content.childCount) ViewCompat.dispatchApplyWindowInsets(
                content.getChildAt(i),
                insets,
            )
        }

    private fun assertPaintedPlayer(name: String, saveScreenshot: Boolean = true): Unit {
        compose.waitForIdle()
        val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        val navigation =
            compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals("Mini must meet navigation", navigation.top, mini.bottom, 2f)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            if (saveScreenshot) {
                val file = File(System.getProperty("podcast.screenshotDir"), "device/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fun matches(actual: Int, expected: Int): Boolean =
                (actual ushr 24) != 0 &&
                    listOf(0, 8, 16).all { shift ->
                        kotlin.math.abs(
                            ((actual shr shift) and 255) - ((expected shr shift) and 255)
                        ) <= 1
                    }
            var panelPixels = 0
            var controlPixels = 0
            var total = 0
            for (y in
                mini.top.toInt().coerceAtLeast(0) until
                    mini.bottom.toInt().coerceAtMost(bitmap.height) step
                    3) {
                for (x in
                    mini.left.toInt().coerceAtLeast(0) until
                        mini.right.toInt().coerceAtMost(bitmap.width) step
                        3) {
                    total++
                    val color = bitmap.getPixel(x, y)
                    if (matches(color, controlColor)) controlPixels++
                    if (matches(color, panelColor)) panelPixels++
                }
            }
            assertTrue(
                "Mini is blank: $panelPixels / $total pixels",
                total > 0 && panelPixels > total / 3,
            )
            assertTrue(
                "Playback control is not painted: $controlPixels / $total",
                controlPixels > total / 100,
            )
        }
    }

    @Test
    fun loadingThenRestoringPlayerAndClosingItDoesNotLeaveAWhitePanel(): Unit {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val ui = mutableStateOf(base.copy(playback = PlaybackUi()))
        compose.setContent { ThemedApp(ui.value) }
        insets()
        compose.runOnIdle { ui.value = base }
        assertPaintedPlayer("loaded-xxhdpi")
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("プレーヤーを閉じる").performClick()
        assertPaintedPlayer("closed-xxhdpi")
    }

    @Test
    fun collapsedControlsStayPaintedWhileInitialAnchorsSettle(): Unit {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.mainClock.autoAdvance = false
        compose.setContent { ThemedApp(base) }
        insets()
        repeat(12) { frame ->
            compose.mainClock.advanceTimeByFrame()
            assertPaintedPlayer("frame-$frame-xxhdpi", saveScreenshot = frame == 11)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    @Config(qualifiers = "ja-w360dp-h780dp-night-xxhdpi")
    fun darkThemeAlsoPaintsTheCollapsedControls(): Unit {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            ThemedApp(base.copy(playback = base.playback.copy(durationMs = 600_000)))
        }
        insets()
        assertPaintedPlayer("dark-xxhdpi")
    }
}
