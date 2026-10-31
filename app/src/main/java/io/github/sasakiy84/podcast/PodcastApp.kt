@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class HomeTab(val label: String) { Library("ポッドキャスト"), Episodes("エピソード"), Search("検索") }
private enum class EpisodeFilter(val label: String) { All("すべて"), Unplayed("未再生"), Downloaded("ダウンロード") }

@Composable
fun PodcastApp(state: PodcastUiState = PodcastUiState(), onAction: (PodcastAction) -> Unit = {}): Unit {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(surfaceContainerLow = Color(0xFF221E1C), surfaceContainerHigh = Color(0xFF302926), surfaceContainerHighest = Color(0xFF3B322E), secondaryContainer = Color(0xFF543832), onSecondaryContainer = Color(0xFFFFDAD3), primary = Color(0xFFFF8E80), surface = Color(0xFF181818), background = Color(0xFF181818))
    else lightColorScheme(surfaceContainerLow = Color(0xFFF7F3F0), surfaceContainerHigh = Color(0xFFEFE7E2), surfaceContainerHighest = Color(0xFFE8DED8), secondaryContainer = Color(0xFFF5DDD6), onSecondaryContainer = Color(0xFF6B3027), primary = Color(0xFFB9382D), surface = Color(0xFFFCFAF7), background = Color(0xFFFCFAF7), surfaceContainer = Color(0xFFF3EFEB))
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf(HomeTab.Library) }
        var selectedFeed by rememberSaveable { mutableStateOf<String?>(null) }
        var playerExpanded by rememberSaveable { mutableStateOf(false) }
        var episodeDetails by rememberSaveable { mutableStateOf<String?>(null) }
        var unsubscribe by remember { mutableStateOf<Podcast?>(null) }
        val selected = state.library.podcasts.firstOrNull { it.feedUrl == selectedFeed }
        val current = state.library.episodes.firstOrNull { it.id == state.playback.episodeId }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let { message -> snackbar.showSnackbar(message); onAction(PodcastAction.ClearMessage) }
        }
        BackHandler(enabled = playerExpanded || selected != null) {
            if (playerExpanded) playerExpanded = false else selectedFeed = null
        }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(selected?.title ?: tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                    navigationIcon = { if (selected != null) IconButton(onClick = { selectedFeed = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "一覧に戻る") } },
                    actions = {
                        if (selected != null) IconButton(onClick = { unsubscribe = selected }) { Icon(Icons.Default.MoreVert, "購読を解除") }
                        if (tab != HomeTab.Search) {
                            IconButton(onClick = { onAction(PodcastAction.Refresh) }, enabled = !state.refreshing && state.library.podcasts.isNotEmpty()) {
                                if (state.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "番組を更新")
                            }
                            IconButton(onClick = { selectedFeed = null; tab = HomeTab.Search }) { Icon(Icons.Default.Add, "ポッドキャストを追加") }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (current != null) MiniPlayer(current, state, onAction) { playerExpanded = true }
                    NavigationBar {
                        HomeTab.entries.forEach { item ->
                            NavigationBarItem(modifier = Modifier.semantics { contentDescription = "タブ: ${item.label}" }, selected = tab == item, onClick = { tab = item; selectedFeed = null },
                                icon = { Icon(when (item) { HomeTab.Library -> Icons.Default.LibraryMusic; HomeTab.Episodes -> Icons.Default.Headphones; HomeTab.Search -> Icons.Default.Search }, null) },
                                label = { Text(item.label) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when {
                    selected != null -> PodcastDetails(selected, state, onAction) { episodeDetails = it.id }
                    tab == HomeTab.Library -> PodcastLibrary(state.library.podcasts, { selectedFeed = it.feedUrl }, { tab = HomeTab.Search })
                    tab == HomeTab.Episodes -> EpisodesPage(state, onAction) { episodeDetails = it.id }
                    else -> SearchPage(state, onAction)
                }
            }
        }
        if (playerExpanded && current != null) ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { playerExpanded = false }) { PlayerPage(current, state, onAction) }
        state.library.episodes.firstOrNull { it.id == episodeDetails }?.let { episode ->
            ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { episodeDetails = null }) {
                LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text(episode.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { onAction(PodcastAction.Play(episode)); episodeDetails = null }, enabled = state.playback.connected) { Text("再生") }
                            DownloadButton(episode, state.library.downloads[episode.id], onAction)
                        }
                    }
                    item { Text(episode.description.ifBlank { "このエピソードには説明がありません。" }, style = MaterialTheme.typography.bodyLarge) }
                    item {
                        val completed = state.library.progress[episode.id]?.completed == true
                        TextButton(onClick = { onAction(PodcastAction.MarkPlayed(episode, !completed)); episodeDetails = null }) { Text(if (completed) "未再生に戻す" else "再生済みにする") }
                    }
                }
            }
        }
        unsubscribe?.let { podcast ->
            AlertDialog(onDismissRequest = { unsubscribe = null }, title = { Text("購読を解除しますか？") },
                text = { Text("「${podcast.title}」とダウンロードした音声をこの端末から削除します。") },
                confirmButton = { TextButton(onClick = { onAction(PodcastAction.Unsubscribe(podcast)); selectedFeed = null; unsubscribe = null }) { Text("購読を解除") } },
                dismissButton = { TextButton(onClick = { unsubscribe = null }) { Text("キャンセル") } })
        }
    }
}

