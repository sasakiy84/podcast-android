package io.github.sasakiy84.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun RewindIcon(description: String): Unit {
    Box(
        Modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Replay, null, Modifier.size(26.dp))
        Text(
            "15",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun CompactDownloadButton(
    episode: Episode,
    download: EpisodeDownload?,
    action: (PodcastAction) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val active = download?.status in listOf(DownloadStatus.Waiting, DownloadStatus.Running)
    val complete = download?.status == DownloadStatus.Complete
    val label =
        when {
            complete -> "ダウンロードを削除"
            active -> "ダウンロードを取消"
            download?.status == DownloadStatus.Failed -> "ダウンロードを再試行"
            else -> "ダウンロード"
        }
    IconButton(
        onClick = {
            action(
                if (complete || active) PodcastAction.RemoveDownload(episode)
                else PodcastAction.Download(episode)
            )
        },
        modifier = modifier.semantics { contentDescription = "$label: ${episode.title}" },
    ) {
        if (active)
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { download!!.percent / 100f },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Icon(AppIcons.Close, null, Modifier.size(14.dp))
            }
        else
            Icon(
                if (complete) AppIcons.DownloadDone
                else if (download?.status == DownloadStatus.Failed) AppIcons.ErrorOutline
                else AppIcons.ArrowDownward,
                null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
internal fun EpisodeChaptersSection(
    episode: Episode,
    state: PodcastUiState,
    action: (PodcastAction) -> Unit,
    showTitle: Boolean = true,
): Unit {
    val chapters = state.chapters[episode.id] ?: episode.chapters
    Column(Modifier.fillMaxWidth()) {
        if (episode.id in state.chaptersLoading) CircularProgressIndicator(Modifier.size(24.dp))
        if (episode.id in state.chapterErrors)
            TextButton(onClick = { action(PodcastAction.LoadChapters(episode.id, true)) }) {
                Text("チャプターを再読み込み")
            }
        if (showTitle && chapters.isNotEmpty())
            Text(
                "チャプター",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        if (episode.chaptersUrl.isNotBlank() && episode.id in state.chapters && chapters.isEmpty())
            Text("チャプターはありません", style = MaterialTheme.typography.bodyMedium)
        chapters.forEachIndexed { index, chapter ->
            val current =
                state.playback.episodeId == episode.id &&
                    state.playback.positionMs >= chapter.startMs &&
                    (chapters.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE) >
                        state.playback.positionMs
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = state.playback.connected) {
                        action(PodcastAction.PlayChapter(episode, chapter.startMs))
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    formatTime(chapter.startMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    CopyableText(
                        chapter.title.ifBlank { "チャプター ${index + 1}" },
                        style =
                            if (current) MaterialTheme.typography.titleSmall
                            else MaterialTheme.typography.bodyMedium,
                    )
                    if (chapter.url.isNotBlank())
                        ContentText(chapter.url, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun EpisodeCopyItems(
    episode: Episode,
    description: String,
    podcastTitle: String,
    dismiss: () -> Unit,
): Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    listOf(
            "タイトルをコピー" to episode.title,
            "番組名をコピー" to podcastTitle,
            "説明をコピー" to description,
            "エピソードURLをコピー" to episodeShareLink(episode),
            "音声URLをコピー" to episode.audioUrl,
        )
        .filter { it.second.isNotBlank() }
        .forEach { (label, value) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    context
                        .getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                    dismiss()
                },
            )
        }
}

@Composable
internal fun EpisodeCopyButton(episode: Episode, state: PodcastUiState): Unit {
    var expanded by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(AppIcons.ContentCopy, "コピーする項目") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            EpisodeCopyItems(
                episode,
                state.descriptions[episode.id] ?: episode.description,
                state.library.podcasts
                    .firstOrNull { it.feedUrl == episode.feedUrl }
                    ?.title
                    .orEmpty(),
            ) {
                expanded = false
            }
        }
    }
}

@Composable
internal fun NowPlayingChapter(
    episode: Episode,
    state: PodcastUiState,
    action: (PodcastAction) -> Unit,
): Unit {
    val chapters = state.chapters[episode.id] ?: episode.chapters
    val index = chapters.indexOfLast { it.startMs <= state.playback.positionMs }
    val chapter = chapters.getOrNull(index) ?: return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                action(
                    PodcastAction.PlayChapter(
                        episode,
                        chapters[(index - 1).coerceAtLeast(0)].startMs,
                    )
                )
            },
            enabled = index > 0 && state.playback.connected,
        ) {
            Icon(AppIcons.ChevronLeft, "前のチャプター")
        }
        Column(Modifier.weight(1f)) {
            Text(chapter.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                "${index + 1} / ${chapters.size} · ${formatTime((state.playback.positionMs - chapter.startMs).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { action(PodcastAction.PlayChapter(episode, chapters[index + 1].startMs)) },
            enabled = index < chapters.lastIndex && state.playback.connected,
        ) {
            Icon(AppIcons.ChevronRight, "次のチャプター")
        }
    }
}
