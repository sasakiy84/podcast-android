@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun PlaybackQueuesPage(state: PodcastUiState, action: (PodcastAction) -> Unit, onQueue: (Episode) -> Unit = {}, details: (Episode) -> Unit): Unit {
    val swipes = remember { EpisodeSwipeState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingQueue by remember { mutableStateOf<PlaybackQueue?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var ruleEditor by remember { mutableStateOf<QueueRule?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var ruleSettings by rememberSaveable { mutableStateOf(false) }
    val queue = state.library.queues.firstOrNull { it.id == selectedId }
    BackHandler(enabled = queue != null) { selectedId = null }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (queue == null) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("キューを作成") } } }
            if (state.library.queues.isEmpty()) item {
                Text("キューはまだありません", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.library.queues, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().clickable { selectedId = item.id; ruleSettings = false }) {
                    Row(Modifier.padding(vertical = 22.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${item.episodeIds.size} エピソード", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "キュー一覧に戻る") }
                    Text(queue.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { editingQueue = queue }) { Icon(Icons.Default.Edit, "キュー名を変更") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.DeleteOutline, "キューを削除") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { action(PodcastAction.PlayQueue(queue.id)) }, shape = RoundedCornerShape(8.dp), enabled = queue.episodeIds.isNotEmpty() && state.playback.connected) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("キューを再生") }
                    TextButton(onClick = { ruleSettings = !ruleSettings }) { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("自動追加") }
                }
            }
            if (ruleSettings) {
                item { Text("自動追加する番組", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(state.library.queueRules.filter { it.queueId == queue.id }, key = { it.feedUrl }) { rule ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { ruleEditor = rule }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(state.library.podcasts.firstOrNull { it.feedUrl == rule.feedUrl }?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                                Text(if (rule.enabled) "新着を自動追加${if (rule.autoDownload) "・自動保存" else ""}" else "自動追加を停止中", style = MaterialTheme.typography.bodySmall)
                                if (rule.includeWords.isNotBlank()) Text("含む: ${rule.includeWords}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                if (rule.excludeWords.isNotBlank()) Text("除外: ${rule.excludeWords}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { ruleEditor = rule }) { Icon(Icons.Default.Edit, "自動追加ルールを編集") }
                            IconButton(onClick = { action(PodcastAction.DeleteQueueRule(queue.id, rule.feedUrl)) }) { Icon(Icons.Default.Close, "この自動追加ルールを削除") }
                        }
                    }
                }

            }
            item { Text("${queue.episodeIds.size} エピソード", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            if (queue.episodeIds.isEmpty()) item { Text("エピソードはまだありません", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(queue.episodeIds, key = { it }) { id ->
                state.library.episodes.firstOrNull { it.id == id }?.let { episode ->
                    Column {
                        EpisodeCard(episode, state.library.podcasts.firstOrNull { it.feedUrl == episode.feedUrl }, state, { event ->
                            if (event is PodcastAction.Play) action(PodcastAction.PlayQueue(queue.id, event.episode.id)) else action(event)
                        }, details, onQueue = onQueue, swipes = swipes, removeFromQueue = { action(PodcastAction.QueueMembership(queue.id, id, false)) }) {
                            Box {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreHoriz, "キュー内の操作: ${episode.title}") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("上へ移動") }, onClick = { action(PodcastAction.MoveQueueEpisode(queue.id, id, -1)); menu = false }, enabled = queue.episodeIds.firstOrNull() != id)
                                    DropdownMenuItem(text = { Text("下へ移動") }, onClick = { action(PodcastAction.MoveQueueEpisode(queue.id, id, 1)); menu = false }, enabled = queue.episodeIds.lastOrNull() != id)
                                    DropdownMenuItem(text = { Text("キューから外す") }, onClick = { action(PodcastAction.QueueMembership(queue.id, id, false)); menu = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating || editingQueue != null) QueueNameDialog(editingQueue, { creating = false; editingQueue = null }) { id, name -> action(PodcastAction.SaveQueue(id, name)) }
    if (deleting && queue != null) ConfirmationSheet("キューを削除しますか？", "「${queue.name}」と自動追加設定を削除します。番組・音声・履歴は残ります。", "削除",
        onDismiss = { deleting = false }, onConfirm = { action(PodcastAction.DeleteQueue(queue.id)); deleting = false; selectedId = null })
    if ((newRule || ruleEditor != null) && queue != null) QueueRuleDialog(queue, ruleEditor, state.library, { newRule = false; ruleEditor = null }) { rule, apply -> action(PodcastAction.SaveQueueRule(rule, apply)) }
}

@Composable
private fun QueueNameDialog(queue: PlaybackQueue?, dismiss: () -> Unit, save: (String, String) -> Unit): Unit {
    var name by remember { mutableStateOf(queue?.name.orEmpty()) }
    val id = remember { queue?.id ?: UUID.randomUUID().toString() }
    QueueFormSheet(if (queue == null) "キューを作成" else "キュー名を変更", dismiss, { save(id, name.trim()); dismiss() }, name.trim().length in 1..80) {
        OutlinedTextField(name, { name = it }, label = { Text("キュー名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = name.trim().length > 80)
    }
}

@Composable
internal fun QueueMembershipDialog(episode: Episode, library: Library, action: (PodcastAction) -> Unit, dismiss: () -> Unit): Unit {
    var name by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val completed = library.progress[episode.id]?.completed == true
    QueueFormSheet("キューに追加・編集", dismiss, dismiss, true, "完了", showCancel = false) {
        Text(episode.title, style = MaterialTheme.typography.titleSmall)
        if (completed) Text("再生済みの回は、詳細画面で未再生に戻してから追加できます。")
        for (queue in library.queues) Row(Modifier.fillMaxWidth().toggleable(value = episode.id in queue.episodeIds, enabled = !completed, role = Role.Checkbox, onValueChange = { action(PodcastAction.QueueMembership(queue.id, episode.id, it)) }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = episode.id in queue.episodeIds, onCheckedChange = null, enabled = !completed)
            Text(queue.name, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(name, { name = it }, label = { Text("新しいキュー名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextButton(onClick = { action(PodcastAction.SaveQueue(newId, name.trim(), episode.id)); name = ""; newId = UUID.randomUUID().toString() }, enabled = !completed && name.trim().length in 1..80, modifier = Modifier.fillMaxWidth()) { Text("作成して追加") }
    }
}

@Composable
internal fun QueueRuleDialog(queue: PlaybackQueue, existing: QueueRule?, library: Library, dismiss: () -> Unit, save: (QueueRule, Boolean) -> Unit): Unit {
    var feed by remember { mutableStateOf(existing?.feedUrl ?: library.podcasts.firstOrNull()?.feedUrl.orEmpty()) }
    var include by remember { mutableStateOf(existing?.includeWords.orEmpty()) }
    var exclude by remember { mutableStateOf(existing?.excludeWords.orEmpty()) }
    var all by remember { mutableStateOf(existing?.matchAll ?: false) }
    var download by remember { mutableStateOf(existing?.autoDownload ?: false) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var apply by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    QueueFormSheet("番組の自動追加", dismiss, { save(QueueRule(queue.id, feed, include, exclude, all, download, enabled), apply); dismiss() }, feed.isNotBlank()) {
        CopyableText(library.podcasts.firstOrNull { it.feedUrl == feed }?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
        ToggleRow("新着を自動追加", enabled) { enabled = it }
        OutlinedTextField(include, { include = it }, label = { Text("含む語（カンマ区切り）") }, placeholder = { Text("空欄ならすべて") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Row(Modifier.fillMaxWidth().toggleable(all, role = Role.Checkbox, onValueChange = { all = it }), verticalAlignment = Alignment.CenterVertically) { Checkbox(all, null); Text("すべての語に一致", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(exclude, { exclude = it }, label = { Text("除外する語（カンマ区切り）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Text("タイトル・説明から検索", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("一致した回を自動ダウンロード", download) { download = it }
        Row(Modifier.fillMaxWidth().toggleable(apply, role = Role.Checkbox, onValueChange = { apply = it }), verticalAlignment = Alignment.CenterVertically) { Checkbox(apply, null); Text("既存の未再生にも追加", Modifier.weight(1f)) }
    }
}

@Composable
internal fun QueueFormSheet(title: String, dismiss: () -> Unit, save: () -> Unit, saveEnabled: Boolean, saveText: String = "保存", showCancel: Boolean = true, content: @Composable ColumnScope.() -> Unit): Unit {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val animate = rememberSheetAction(sheet)
    ModalBottomSheet(onDismissRequest = dismiss, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (showCancel) TextButton(onClick = { animate(dismiss) }) { Text("キャンセル") }
                Button(onClick = { animate(save) }, shape = RoundedCornerShape(8.dp), enabled = saveEnabled) { Text(saveText) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, change: (Boolean) -> Unit): Unit {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}
