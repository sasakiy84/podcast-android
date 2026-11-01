package io.github.sasakiy84.podcast

import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
internal fun rememberAppSnackbar(
    state: PodcastUiState,
    action: (PodcastAction) -> Unit,
): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message, state.undo?.id) {
        val message = state.message
        val undo = state.undo
        if (message != null) {
            snackbar.showSnackbar(message)
            action(PodcastAction.ClearMessage)
        } else if (undo != null && android.os.SystemClock.elapsedRealtime() < undo.expiresAt) {
            val result =
                snackbar.showSnackbar(
                    undo.message,
                    actionLabel = "元に戻す",
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            action(
                if (result == SnackbarResult.ActionPerformed) PodcastAction.Undo(undo.id)
                else PodcastAction.DismissUndo(undo.id)
            )
        }
    }
    return snackbar
}
