@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Material's anchored sheet owns drag offset, velocity settling, nested scroll and Back. */
@Composable
internal fun PlayerSheet(onDismiss: () -> Unit, content: @Composable (() -> Unit) -> Unit): Unit {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (!closing) { closing = true; scope.launch { sheet.hide(); onDismiss() } }; Unit }
    PodcastTheme(dark = true) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, sheetMaxWidth = 640.dp, shape = RectangleShape, dragHandle = null, contentWindowInsets = { WindowInsets(0) }) {
            Box(Modifier.fillMaxHeight()) { content(close) }
        }
    }
}
