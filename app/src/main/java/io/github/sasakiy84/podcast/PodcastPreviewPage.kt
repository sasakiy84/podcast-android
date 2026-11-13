package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun PodcastHeader(podcast: Podcast, episodeCount: Int?): Unit {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(podcast.artwork, podcast.title, Modifier.size(104.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CopyableText(podcast.title, style = MaterialTheme.typography.titleLarge)
            CopyableText(podcast.author, style = MaterialTheme.typography.bodyMedium)
            if (episodeCount != null)
                Text("$episodeCount エピソード", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun PodcastPreviewPage(
    candidate: PodcastCandidate,
    state: PodcastUiState,
    action: (PodcastAction) -> Unit,
    subscribe: (PodcastCandidate) -> Unit,
): Unit {
    val preview = state.podcastPreview?.takeIf { it.feedUrl == candidate.podcast.feedUrl }
    val podcast = preview?.feed?.podcast ?: candidate.podcast
    val busy = candidate.podcast.feedUrl in state.busyFeeds
    var sourceMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(candidate.podcast.feedUrl, busy) {
        if (!busy) action(PodcastAction.PreviewPodcast(candidate.podcast.feedUrl))
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().testTag("podcast-preview"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PodcastHeader(podcast, preview?.takeIf { it.feed != null }?.total) }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { subscribe(candidate) }, enabled = !busy) {
                    Text(if (busy) "登録中…" else "登録する")
                }
                TextButton(onClick = { sourceMenu = true }) { Text("購読元") }
            }
        }
        if (podcast.description.isNotBlank()) item { CollapsibleDescription(podcast.description) }
        when {
            preview == null || preview.loading ->
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            preview.error != null ->
                item {
                    Text(preview.error, color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = {
                            action(
                                PodcastAction.PreviewPodcast(
                                    candidate.podcast.feedUrl,
                                    retry = true,
                                )
                            )
                        }
                    ) {
                        Text("再試行")
                    }
                }
            else -> {
                item { Text("最新のエピソード", style = MaterialTheme.typography.titleMedium) }
                if (preview.feed?.episodes.isNullOrEmpty()) item { Text("公開されている音声エピソードはありません。") }
                items(preview.feed?.episodes.orEmpty(), key = Episode::id) { episode ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CopyableText(episode.title, style = MaterialTheme.typography.titleMedium)
                        if (episode.durationMs > 0)
                            Text(
                                formatDuration(episode.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
    if (sourceMenu) PodcastSourceSheet(podcast) { sourceMenu = false }
}
