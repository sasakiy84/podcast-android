package io.github.sasakiy84.podcast

import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import org.robolectric.shadows.ShadowDialog

/** The AndroidX entry points called by OnBackInvokedInput; Robolectric has no OS edge gesture. */
internal fun AndroidComposeTestRule<*, *>.backGesture(cancel: Boolean = false): Unit {
    runOnIdle {
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } as? ComponentDialog
        val dispatcher = dialog?.onBackPressedDispatcher ?: activity.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(
            BackEventCompat(100f, 300f, .6f, BackEventCompat.EDGE_LEFT)
        )
        if (cancel) dispatcher.dispatchOnBackCancelled() else dispatcher.onBackPressed()
    }
    waitForIdle()
}
