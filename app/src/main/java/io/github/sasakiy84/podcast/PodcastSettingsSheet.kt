@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun PodcastSettingsSheet(podcast: Podcast, state: PodcastUiState, action: (PodcastAction) -> Unit, dismiss: () -> Unit): Unit {
    val options = state.podcastOptions[podcast.feedUrl] ?: PodcastOptions()
    var start by rememberSaveable(podcast.feedUrl, options.skipStartSeconds) { mutableStateOf(options.skipStartSeconds.toString()) }
    var end by rememberSaveable(podcast.feedUrl, options.skipEndSeconds) { mutableStateOf(options.skipEndSeconds.toString()) }
    val valid = start.toIntOrNull() in 0..3600 && end.toIntOrNull() in 0..3600
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CopyableText(podcast.title, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("新着通知", Modifier.weight(1f))
                Switch(checked = options.notifications, onCheckedChange = { action(PodcastAction.SetPodcastOptions(podcast.feedUrl, options.copy(notifications = it))) }, modifier = Modifier.semantics { contentDescription = "新着通知" })
            }
            OutlinedTextField(value = start, onValueChange = { start = it.filter(Char::isDigit).take(4) }, label = { Text("最初をスキップ（秒）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = start.toIntOrNull() !in 0..3600, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = end, onValueChange = { end = it.filter(Char::isDigit).take(4) }, label = { Text("最後をスキップ（秒）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = end.toIntOrNull() !in 0..3600, modifier = Modifier.fillMaxWidth())
            Text("0〜3600秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { action(PodcastAction.SetPodcastOptions(podcast.feedUrl, options.copy(skipStartSeconds = start.toInt(), skipEndSeconds = end.toInt()))); dismiss() }, enabled = valid, modifier = Modifier.align(Alignment.End)) { Text("保存") }
        }
    }
}
