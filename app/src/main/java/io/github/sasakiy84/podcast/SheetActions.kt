@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sasakiy84.podcast

import androidx.compose.material3.SheetState
import androidx.compose.runtime.*
import kotlinx.coroutines.launch

/** Do not remove a sheet from composition until its exit animation has finished. */
@Composable
internal fun rememberSheetAction(sheet: SheetState): (() -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var closing by remember(sheet) { mutableStateOf(false) }
    return { action -> if (!closing) {
        closing = true
        scope.launch { try { sheet.hide(); action() } finally { closing = false } }
    } }
}
