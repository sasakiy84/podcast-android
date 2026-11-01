@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun EpisodeDetailsSheet(
    episode: Episode,
    state: PodcastUiState,
    onAction: (PodcastAction) -> Unit,
    onDismiss: () -> Unit,
    openPodcast: (Podcast) -> Unit,
    editQueues: () -> Unit,
): Unit {
    LaunchedEffect(episode.id) {
        onAction(PodcastAction.LoadDescription(episode.id))
        onAction(PodcastAction.LoadChapters(episode.id))
    }
    val detailSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val animateDetail = rememberSheetAction(detailSheet)
    ModalBottomSheet(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        sheetState = detailSheet,
        onDismissRequest = { onDismiss() },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CopyableText(
                        episode.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    EpisodeCopyButton(episode, state)
                    FavoriteButton(episode.id, state, onAction)
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            onAction(PodcastAction.Play(episode))
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        enabled = state.playback.connected,
                    ) {
                        Text("再生")
                    }
                    DownloadButton(episode, state.library.downloads[episode.id], onAction)
                }
            }
            state.library.podcasts
                .firstOrNull { it.feedUrl == episode.feedUrl }
                ?.let { podcast ->
                    item { PodcastLink(podcast) { animateDetail { openPodcast(podcast) } } }
                }
            item { EpisodeChaptersSection(episode, state, onAction) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onAction(PodcastAction.QuickQueue(episode.id)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${state.library.queues.firstOrNull { it.id == state.defaultQueueId }?.name ?: "あとで聴く"}に追加",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { animateDetail { editQueues() } }) {
                        Icon(Icons.Default.MoreHoriz, "キューに追加・編集")
                    }
                }
            }
            if (episode.description.isNotBlank())
                item {
                    ContentText(
                        state.descriptions[episode.id] ?: episode.description,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            item {
                val completed = state.library.progress[episode.id]?.completed == true
                TextButton(
                    onClick = {
                        onAction(PodcastAction.MarkPlayed(episode, !completed))
                        onDismiss()
                    }
                ) {
                    Text(if (completed) "未再生に戻す" else "再生済みにする")
                }
            }
        }
    }
}
