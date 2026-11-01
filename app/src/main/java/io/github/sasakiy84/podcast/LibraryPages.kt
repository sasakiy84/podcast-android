@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class EpisodeFilter(val label: String) { All("すべて"), Unplayed("未再生"), Downloaded("ダウンロード"), Favorites("お気に入り"), History("再生履歴") }

@Composable
internal fun PodcastLibrary(library: Library, sort: PodcastSort, open: (Podcast) -> Unit, add: () -> Unit): Unit {
    val podcasts = remember(library.podcasts, library.latestEpisodes, sort) { sortedPodcasts(library, sort) }
    val grid = rememberLazyGridState()
    var appliedSort by rememberSaveable { mutableStateOf(sort) }
    LaunchedEffect(sort) { if (appliedSort != sort) { grid.scrollToItem(0); appliedSort = sort } }
    if (podcasts.isEmpty()) {
        EmptyState("ポッドキャストを追加", "", "番組を探す", add)
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(100.dp), state = grid, contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(podcasts, key = { it.feedUrl }) { podcast ->
            Box(Modifier.clip(RoundedCornerShape(5.dp)).clickable { open(podcast) }.semantics { contentDescription = podcast.title }) {
                Cover(podcast.artwork, podcast.title, Modifier.fillMaxWidth().aspectRatio(1f))
            }
        }
    }
}

@Composable
internal fun PodcastDetails(podcast: Podcast, state: PodcastUiState, action: (PodcastAction) -> Unit, queue: (Episode) -> Unit, details: (Episode) -> Unit): Unit {
    val page = state.podcastPages[podcast.feedUrl]
    val episodes = page?.episodes ?: state.library.episodes.filter { it.feedUrl == podcast.feedUrl }
    val swipes = remember { EpisodeSwipeState() }
    val list = rememberLazyListState()
    var settingsMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    LaunchedEffect(podcast.feedUrl, state.library.episodeCounts[podcast.feedUrl], state.library.latestEpisodes[podcast.feedUrl]) { action(PodcastAction.LoadPodcast(podcast.feedUrl)) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) swipes.openId = null }
    LazyColumn(state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(podcast.artwork, podcast.title, Modifier.size(104.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CopyableText(podcast.title, style = MaterialTheme.typography.titleLarge)
                    CopyableText(podcast.author, style = MaterialTheme.typography.bodyMedium)
                    Text("${state.library.episodeCounts[podcast.feedUrl] ?: page?.total ?: episodes.size} エピソード", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { speedMenu = true }) { Text("再生速度 ${state.podcastSpeeds[podcast.feedUrl] ?: state.globalSpeed}×") }
            TextButton(onClick = { sourceMenu = true }) { Text("購読元") }
            IconButton(onClick = { settingsMenu = true }) { Icon(Icons.Default.Settings, "番組の設定") }
        } }
        if (podcast.description.isNotBlank()) item { ContentText(podcast.description, style = MaterialTheme.typography.bodyMedium) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("新着を自動ダウンロード", style = MaterialTheme.typography.titleSmall)
                }
                Switch(checked = podcast.feedUrl in state.library.autoDownloadFeeds, onCheckedChange = { action(PodcastAction.AutoDownloadFeed(podcast.feedUrl, it)) })
            }
        }
        item { Text("エピソード", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
        if (episodes.isEmpty()) item { Text("公開されている音声エピソードはありません。") }
        items(episodes, key = { it.id }) { EpisodeCard(it, podcast, state, action, details, onQueue = queue, swipes = swipes, showPodcastTitle = false) }
        if (page != null && episodes.size < page.total) item { TextButton(onClick = { action(PodcastAction.LoadPodcast(podcast.feedUrl, true)) }, modifier = Modifier.fillMaxWidth()) { Text("さらに表示") } }
    }
    if (settingsMenu) PodcastSettingsSheet(podcast, state, action) { settingsMenu = false }
    if (sourceMenu) PodcastSourceSheet(podcast) { sourceMenu = false }
    if (speedMenu) PlaybackSpeedSheet(state, podcast.feedUrl, podcastScope = true, action = action, dismiss = { speedMenu = false })
}

@Composable
internal fun EpisodesPage(state: PodcastUiState, action: (PodcastAction) -> Unit, queue: (Episode) -> Unit, details: (Episode) -> Unit): Unit {
    var filter by rememberSaveable { mutableStateOf(EpisodeFilter.All) }
    val swipes = remember { EpisodeSwipeState() }
    val list = rememberLazyListState()
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) swipes.openId = null }
    val sourceEpisodes = if (filter == EpisodeFilter.History) state.library.historyIds.mapNotNull { id -> state.library.episodes.firstOrNull { it.id == id } } else state.library.episodes
    val episodes = sourceEpisodes.filter { episode -> when (filter) {
        EpisodeFilter.All, EpisodeFilter.History -> true
        EpisodeFilter.Unplayed -> state.library.progress[episode.id]?.completed != true
        EpisodeFilter.Downloaded -> state.library.downloads.containsKey(episode.id)
        EpisodeFilter.Favorites -> episode.id in state.library.favorites
    } }
    Column {
        LazyRow(modifier = Modifier.testTag("episode-filters"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EpisodeFilter.entries, key = { it.name }) { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label) }) }
        }
        if (episodes.isEmpty()) {
            EmptyState(when (filter) { EpisodeFilter.Downloaded -> "ダウンロードはまだありません"; EpisodeFilter.Favorites -> "お気に入りはまだありません"; EpisodeFilter.History -> "再生履歴はまだありません"; else -> "エピソードはまだありません" }, "")
        } else LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(episodes, key = { it.id }) { episode -> EpisodeCard(episode, state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }, state, action, details, onQueue = queue, swipes = swipes) }
            if (filter == EpisodeFilter.History && state.library.hasMoreHistory) item { TextButton(onClick = { action(PodcastAction.LoadMoreHistory) }, modifier = Modifier.fillMaxWidth()) { Text("さらに表示") } }
            if (state.library.hasMoreEpisodes && filter in listOf(EpisodeFilter.All, EpisodeFilter.Unplayed)) item { TextButton(onClick = { action(PodcastAction.LoadMoreEpisodes) }, modifier = Modifier.fillMaxWidth()) { Text("さらに表示") } }
        }
    }
}

