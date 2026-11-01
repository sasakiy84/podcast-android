package io.github.sasakiy84.podcast

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Stable
internal class EpisodeSwipeState { var openId by mutableStateOf<String?>(null) }

/** Horizontal drags reveal explicit actions; vertical scrolling never commits a swipe action. */
@Composable
internal fun EpisodeSwipeActions(episode: Episode, state: PodcastUiState, action: (PodcastAction) -> Unit,
    swipes: EpisodeSwipeState, remove: (() -> Unit)?, content: @Composable () -> Unit): Unit {
    val density = LocalDensity.current
    val leading = with(density) { 72.dp.toPx() }
    val trailing = with(density) { 192.dp.toPx() }
    var offset by remember(episode.id) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(offset, if (dragging) snap() else tween(140), label = "episodeSwipe")
    LaunchedEffect(swipes.openId) { if (swipes.openId != episode.id) offset = 0f }
    fun close(): Unit { offset = 0f; swipes.openId = null }
    Box(Modifier.fillMaxWidth().semantics { contentDescription = "エピソード操作: ${episode.title}" }) {
        if (animated > 0f) Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { close(); action(PodcastAction.QuickQueue(episode.id)) }, modifier = Modifier.width(72.dp).fillMaxHeight().semantics { contentDescription = "キューに追加: ${episode.title}" }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(AppIcons.QueueMusic, null); Text("キュー", fontSize = 11.sp) }
            }
        }
        if (animated < 0f) Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val played = state.library.progress[episode.id]?.completed == true
            SwipeAction(if (remove != null) "外す" else if (played) "未再生" else "再生済み", AppIcons.Check) {
                close(); if (remove != null) remove() else action(PodcastAction.MarkPlayed(episode, !played))
            }
            val favorite = episode.id in state.library.favorites
            SwipeAction("お気に入り", if (favorite) AppIcons.Star else AppIcons.StarBorder) { close(); action(PodcastAction.Favorite(episode.id, !favorite)) }
            val download = state.library.downloads[episode.id]
            SwipeAction(if (download == null || download.status == DownloadStatus.Failed) "保存" else "削除", AppIcons.Download) {
                close(); action(if (download == null || download.status == DownloadStatus.Failed) PodcastAction.Download(episode) else PodcastAction.RemoveDownload(episode))
            }
        }
        Box(Modifier.offset { IntOffset(animated.roundToInt(), 0) }.background(MaterialTheme.colorScheme.surface)
            .pointerInput(episode.id, leading, trailing) {
                detectHorizontalDragGestures(onDragStart = { dragging = true; swipes.openId = episode.id },
                    onHorizontalDrag = { change, amount -> change.consume(); offset = (offset + amount).coerceIn(-trailing, leading) },
                    onDragEnd = { dragging = false; offset = when { offset > leading * 0.45f -> leading; offset < -trailing * 0.25f -> -trailing; else -> 0f }; if (offset == 0f) swipes.openId = null },
                    onDragCancel = { dragging = false; close() })
            }) { content() }
    }
}

@Composable
private fun RowScope.SwipeAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit): Unit {
    TextButton(onClick = onClick, modifier = Modifier.width(64.dp).fillMaxHeight(), contentPadding = PaddingValues(2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { Icon(icon, null, Modifier.size(20.dp)); Text(label, fontSize = 10.sp, maxLines = 1) }
    }
}
