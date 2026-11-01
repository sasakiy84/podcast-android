@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PodcastAutomationSheet(podcast: Podcast, state: PodcastUiState, action: (PodcastAction) -> Unit, dismiss: () -> Unit): Unit {
    var editing by remember { mutableStateOf<PlaybackQueue?>(null) }
    val queues = (state.library.queues + if (state.library.queues.none { it.id == "default" }) listOf(PlaybackQueue("default", "あとで聴く")) else emptyList())
    if (editing != null) {
        val queue = editing!!
        QueueRuleDialog(queue, state.library.queueRules.firstOrNull { it.queueId == queue.id && it.feedUrl == podcast.feedUrl } ?: QueueRule(queue.id, podcast.feedUrl), state.library, { editing = null }) { rule, apply ->
            action(PodcastAction.SaveQueueRule(rule, apply))
        }
    } else ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("キューへの自動追加", style = MaterialTheme.typography.titleLarge)
            queues.forEach { queue ->
                ListItem(headlineContent = { Text(queue.name) }, supportingContent = { if (state.library.queueRules.any { it.queueId == queue.id && it.feedUrl == podcast.feedUrl && it.enabled }) Text("自動追加中") }, modifier = Modifier.clickable { editing = queue })
            }
        }
    }
}
