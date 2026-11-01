@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.activity.compose.BackHandler
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class HomeTab(val label: String) { Library("ポッドキャスト"), Episodes("エピソード"), Queues("キュー"), Search("検索") }

@Composable
fun PodcastApp(state: PodcastUiState = PodcastUiState(), onAction: (PodcastAction) -> Unit = {}, initialFeedUrl: String? = null, openFeedRequest: Int = 0, onSettings: () -> Unit = {}): Unit {
    PodcastTheme {
        var tab by rememberSaveable { mutableStateOf(HomeTab.Library) }
        var selectedFeed by rememberSaveable { mutableStateOf<String?>(null) }
        var playerExpanded by rememberSaveable { mutableStateOf(false) }
        var queueEpisode by rememberSaveable { mutableStateOf<String?>(null) }
        var episodeSnapshot by remember { mutableStateOf<Episode?>(null) }
        var episodeDetails by rememberSaveable { mutableStateOf<String?>(null) }
        val routeStates = rememberSaveableStateHolder()
        var libraryMenu by remember { mutableStateOf(false) }
        var unsubscribe by remember { mutableStateOf<Podcast?>(null) }
        LaunchedEffect(initialFeedUrl, openFeedRequest) { if (initialFeedUrl != null) selectedFeed = initialFeedUrl }
        val selected = state.library.podcasts.firstOrNull { it.feedUrl == selectedFeed }
        val knownEpisodes = remember(state.library.episodes, state.podcastPages) { (state.podcastPages.values.flatMap { it.episodes } + state.library.episodes).associateBy { it.id } }
        val current = knownEpisodes[state.playback.episodeId]
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let { message -> snackbar.showSnackbar(message); onAction(PodcastAction.ClearMessage) }
        }
        BackHandler(enabled = playerExpanded || selected != null || tab != HomeTab.Library) {
            if (playerExpanded) playerExpanded = false else if (selectedFeed != null) selectedFeed = null else tab = HomeTab.Library
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
                                if (state.refreshing) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    if (state.refreshTotal > 0) Text("${state.refreshCompleted}/${state.refreshTotal}", style = MaterialTheme.typography.labelSmall)
                                } else Icon(Icons.Default.Refresh, "番組を更新")
                            }

                        }
                        if (tab == HomeTab.Library && selected == null) Box {
                            IconButton(onClick = { libraryMenu = true }, enabled = !state.opmlBusy) {
                                if (state.opmlBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.MoreVert, "番組一覧のメニュー")
                            }
                            DropdownMenu(expanded = libraryMenu, onDismissRequest = { libraryMenu = false }) {
                                PodcastSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.label) }, onClick = { onAction(PodcastAction.SortPodcasts(sort)); libraryMenu = false }, trailingIcon = { if (state.podcastSort == sort) Icon(Icons.Default.Check, null) }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("設定") }, onClick = { libraryMenu = false; onSettings() })
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
            RefreshableContent(enabled = selected != null || tab in listOf(HomeTab.Library, HomeTab.Episodes), isRefreshing = state.refreshing, onRefresh = { onAction(PodcastAction.Refresh) }, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when {
                    selected != null -> routeStates.SaveableStateProvider("podcast:${selected.feedUrl}") { PodcastDetails(selected, state, onAction, queue = { queueEpisode = it.id }) { episodeSnapshot = it; episodeDetails = it.id } }
                    tab == HomeTab.Library -> routeStates.SaveableStateProvider("library") { PodcastLibrary(state.library, state.podcastSort, { selectedFeed = it.feedUrl }, { tab = HomeTab.Search }) }
                    tab == HomeTab.Episodes -> routeStates.SaveableStateProvider("episodes") { EpisodesPage(state, onAction, queue = { queueEpisode = it.id }) { episodeSnapshot = it; episodeDetails = it.id } }
                    tab == HomeTab.Queues -> routeStates.SaveableStateProvider("queues") { PlaybackQueuesPage(state, onAction, onQueue = { queueEpisode = it.id }) { episodeSnapshot = it; episodeDetails = it.id } }
                    else -> routeStates.SaveableStateProvider("search") { SearchPage(state, onAction) }
                }
            }
        }
        if (playerExpanded && current != null) PlayerSheet(onDismiss = { playerExpanded = false }) { close ->
            PlayerPage(current, state, onAction, close, { close(); queueEpisode = current.id }, { close(); selectedFeed = null; tab = HomeTab.Queues })
        }
        (knownEpisodes[episodeDetails] ?: episodeSnapshot?.takeIf { it.id == episodeDetails && state.library.podcasts.any { p -> p.feedUrl == it.feedUrl } })?.let { episode ->
            LaunchedEffect(episode.id) { onAction(PodcastAction.LoadDescription(episode.id)); onAction(PodcastAction.LoadChapters(episode.id)) }
            ModalBottomSheet(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { episodeDetails = null }) {
                LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Row(verticalAlignment = Alignment.CenterVertically) { CopyableText(episode.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall); EpisodeCopyButton(episode, state); FavoriteButton(episode.id, state, onAction) } }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { onAction(PodcastAction.Play(episode)); episodeDetails = null }, shape = RoundedCornerShape(8.dp), enabled = state.playback.connected) { Text("再生") }
                            DownloadButton(episode, state.library.downloads[episode.id], onAction)
                        }
                    }
                    item { EpisodeChaptersSection(episode, state, onAction) }
                    item { OutlinedButton(onClick = { queueEpisode = episode.id; episodeDetails = null }, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text("キューに追加・編集") } }
                    if (episode.description.isNotBlank()) item { ContentText(state.descriptions[episode.id] ?: episode.description, style = MaterialTheme.typography.bodyLarge) }
                    item {
                        val completed = state.library.progress[episode.id]?.completed == true
                        TextButton(onClick = { onAction(PodcastAction.MarkPlayed(episode, !completed)); episodeDetails = null }) { Text(if (completed) "未再生に戻す" else "再生済みにする") }
                    }
                }
            }
        }
        (knownEpisodes[queueEpisode] ?: episodeSnapshot?.takeIf { it.id == queueEpisode && state.library.podcasts.any { p -> p.feedUrl == it.feedUrl } })?.let { episode ->
            QueueMembershipDialog(episode, state.library, onAction) { queueEpisode = null }
        }
        unsubscribe?.let { podcast ->
            ConfirmationSheet("購読を解除しますか？", "「${podcast.title}」と保存した音声を削除します。", "購読を解除",
                onDismiss = { unsubscribe = null }, onConfirm = { onAction(PodcastAction.Unsubscribe(podcast)); selectedFeed = null; unsubscribe = null })
        }
    }
}
