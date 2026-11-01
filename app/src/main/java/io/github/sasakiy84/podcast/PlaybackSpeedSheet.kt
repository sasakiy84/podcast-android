@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.round

@Composable
internal fun PlaybackSpeedSheet(state: PodcastUiState, feedUrl: String?, podcastScope: Boolean = false,
    action: (PodcastAction) -> Unit, dismiss: () -> Unit): Unit {
    var specific by remember { mutableStateOf(podcastScope && feedUrl != null) }
    val stored = if (specific) state.podcastSpeeds[feedUrl] ?: state.globalSpeed else state.globalSpeed
    var speed by remember(specific, stored) { mutableFloatStateOf(stored) }
    fun save(value: Float): Unit { speed = (round(value.coerceIn(0.5f, 3f) * 100) / 100); action(PodcastAction.Speed(speed, feedUrl.takeIf { specific })) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val animate = rememberSheetAction(sheet)
    val close = { animate(dismiss) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("再生速度", style = MaterialTheme.typography.titleMedium)
            if (feedUrl != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!specific, { specific = false }, label = { Text("全体") })
                FilterChip(specific, { specific = true }, label = { Text("この番組") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { save(speed - 0.1f) }, enabled = speed > 0.5f) { Icon(AppIcons.Remove, "速度を下げる") }
                Text("${speed}×", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = { save(speed + 0.1f) }, enabled = speed < 3f) { Icon(AppIcons.Add, "速度を上げる") }
            }
            Slider(speed, { speed = it }, valueRange = 0.5f..3f, steps = 49, onValueChangeFinished = { save(speed) }, modifier = Modifier.semantics { contentDescription = "再生速度を調整" })
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { value ->
                    TextButton(onClick = { save(value); close() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("${value}×") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (specific && feedUrl in state.podcastSpeeds) TextButton(onClick = { action(PodcastAction.UseGlobalSpeed(requireNotNull(feedUrl))); speed = state.globalSpeed }) { Text("全体設定に戻す") }
                TextButton(onClick = close) { Text("完了") }
            }
        }
    }
}
