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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class HomeTab(val label: String) { Library("ポッドキャスト"), Episodes("エピソード"), Queues("キュー"), Search("検索") }
private enum class EpisodeFilter(val label: String) { All("すべて"), Unplayed("未再生"), Downloaded("ダウンロード") }

@Composable
fun PodcastApp(state: PodcastUiState = PodcastUiState(), onAction: (PodcastAction) -> Unit = {}, onImportOpml: () -> Unit = {}, onExportOpml: () -> Unit = {}): Unit {
    val dark = isSystemInDarkTheme()
    val accent = Color(0xFF007E9E)
    val playerColors = darkColorScheme(onSurface = Color(0xFFF4F5F6), onSurfaceVariant = Color(0xFF979CA3), outlineVariant = Color(0xFF34383C), primary = Color(0xFF55CEE9), onPrimary = Color(0xFF003640), background = Color(0xFF08090A), surface = Color(0xFF08090A), surfaceContainer = Color(0xFF1A1C1F), surfaceContainerLow = Color(0xFF111316), secondaryContainer = Color(0xFF17343D), onSecondaryContainer = Color(0xFFC9EFF7))
    val colors = if (dark) playerColors else lightColorScheme(onSurface = Color(0xFF1B1E22), onSurfaceVariant = Color(0xFF666D75), outlineVariant = Color(0xFFDDE1E5), primary = accent, onPrimary = Color.White, background = Color.White, surface = Color.White, surfaceContainer = Color(0xFFF0F3F6), surfaceContainerLow = Color(0xFFF6F8FA), secondaryContainer = Color(0xFFE0F4F8), onSecondaryContainer = Color(0xFF164957))
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf(HomeTab.Library) }
        var selectedFeed by rememberSaveable { mutableStateOf<String?>(null) }
        var playerExpanded by rememberSaveable { mutableStateOf(false) }
        var queueEpisode by rememberSaveable { mutableStateOf<String?>(null) }
        var episodeDetails by rememberSaveable { mutableStateOf<String?>(null) }
        var libraryMenu by remember { mutableStateOf(false) }
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
                        if (tab == HomeTab.Library && selected == null) Box {
                            IconButton(onClick = { libraryMenu = true }, enabled = !state.opmlBusy) {
                                if (state.opmlBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.MoreVert, "番組一覧のメニュー")
                            }
                            DropdownMenu(expanded = libraryMenu, onDismissRequest = { libraryMenu = false }) {
                                DropdownMenuItem(text = { Text("OPML をインポート") }, onClick = { libraryMenu = false; onImportOpml() })
                                DropdownMenuItem(text = { Text("OPML をエクスポート") }, enabled = state.library.podcasts.isNotEmpty(), onClick = { libraryMenu = false; onExportOpml() })
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (current != null) MiniPlayer(current, state, onAction, { tab = HomeTab.Queues; selectedFeed = null }) { playerExpanded = true }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        HomeTab.entries.forEach { item ->
                            NavigationBarItem(modifier = Modifier.semantics { contentDescription = "タブ: ${item.label}" }, selected = tab == item, onClick = { tab = item; selectedFeed = null },
                                icon = { Icon(when (item) { HomeTab.Library -> Icons.Default.GridView; HomeTab.Episodes -> Icons.Default.Headphones; HomeTab.Queues -> Icons.AutoMirrored.Filled.QueueMusic; HomeTab.Search -> Icons.Default.Search }, null) },
                                label = { Text(if (item == HomeTab.Library) "番組" else item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
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
                    tab == HomeTab.Queues -> PlaybackQueuesPage(state, onAction) { episodeDetails = it.id }
                    else -> SearchPage(state, onAction)
                }
            }
        }
        if (playerExpanded && current != null) Dialog(onDismissRequest = { playerExpanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.let {
                    WindowCompat.getInsetsController(it, view).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
            }
            MaterialTheme(colorScheme = playerColors) {
                Surface(Modifier.fillMaxSize()) {
                    PlayerPage(current, state, onAction, { playerExpanded = false }, { playerExpanded = false; queueEpisode = current.id }, { playerExpanded = false; selectedFeed = null; tab = HomeTab.Queues })
                }
            }
        }
        state.library.episodes.firstOrNull { it.id == episodeDetails }?.let { episode ->
            ModalBottomSheet(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { episodeDetails = null }) {
                LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text(episode.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { onAction(PodcastAction.Play(episode)); episodeDetails = null }, shape = RoundedCornerShape(8.dp), enabled = state.playback.connected) { Text("再生") }
                            DownloadButton(episode, state.library.downloads[episode.id], onAction)
                        }
                    }
                    item { OutlinedButton(onClick = { queueEpisode = episode.id; episodeDetails = null }, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text("キューに追加・編集") } }
                    if (episode.description.isNotBlank()) item { Text(episode.description, style = MaterialTheme.typography.bodyLarge) }
                    item {
                        val completed = state.library.progress[episode.id]?.completed == true
                        TextButton(onClick = { onAction(PodcastAction.MarkPlayed(episode, !completed)); episodeDetails = null }) { Text(if (completed) "未再生に戻す" else "再生済みにする") }
                    }
                }
            }
        }
        state.library.episodes.firstOrNull { it.id == queueEpisode }?.let { episode ->
            QueueMembershipDialog(episode, state.library, onAction) { queueEpisode = null }
        }
        unsubscribe?.let { podcast ->
            ConfirmationSheet("購読を解除しますか？", "「${podcast.title}」と保存した音声を削除します。", "購読を解除",
                onDismiss = { unsubscribe = null }, onConfirm = { onAction(PodcastAction.Unsubscribe(podcast)); selectedFeed = null; unsubscribe = null })
        }
    }
}

