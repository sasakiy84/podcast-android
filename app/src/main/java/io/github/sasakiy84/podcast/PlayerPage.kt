@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MiniPlayer(episode: Episode, state: PodcastUiState, action: (PodcastAction) -> Unit, queues: () -> Unit, expand: () -> Unit): Unit {
    val podcast = state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }
    Surface(Modifier.testTag("mini-player").padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(48.dp).clickable(onClick = expand).semantics { contentDescription = "プレーヤーを開く"; stateDescription = episode.title }, contentAlignment = Alignment.Center) {
                            Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, episode.title, Modifier.size(46.dp))
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { action(PodcastAction.Skip(-15_000)) }, enabled = state.playback.connected) { RewindIcon("15秒戻る（ミニプレーヤー）") }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Spacer(Modifier.size(48.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { action(PodcastAction.Skip(30_000)) }, enabled = state.playback.connected) { Icon(Icons.Default.Forward30, "30秒スキップ") }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = queues) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "キューを開く") }
                    }
                }
                FilledIconButton(onClick = { action(PodcastAction.TogglePlayback) }, enabled = state.playback.connected, modifier = Modifier.align(Alignment.Center).size(40.dp).semantics { contentDescription = if (state.playback.playing) "一時停止" else "再生を再開" }) {
                    Icon(if (state.playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(30.dp))
                }
            }
            if (state.playback.durationMs > 0) LinearProgressIndicator(progress = { (state.playback.positionMs.toFloat() / state.playback.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
internal fun PlayerPage(episode: Episode, state: PodcastUiState, action: (PodcastAction) -> Unit, close: () -> Unit, membership: () -> Unit, queues: () -> Unit, openPodcast: () -> Unit = {}): Unit {
    val playback = state.playback
    val podcast = state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }
    val duration = playback.durationMs.takeIf { it > 0 } ?: episode.durationMs
    var scrubbing by remember(episode.id) { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val hasChapters = episode.chapters.isNotEmpty() || episode.chaptersUrl.isNotBlank() || state.chapters[episode.id]?.isNotEmpty() == true
    val pager = rememberPagerState { if (hasChapters) 3 else 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(episode.id) { action(PodcastAction.LoadDescription(episode.id)); action(PodcastAction.LoadChapters(episode.id)) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.Default.KeyboardArrowDown, "プレーヤーを閉じる") }
            LazyRow(Modifier.weight(1f)) {
                item { TextButton(onClick = { scope.launch { pager.animateScrollToPage(0) } }) { Text("再生中", color = if (pager.currentPage == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (pager.currentPage == 0) FontWeight.Bold else FontWeight.Normal) } }
                item { TextButton(onClick = { scope.launch { pager.animateScrollToPage(1) } }) { Text("詳細", color = if (pager.currentPage == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (hasChapters) item { TextButton(onClick = { scope.launch { pager.animateScrollToPage(2) } }) { Text("チャプター", color = if (pager.currentPage == 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            IconButton(onClick = queues) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "キューを開く") }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { page ->
            when (page) {
                2 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) { item { EpisodeChaptersSection(episode, state, action, showTitle = false) } }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (podcast != null) item { PodcastLink(podcast, openPodcast) }
                    item { CopyableText(episode.title, style = MaterialTheme.typography.headlineSmall) }
                    if (episode.description.isNotBlank()) item { ContentText(state.descriptions[episode.id] ?: episode.description, style = MaterialTheme.typography.bodyLarge) }
                }
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val fontScale = LocalDensity.current.fontScale
            val artSize = minOf(maxWidth - 48.dp, maxHeight - 400.dp - 80.dp * (fontScale - 1f).coerceAtLeast(0f), 400.dp).coerceAtLeast(100.dp)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, episode.title, Modifier.size(artSize).semantics { contentDescription = "プレーヤーのアートワーク" }) }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectionContainer { Text(episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        CopyableText(podcast?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Column {
                        Slider(value = scrubbing ?: playback.positionMs.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = { scrubbing = it },
                            onValueChangeFinished = { scrubbing?.let { action(PodcastAction.Seek(it.toLong())) }; scrubbing = null },
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && playback.connected,
                            modifier = Modifier.semantics { contentDescription = "再生位置" })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(scrubbing?.toLong() ?: playback.positionMs), style = MaterialTheme.typography.bodySmall)
                            Text("−${formatTime((duration - (scrubbing?.toLong() ?: playback.positionMs)).coerceAtLeast(0))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { NowPlayingChapter(episode, state, action) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { action(PodcastAction.PreviousEpisode) }, enabled = playback.hasPrevious) { Icon(Icons.Default.SkipPrevious, "前のエピソード") }
                        IconButton(onClick = { action(PodcastAction.Skip(-15_000)) }, enabled = playback.connected, modifier = Modifier.size(48.dp).semantics { contentDescription = "15秒戻る" }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Replay, null, Modifier.size(40.dp))
                                Text("15", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        FilledIconButton(onClick = { action(PodcastAction.TogglePlayback) }, enabled = playback.connected, modifier = Modifier.size(64.dp).semantics { contentDescription = if (playback.playing) "一時停止" else "再生を再開" }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                            if (playback.buffering) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.surface)
                            else Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(40.dp))
                        }
                        IconButton(onClick = { action(PodcastAction.Skip(30_000)) }, enabled = playback.connected, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Forward30, "30秒進む", Modifier.size(30.dp)) }
                        IconButton(onClick = { action(PodcastAction.NextEpisode) }, enabled = playback.hasNext) { Icon(Icons.Default.SkipNext, "次のエピソード") }
                    }
                }
            }
        }
            }
        }
        Surface(Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { speedMenu = true }, enabled = playback.connected, modifier = Modifier.semantics { contentDescription = "再生速度 ${playback.speed}×" }) { Text("${playback.speed}×", fontWeight = FontWeight.Bold) }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FavoriteButton(episode.id, state, action) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { action(PodcastAction.QuickQueue(episode.id)) }) { Icon(if (state.library.queues.any { it.id == state.defaultQueueId && episode.id in it.episodeIds }) Icons.Default.Check else Icons.AutoMirrored.Filled.PlaylistAdd, "デフォルトキューに追加") }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreHoriz, "その他の操作") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("キューに追加・編集") }, onClick = { more = false; membership() })
                        EpisodeCopyItems(episode, state.descriptions[episode.id] ?: episode.description, podcast?.title.orEmpty()) { more = false }
                        HorizontalDivider()
                        val completed = state.library.progress[episode.id]?.completed == true
                        DropdownMenuItem(text = { Text(if (completed) "未再生に戻す" else "再生済みにする") }, onClick = { action(PodcastAction.MarkPlayed(episode, !completed)); more = false })
                    }
                }
            }
        }
    }
    if (speedMenu) PlaybackSpeedSheet(state, episode.feedUrl, action = action, dismiss = { speedMenu = false })
}

