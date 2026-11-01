@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun ManagementPage(
    state: PodcastUiState,
    action: (PodcastAction) -> Unit,
    back: () -> Unit,
    snackbar: SnackbarHostState,
): Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun bytes(value: Long): String =
        android.text.format.Formatter.formatShortFileSize(context, value.coerceAtLeast(0))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cleanup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            action(PodcastAction.LoadManagement)
            delay(5000)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ストレージと更新") },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(AppIcons.ArrowBack, "設定に戻る")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            state.management?.storage?.let { storage ->
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(
                            "音声" to storage.audioBytes,
                            "画像" to storage.artworkBytes,
                            "空き容量" to storage.freeBytes,
                        )
                        .forEach { (label, size) ->
                            Column {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                                Text(bytes(size), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                }
            }
            if (state.managementLoading && state.management == null)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            state.managementError?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("保存") })
                Tab(tab == 1, { tab = 1 }, text = { Text("更新") })
            }
            if (tab == 0)
                LazyColumn(
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { cleanup = "audio" },
                                enabled =
                                    state.management?.downloads?.any {
                                        state.library.progress[it.episode.id]?.completed == true
                                    } == true,
                            ) {
                                Text("再生済みの音声を削除")
                            }
                            TextButton(
                                onClick = { cleanup = "artwork" },
                                enabled = (state.management?.storage?.artworkBytes ?: 0) > 0,
                            ) {
                                Text("画像キャッシュを削除")
                            }
                        }
                    }
                    if (state.management?.downloads?.isEmpty() == true)
                        item {
                            Text("保存した音声はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    items(state.management?.downloads.orEmpty(), key = { it.episode.id }) { item ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        item.episode.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        item.podcastTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val info = item.download
                                    val label =
                                        when (info.status) {
                                            DownloadStatus.Complete ->
                                                "保存済み · ${bytes(info.totalBytes.takeIf { it > 0 } ?: info.downloadedBytes)}"
                                            DownloadStatus.Running ->
                                                "保存中 ${info.percent}% · ${bytes(info.downloadedBytes)}" +
                                                    if (info.totalBytes > 0)
                                                        " / ${bytes(info.totalBytes)}"
                                                    else ""
                                            DownloadStatus.Waiting -> info.reason.ifBlank { "開始待ち" }
                                            DownloadStatus.Failed ->
                                                info.reason.ifBlank { "ダウンロードに失敗しました" }
                                        }
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (info.status == DownloadStatus.Failed)
                                                MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (
                                    item.download.status == DownloadStatus.Failed ||
                                        (item.download.status == DownloadStatus.Waiting &&
                                            item.download.reason != "Wi-Fi 接続待ち")
                                )
                                    IconButton(
                                        onClick = {
                                            action(PodcastAction.RetryDownload(item.episode))
                                        }
                                    ) {
                                        Icon(
                                            AppIcons.Refresh,
                                            "ダウンロードを再試行: ${item.episode.title}",
                                        )
                                    }
                                IconButton(
                                    onClick = { action(PodcastAction.RemoveDownload(item.episode)) }
                                ) {
                                    Icon(
                                        AppIcons.DeleteOutline,
                                        "保存を削除: ${item.episode.title}",
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp))
                        }
                    }
                }
            else
                LazyColumn(
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = { action(PodcastAction.Refresh) },
                                enabled = !state.refreshing && state.library.podcasts.isNotEmpty(),
                            ) {
                                if (state.refreshing)
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                else Icon(AppIcons.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text("すべて更新")
                            }
                        }
                    }
                    items(
                        state.library.podcasts.sortedByDescending {
                            state.library.feedUpdates[it.feedUrl]?.error?.isNotEmpty() == true
                        },
                        key = { it.feedUrl },
                    ) { podcast ->
                        val status = state.library.feedUpdates[podcast.feedUrl]
                        fun time(value: Long): String =
                            DateTimeFormatter.ofPattern("M/d HH:mm")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(value))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        podcast.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (status == null || status.checkedMs == 0L) "未確認"
                                        else "最終確認 ${time(status.checkedMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (!status?.error.isNullOrEmpty()) {
                                        CopyableText(
                                            status!!.error,
                                            style =
                                                MaterialTheme.typography.bodySmall.copy(
                                                    color = MaterialTheme.colorScheme.error
                                                ),
                                        )
                                        if (status.updatedMs > 0)
                                            Text(
                                                "前回成功 ${time(status.updatedMs)}",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                    }
                                }
                                IconButton(
                                    onClick = { action(PodcastAction.RetryFeed(podcast.feedUrl)) },
                                    enabled =
                                        !state.refreshing && podcast.feedUrl !in state.busyFeeds,
                                ) {
                                    if (podcast.feedUrl in state.busyFeeds)
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    else Icon(AppIcons.Refresh, "番組を再取得: ${podcast.title}")
                                }
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp))
                        }
                    }
                }
        }
    }
    cleanup?.let { kind ->
        ConfirmationSheet(
            if (kind == "audio") "再生済みの音声を削除しますか？" else "画像キャッシュを削除しますか？",
            if (kind == "audio") "履歴とお気に入りは残ります。" else "画像は次に表示するときに取得します。",
            "削除",
            { cleanup = null },
        ) {
            cleanup = null
            action(
                if (kind == "audio") PodcastAction.RemoveCompletedDownloads
                else PodcastAction.ClearArtworkCache
            )
        }
    }
}