@Composable
private fun PodcastLibrary(podcasts: List<Podcast>, open: (Podcast) -> Unit, add: () -> Unit): Unit {
    if (podcasts.isEmpty()) {
        EmptyState("ポッドキャストを追加", "", "番組を探す", add)
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(100.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(podcasts, key = { it.feedUrl }) { podcast ->
            Box(Modifier.clip(RoundedCornerShape(5.dp)).clickable { open(podcast) }.semantics { contentDescription = podcast.title }) {
                Cover(podcast.artwork, podcast.title, Modifier.fillMaxWidth().aspectRatio(1f))
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
            EmptyState(if (filter == EpisodeFilter.Downloaded) "ダウンロードはまだありません" else "エピソードはまだありません", "")
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(episodes, key = { it.id }) { episode -> EpisodeCard(episode, state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }, state, action, details) }
        }
    }
}

@Composable
internal fun EpisodeCard(episode: Episode, podcast: Podcast?, state: PodcastUiState, action: (PodcastAction) -> Unit, details: (Episode) -> Unit, extra: @Composable () -> Unit = {}): Unit {
    val progress = state.library.progress[episode.id]
    val playing = state.playback.episodeId == episode.id && state.playback.playing
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { details(episode) }.semantics { contentDescription = "エピソード詳細: ${episode.title}" }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, podcast?.title ?: episode.title, Modifier.size(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (episode.published > 0) Text(DateTimeFormatter.ofPattern("M月d日").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(episode.published)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(episode.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                        else -> podcast?.title.orEmpty()
                    }
                    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { action(PodcastAction.Play(episode)) }, enabled = state.playback.connected, modifier = Modifier.size(48.dp)) {
                    Icon(if (playing) Icons.Default.PauseCircleOutline else Icons.Default.PlayCircleOutline,
                        if (playing) "一時停止: ${episode.title}" else "再生: ${episode.title}", Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                }
                extra()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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

@Composable
private fun MiniPlayer(episode: Episode, state: PodcastUiState, action: (PodcastAction) -> Unit, queues: () -> Unit, expand: () -> Unit): Unit {
    val podcast = state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }
    Surface(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(48.dp).clickable(onClick = expand).semantics { contentDescription = "プレーヤーを開く"; stateDescription = episode.title }, contentAlignment = Alignment.Center) {
                            Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, episode.title, Modifier.size(46.dp))
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { action(PodcastAction.Skip(-15_000)) }, enabled = state.playback.connected) { Icon(Icons.Default.Replay, "15秒戻る（ミニプレーヤー）") }
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
private fun PlayerPage(episode: Episode, state: PodcastUiState, action: (PodcastAction) -> Unit, close: () -> Unit, membership: () -> Unit, queues: () -> Unit): Unit {
    val playback = state.playback
    val podcast = state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }
    val duration = playback.durationMs.takeIf { it > 0 } ?: episode.durationMs
    var scrubbing by remember(episode.id) { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.Default.KeyboardArrowDown, "プレーヤーを閉じる") }
            TextButton(onClick = { showDetails = false }) { Text("再生中", color = if (!showDetails) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { showDetails = true }) { Text("詳細", color = if (showDetails) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = queues) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "キューを開く") }
        }
        if (showDetails) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text(episode.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            if (episode.description.isNotBlank()) item { Text(episode.description, style = MaterialTheme.typography.bodyLarge) }
        } else BoxWithConstraints(Modifier.weight(1f)) {
            val fontScale = LocalDensity.current.fontScale
            val artSize = minOf(maxWidth - 48.dp, maxHeight - 320.dp - 80.dp * (fontScale - 1f).coerceAtLeast(0f), 400.dp).coerceAtLeast(100.dp)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { Cover(episode.artwork.ifBlank { podcast?.artwork.orEmpty() }, episode.title, Modifier.size(artSize)) }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(podcast?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { action(PodcastAction.Skip(-15_000)) }, enabled = playback.connected, modifier = Modifier.size(56.dp).semantics { contentDescription = "15秒戻る" }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Replay, null, Modifier.size(40.dp))
                                Text("15", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        FilledIconButton(onClick = { action(PodcastAction.TogglePlayback) }, enabled = playback.connected, modifier = Modifier.size(76.dp).semantics { contentDescription = if (playback.playing) "一時停止" else "再生を再開" }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                            if (playback.buffering) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.surface)
                            else Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(40.dp))
                        }
                        IconButton(onClick = { action(PodcastAction.Skip(30_000)) }, enabled = playback.connected, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Forward30, "30秒進む", Modifier.size(36.dp)) }
                    }
                }
            }
        }
        Surface(Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { speedMenu = true }, enabled = playback.connected, modifier = Modifier.semantics { contentDescription = "再生速度 ${playback.speed}×" }) { Text("${playback.speed}×", fontWeight = FontWeight.Bold) }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { speed -> DropdownMenuItem(text = { Text("${speed}×") }, onClick = { action(PodcastAction.Speed(speed)); speedMenu = false }, trailingIcon = { if (speed == playback.speed) Icon(Icons.Default.Check, null) }) }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = membership) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "キューに追加・編集") }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreHoriz, "その他の操作") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        val completed = state.library.progress[episode.id]?.completed == true
                        DropdownMenuItem(text = { Text(if (completed) "未再生に戻す" else "再生済みにする") }, onClick = { action(PodcastAction.MarkPlayed(episode, !completed)); more = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadButton(episode: Episode, download: EpisodeDownload?, action: (PodcastAction) -> Unit): Unit {
    when (download?.status) {
        DownloadStatus.Complete -> TextButton(onClick = { action(PodcastAction.RemoveDownload(episode)) }) { Icon(Icons.Default.DownloadDone, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("ダウンロードを削除") }
        DownloadStatus.Running, DownloadStatus.Waiting -> TextButton(onClick = { action(PodcastAction.RemoveDownload(episode)) }) {
            Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (download.status == DownloadStatus.Waiting) "待機中・取消" else "${download.percent}%・取消")
        }
        else -> TextButton(onClick = { action(PodcastAction.Download(episode)) }) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (download?.status == DownloadStatus.Failed) "再試行" else "ダウンロード") }
    }
}

@Composable
private fun Cover(url: String, title: String, modifier: Modifier): Unit {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase(), modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun EmptyState(title: String, description: String, button: String? = null, onClick: () -> Unit = {}): Unit {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Headphones, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (button != null) { Spacer(Modifier.height(24.dp)); Button(onClick = onClick, shape = RoundedCornerShape(8.dp)) { Text(button) } }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60}時間${minutes % 60}分" else "${minutes}分"
}

@Composable
internal fun ConfirmationSheet(title: String, description: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit): Unit {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(confirm) }
            }
        }
    }
}