@Composable
private fun PodcastLibrary(podcasts: List<Podcast>, open: (Podcast) -> Unit, add: () -> Unit): Unit {
    if (podcasts.isEmpty()) {
        EmptyState("好きな番組を、あなたの端末に。", "番組名や共有URLから登録できます。", "番組を探す", add)
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(128.dp), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(podcasts, key = { it.feedUrl }) { podcast ->
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { open(podcast) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Cover(podcast.artwork, podcast.title, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(podcast.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (podcast.author.isNotBlank()) Text(podcast.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PodcastDetails(podcast: Podcast, state: PodcastUiState, action: (PodcastAction) -> Unit, details: (Episode) -> Unit): Unit {
    val episodes = state.library.episodes.filter { it.feedUrl == podcast.feedUrl }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(podcast.artwork, podcast.title, Modifier.size(104.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(podcast.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(podcast.author, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text("${episodes.size} エピソード", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (podcast.description.isNotBlank()) item { Text(podcast.description, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        item { Text("エピソード", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
        if (episodes.isEmpty()) item { Text("公開されている音声エピソードはありません。") }
        items(episodes, key = { it.id }) { EpisodeCard(it, podcast, state, action, details) }
    }
}

@Composable
private fun EpisodesPage(state: PodcastUiState, action: (PodcastAction) -> Unit, details: (Episode) -> Unit): Unit {
    var filter by rememberSaveable { mutableStateOf(EpisodeFilter.All) }
    val episodes = state.library.episodes.filter { episode -> when (filter) {
        EpisodeFilter.All -> true
        EpisodeFilter.Unplayed -> state.library.progress[episode.id]?.completed != true
        EpisodeFilter.Downloaded -> state.library.downloads.containsKey(episode.id)
    } }
    Column {
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EpisodeFilter.entries.forEach { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label) }) }
        }
        if (episodes.isEmpty()) {
            EmptyState(if (filter == EpisodeFilter.Downloaded) "ダウンロードはまだありません" else "エピソードはまだありません", if (filter == EpisodeFilter.Downloaded) "保存した音声はオフラインでも再生できます。" else "番組を登録・更新すると、ここに並びます。")
        } else LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(episodes, key = { it.id }) { episode -> EpisodeCard(episode, state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }, state, action, details) }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, podcast: Podcast?, state: PodcastUiState, action: (PodcastAction) -> Unit, details: (Episode) -> Unit): Unit {
    val progress = state.library.progress[episode.id]
    val playing = state.playback.episodeId == episode.id && state.playback.playing
    OutlinedCard(onClick = { details(episode) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, podcast?.title ?: episode.title, Modifier.size(52.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(podcast?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(onClick = { action(PodcastAction.Play(episode)) }, enabled = state.playback.connected) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "一時停止: ${episode.title}" else "再生: ${episode.title}") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (episode.published > 0) Text(DateTimeFormatter.ofPattern("yyyy/M/d").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(episode.published)), style = MaterialTheme.typography.labelSmall)
                    Text(if (progress?.completed == true) "再生済み" else if ((progress?.positionMs ?: 0) > 0) "${formatTime(progress?.positionMs ?: 0)} / ${formatTime(progress?.durationMs?.takeIf { it > 0 } ?: episode.durationMs)}" else if (episode.durationMs > 0) formatTime(episode.durationMs) else "音声エピソード", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DownloadButton(episode, state.library.downloads[episode.id], action)
            }
            if (progress != null && progress.durationMs > 0 && !progress.completed) LinearProgressIndicator(progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SearchPage(state: PodcastUiState, action: (PodcastAction) -> Unit): Unit {
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
            Button(onClick = { submit() }, enabled = input.isNotBlank() && !state.searching, modifier = Modifier.fillMaxWidth()) {
                if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(if (byUrl) "番組を確認" else "検索する")
            }
            Text(if (byUrl) "Apple Podcasts・Spotify・Amazon Music・Pocket Casts・RSS" else "Apple Podcasts から検索", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.searching) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (state.searchError != null) {
            EmptyState("検索できませんでした", state.searchError)
        } else if (state.results.isEmpty()) {
            EmptyState(if (state.searched) "番組が見つかりませんでした" else "次のお気に入りを見つけよう", if (state.searched) "別の番組名や公式 RSS URLを試してください。" else "気になる番組を検索するか、番組の共有URLを貼り付けてください。")
        } else LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.results.any { it.source.contains("候補") }) item { Text("番組名・配信者を確認して登録してください。", style = MaterialTheme.typography.bodyMedium) }
            items(state.results, key = { it.podcast.feedUrl }) { candidate ->
                val p = candidate.podcast
                val subscribed = state.library.podcasts.any { it.feedUrl == p.feedUrl }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(p.artwork, p.title, Modifier.size(64.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(p.author, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(candidate.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { action(PodcastAction.Subscribe(candidate)) }, enabled = !subscribed && p.feedUrl !in state.busyFeeds, modifier = Modifier.fillMaxWidth()) {
                            Text(if (subscribed) "登録済み" else if (p.feedUrl in state.busyFeeds) "登録中…" else "登録する")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(episode: Episode, state: PodcastUiState, action: (PodcastAction) -> Unit, expand: () -> Unit): Unit {
    val podcast = state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }
    Surface(tonalElevation = 4.dp) {
        Column {
            if (state.playback.durationMs > 0) LinearProgressIndicator(progress = { (state.playback.positionMs.toFloat() / state.playback.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = expand).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, episode.title, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(podcast?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { action(PodcastAction.TogglePlayback) }, enabled = state.playback.connected,
                    modifier = Modifier.semantics { contentDescription = if (state.playback.playing) "一時停止" else "再生を再開" }) {
                    if (state.playback.buffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(if (state.playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                }
            }
        }
    }
}

@Composable
private fun PlayerPage(episode: Episode, state: PodcastUiState, action: (PodcastAction) -> Unit): Unit {
    val playback = state.playback
    val podcast = state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }
    val duration = playback.durationMs.takeIf { it > 0 } ?: episode.durationMs
    var scrubbing by remember(episode.id) { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, episode.title, Modifier.size(200.dp)) }
        item { Text(episode.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text(podcast?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Column {
                Slider(value = scrubbing ?: playback.positionMs.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = { scrubbing = it },
                    onValueChangeFinished = { scrubbing?.let { action(PodcastAction.Seek(it.toLong())) }; scrubbing = null },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && playback.connected,
                    modifier = Modifier.semantics { contentDescription = "再生位置" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(scrubbing?.toLong() ?: playback.positionMs), style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { action(PodcastAction.Skip(-15_000)) }, enabled = playback.connected, contentPadding = PaddingValues(8.dp), modifier = Modifier.width(64.dp)) { Text("−15秒") }
                FilledIconButton(onClick = { action(PodcastAction.TogglePlayback) }, enabled = playback.connected, modifier = Modifier.size(72.dp).semantics { contentDescription = if (playback.playing) "一時停止" else "再生を再開" }) {
                    if (playback.buffering) CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(36.dp))
                }
                TextButton(onClick = { action(PodcastAction.Skip(30_000)) }, enabled = playback.connected, contentPadding = PaddingValues(8.dp), modifier = Modifier.width(64.dp)) { Text("+30秒") }
            }
        }
        item {
            Box {
                OutlinedButton(onClick = { speedMenu = true }, enabled = playback.connected) { Text("再生速度 ${playback.speed}×") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { speed ->
                        DropdownMenuItem(text = { Text("${speed}×") }, onClick = { action(PodcastAction.Speed(speed)); speedMenu = false }, trailingIcon = { if (speed == playback.speed) Icon(Icons.Default.Check, null) })
                    }
                }
            }
        }
        item { DownloadButton(episode, state.library.downloads[episode.id], action) }
    }
}

@Composable
private fun DownloadButton(episode: Episode, download: EpisodeDownload?, action: (PodcastAction) -> Unit): Unit {
    when (download?.status) {
        DownloadStatus.Complete -> TextButton(onClick = { action(PodcastAction.RemoveDownload(episode)) }) { Icon(Icons.Default.DownloadDone, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("保存済み・削除") }
        DownloadStatus.Running, DownloadStatus.Waiting -> TextButton(onClick = { action(PodcastAction.RemoveDownload(episode)) }) {
            Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (download.status == DownloadStatus.Waiting) "待機中・取消" else "${download.percent}%・取消")
        }
        else -> TextButton(onClick = { action(PodcastAction.Download(episode)) }) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (download?.status == DownloadStatus.Failed) "再試行" else "保存する") }
    }
}

@Composable
private fun Cover(url: String, title: String, modifier: Modifier): Unit {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun EmptyState(title: String, description: String, button: String? = null, onClick: () -> Unit = {}): Unit {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Headphones, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (button != null) { Spacer(Modifier.height(24.dp)); Button(onClick = onClick) { Text(button) } }
    }
}