@Composable
internal fun EpisodeCard(episode: Episode, podcast: Podcast?, state: PodcastUiState, action: (PodcastAction) -> Unit, details: (Episode) -> Unit, onQueue: (Episode) -> Unit = {}, swipes: EpisodeSwipeState = remember { EpisodeSwipeState() }, removeFromQueue: (() -> Unit)? = null, showPodcastTitle: Boolean = true, extra: @Composable () -> Unit = {}): Unit {
    val progress = state.library.progress[episode.id]
    val context = LocalContext.current
    fun copyTitle() { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("エピソード", episode.title)) }
    val playing = state.playback.episodeId == episode.id && state.playback.playing
    EpisodeSwipeActions(episode, state, action, swipes, queue = { onQueue(episode) }, remove = removeFromQueue) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { swipes.openId = null; details(episode) }, onLongClick = { copyTitle() }).semantics { contentDescription = "エピソード詳細: ${episode.title}" }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).alpha(if (progress?.completed == true) 0.55f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (episode.published > 0) Text(DateTimeFormatter.ofPattern("M月d日").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(episode.published)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(episode.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (showPodcastTitle && podcast != null) Text(podcast.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (episode.id in state.library.favorites) Icon(Icons.Default.Star, "お気に入り", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    val download = state.library.downloads[episode.id]
                    if (download != null) Icon(when (download.status) {
                        DownloadStatus.Complete -> Icons.Default.DownloadDone
                        DownloadStatus.Failed -> Icons.Default.ErrorOutline
                        else -> Icons.Default.Downloading
                    }, when (download.status) { DownloadStatus.Complete -> "ダウンロード済み"; DownloadStatus.Failed -> "ダウンロード失敗"; else -> "ダウンロード中" }, Modifier.size(16.dp), tint = if (download.status == DownloadStatus.Complete) Color(0xFF65B94E) else MaterialTheme.colorScheme.onSurfaceVariant)
                    val duration = progress?.durationMs?.takeIf { it > 0 } ?: episode.durationMs
                    val position = progress?.positionMs ?: 0
                    val time = when {
                        progress?.completed == true -> "再生済み"
                        position > 0 && duration > 0 -> "残り${formatDuration(duration - position)}"
                        duration > 0 -> formatDuration(duration)
                        else -> ""
                    }
                    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactDownloadButton(episode, state.library.downloads[episode.id], action)
                IconButton(onClick = { swipes.openId = null; action(PodcastAction.Play(episode)) }, enabled = state.playback.connected, modifier = Modifier.size(48.dp)) {
                    Icon(if (playing) Icons.Default.PauseCircleOutline else Icons.Default.PlayCircleOutline,
                        if (playing) "一時停止: ${episode.title}" else "再生: ${episode.title}", Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                }
                extra()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
    }
}

@Composable
internal fun SearchPage(state: PodcastUiState, action: (PodcastAction) -> Unit): Unit {
    var input by rememberSaveable { mutableStateOf("") }
    var byUrl by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun submit(): Unit {
        focus.clearFocus()
        action(if (byUrl) PodcastAction.Resolve(input) else PodcastAction.Search(input))
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !byUrl, onClick = { byUrl = false }, label = { Text("番組名") })
                FilterChip(selected = byUrl, onClick = { byUrl = true }, label = { Text("URLから登録") })
            }
            OutlinedTextField(value = input, onValueChange = { input = it; if (it.startsWith("https://") || it.startsWith("http://")) byUrl = true },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (byUrl) "番組URL・RSS URL" else "番組名・キーワード") },
                keyboardOptions = KeyboardOptions(keyboardType = if (byUrl) KeyboardType.Uri else KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (input.isNotBlank() && !state.searching) submit() }))
            Button(onClick = { submit() }, shape = RoundedCornerShape(8.dp), enabled = input.isNotBlank() && !state.searching, modifier = Modifier.align(Alignment.End)) {
                if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(if (byUrl) "番組を確認" else "検索する")
            }
            if (!byUrl) Text("Apple Podcasts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.searching) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (state.searchError != null) {
            EmptyState("検索できませんでした", state.searchError)
        } else if (state.results.isEmpty()) {
            if (state.searched) EmptyState("番組が見つかりませんでした", "別の番組名で検索してください。")
        } else LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.results.any { it.source.contains("候補") }) item { Text("番組名・配信者を確認して登録してください。", style = MaterialTheme.typography.bodyMedium) }
            items(state.results, key = { it.podcast.feedUrl }) { candidate ->
                val p = candidate.podcast
                val subscribed = state.library.podcasts.any { it.feedUrl == p.feedUrl }
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(p.artwork, p.title, Modifier.size(64.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(p.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(candidate.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { action(PodcastAction.Subscribe(candidate)) }, enabled = !subscribed && p.feedUrl !in state.busyFeeds) {
                            Text(if (subscribed) "登録済み" else if (p.feedUrl in state.busyFeeds) "登録中…" else "登録する")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

