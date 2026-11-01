@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
internal fun DownloadButton(episode: Episode, download: EpisodeDownload?, action: (PodcastAction) -> Unit): Unit {
    when (download?.status) {
        DownloadStatus.Complete -> TextButton(onClick = { action(PodcastAction.RemoveDownload(episode)) }) { Icon(AppIcons.DownloadDone, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("ダウンロードを削除") }
        DownloadStatus.Running, DownloadStatus.Waiting -> TextButton(onClick = { action(PodcastAction.RemoveDownload(episode)) }) {
            Icon(AppIcons.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (download.status == DownloadStatus.Waiting) "待機中・取消" else "${download.percent}%・取消")
        }
        else -> TextButton(onClick = { action(PodcastAction.Download(episode)) }) { Icon(AppIcons.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (download?.status == DownloadStatus.Failed) "再試行" else "ダウンロード") }
    }
}

@Composable
internal fun Cover(url: String, title: String, modifier: Modifier): Unit {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase(), modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun EmptyState(title: String, description: String, button: String? = null, onClick: () -> Unit = {}): Unit {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AppIcons.Headphones, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (button != null) { Spacer(Modifier.height(24.dp)); Button(onClick = onClick, shape = RoundedCornerShape(8.dp)) { Text(button) } }
    }
}

internal fun formatDuration(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60}時間${minutes % 60}分" else "${minutes}分"
}

@Composable
internal fun ConfirmationSheet(title: String, description: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit): Unit {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val animate = rememberSheetAction(sheet)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { animate(onDismiss) }) { Text("キャンセル") }
                TextButton(onClick = { animate(onConfirm) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(confirm) }
            }
        }
    }
}

@Composable
internal fun FavoriteButton(id: String, state: PodcastUiState, action: (PodcastAction) -> Unit): Unit {
    val favorite = id in state.library.favorites
    IconToggleButton(checked = favorite, onCheckedChange = { action(PodcastAction.Favorite(id, it)) }) {
        Icon(if (favorite) AppIcons.Star else AppIcons.StarBorder, if (favorite) "お気に入りを解除" else "お気に入りに追加", Modifier.size(22.dp))
    }
}
