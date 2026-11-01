@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal data class EpisodeShareContent(
    val episode: Episode,
    val podcastTitle: String,
    val positionMs: Long?,
)

internal fun episodeShareContent(episode: Episode, state: PodcastUiState): EpisodeShareContent =
    EpisodeShareContent(
        episode,
        state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }?.title.orEmpty(),
        state.playback.positionMs.coerceAtLeast(0).takeIf { state.playback.episodeId == episode.id },
    )

internal fun episodeShareLink(episode: Episode): String =
    absoluteUrl(episode.feedUrl, episode.webUrl).ifBlank { episode.audioUrl }

internal fun episodeShareText(content: EpisodeShareContent, includePosition: Boolean): String =
    buildList {
            if (content.podcastTitle.isNotBlank()) add(content.podcastTitle)
            add(content.episode.title)
            add(episodeShareLink(content.episode))
            if (includePosition && content.positionMs != null)
                add("再生位置: ${formatTime(content.positionMs)}（${content.positionMs / 1000}秒）")
        }
        .joinToString("\n")

internal fun copyText(context: Context, label: String, text: String): Unit {
    context
        .getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun shareText(context: Context, title: String, text: String): Unit {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "共有"))
}

private data class ShareRequest(val content: EpisodeShareContent, val dark: Boolean)

private val LocalEpisodeSharing =
    staticCompositionLocalOf<((EpisodeShareContent, Boolean) -> Unit)?> { null }

/** Keep the share snapshot alive even when the source row leaves an unplayed/favorite filter. */
@Composable
internal fun EpisodeSharingHost(content: @Composable () -> Unit): Unit {
    var request by remember { mutableStateOf<ShareRequest?>(null) }
    CompositionLocalProvider(
        LocalEpisodeSharing provides { value, dark -> request = ShareRequest(value, dark) }
    ) {
        content()
    }
    request?.let { value ->
        PodcastTheme(dark = value.dark) { EpisodeShareSheet(value.content) { request = null } }
    }
}

@Composable
internal fun rememberEpisodeSharing(episode: Episode, state: PodcastUiState): () -> Unit {
    val host = LocalEpisodeSharing.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var fallback by remember { mutableStateOf<EpisodeShareContent?>(null) }
    fallback?.let { EpisodeShareSheet(it) { fallback = null } }
    return {
        val snapshot = episodeShareContent(episode, state)
        if (host != null) host(snapshot, dark) else fallback = snapshot
    }
}

@Composable
internal fun EpisodeShareButton(episode: Episode, state: PodcastUiState): Unit {
    val share = rememberEpisodeSharing(episode, state)
    IconButton(onClick = share) { Icon(AppIcons.Share, "エピソードを共有") }
}

@Composable
internal fun EpisodeShareSheet(content: EpisodeShareContent, dismiss: () -> Unit): Unit {
    val context = LocalContext.current
    var includePosition by remember { mutableStateOf(false) }
    val text = episodeShareText(content, includePosition)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetAction(sheet)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("共有・コピー", style = MaterialTheme.typography.titleLarge)
            CopyableText(text, style = MaterialTheme.typography.bodyLarge)
            if (content.positionMs != null)
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            includePosition,
                            role = Role.Checkbox,
                            onValueChange = { includePosition = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(includePosition, null)
                    Spacer(Modifier.width(8.dp))
                    Text("再生位置を含める（${formatTime(content.positionMs)}）", Modifier.weight(1f))
                }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { copyText(context, "エピソード", content.episode.title) }) {
                    Text("タイトルをコピー")
                }
                TextButton(
                    onClick = { copyText(context, "エピソードURL", episodeShareLink(content.episode)) }
                ) {
                    Text("リンクをコピー")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        copyText(context, "エピソード", text)
                        close(dismiss)
                    }
                ) {
                    Text("本文をコピー")
                }
                Button(onClick = { shareText(context, content.episode.title, text) }) {
                    Icon(AppIcons.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("他のアプリへ共有")
                }
            }
        }
    }
}
