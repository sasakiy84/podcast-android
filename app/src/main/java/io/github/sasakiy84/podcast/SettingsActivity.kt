@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private val model: PodcastsViewModel by viewModels()
    private val importOpml =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(model::importOpml)
        }
    private val exportOpml =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml+xml")) {
            it?.let(model::exportOpml)
        }

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeVisibleScreen() }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            SettingsPage(
                state,
                model::dispatch,
                { finish() },
                { importOpml.launch(arrayOf("*/*")) },
                { exportOpml.launch("podcasts.opml") },
            )
            state.mobileDataPlaybackRequest?.let { request ->
                PodcastTheme { MobileDataPlaybackDialog(request, model::dispatch) }
            }
        }
    }
}

@Composable
internal fun SettingsPage(
    state: PodcastUiState,
    action: (PodcastAction) -> Unit,
    back: () -> Unit,
    importOpml: () -> Unit,
    exportOpml: () -> Unit,
): Unit {
    PodcastTheme {
        var speed by remember { mutableStateOf(false) }
        val snackbar = rememberAppSnackbar(state, action)
        var management by rememberSaveable { mutableStateOf(false) }
        var autoDownloads by rememberSaveable { mutableStateOf(false) }
        androidx.activity.compose.BackHandler(enabled = management) { management = false }
        androidx.activity.compose.BackHandler(enabled = autoDownloads) { autoDownloads = false }
        if (management) {
            ManagementPage(state, action, { management = false }, snackbar)
            return@PodcastTheme
        }
        if (autoDownloads) {
            AutoDownloadSettingsPage(state, action, { autoDownloads = false }, snackbar)
            return@PodcastTheme
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("設定") },
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(AppIcons.ArrowBack, "戻る")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier.padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(onClick = { autoDownloads = true }) { Text("自動ダウンロード") }
                SettingsSwitch("モバイルデータ通信での再生を確認", state.warnOnMobilePlayback) {
                    action(PodcastAction.WarnOnMobilePlayback(it))
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(state.normalizeVolume, role = Role.Switch,
                        onValueChange = { action(PodcastAction.NormalizeVolume(it)) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("音量をそろえる", Modifier.weight(1f))
                    Switch(state.normalizeVolume, null)
                }
                if (state.normalizeVolume) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !state.louderVolume, onClick = { action(PodcastAction.LouderVolume(false)) }, label = { Text("標準") })
                        FilterChip(selected = state.louderVolume, onClick = { action(PodcastAction.LouderVolume(true)) }, label = { Text("大きめ") })
                    }
                }
                Text("バックグラウンド更新", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    refreshIntervals.forEach { hours ->
                        FilterChip(
                            selected = state.refreshHours == hours,
                            onClick = { action(PodcastAction.RefreshInterval(hours)) },
                            label = { Text(if (hours == 0) "オフ" else "${hours}h") },
                        )
                    }
                }
                Text("デフォルトキュー", style = MaterialTheme.typography.titleMedium)
                val queues =
                    (state.library.queues +
                        if (state.library.queues.none { it.id == "default" })
                            listOf(PlaybackQueue("default", "あとで聴く"))
                        else emptyList())
                queues.forEach { queue ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = state.defaultQueueId == queue.id,
                                role = Role.RadioButton,
                                onClick = { action(PodcastAction.DefaultQueue(queue.id)) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.defaultQueueId == queue.id, onClick = null)
                        Text(queue.name)
                    }
                }
                TextButton(onClick = { speed = true }) { Text("再生速度 ${state.globalSpeed}×") }
                HorizontalDivider()
                TextButton(onClick = { management = true }) { Text("ストレージと更新") }
                TextButton(onClick = importOpml, enabled = !state.opmlBusy) { Text("OPML をインポート") }
                TextButton(
                    onClick = exportOpml,
                    enabled = !state.opmlBusy && state.library.podcasts.isNotEmpty(),
                ) {
                    Text("OPML をエクスポート")
                }
                if (state.opmlBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (speed) PlaybackSpeedSheet(state, null, action = action, dismiss = { speed = false })
    }
}

@Composable
private fun AutoDownloadSettingsPage(
    state: PodcastUiState,
    action: (PodcastAction) -> Unit,
    back: () -> Unit,
    snackbar: SnackbarHostState,
): Unit {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自動ダウンロード") },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(AppIcons.ArrowBack, "設定に戻る") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSwitch("Wi-Fi のみ", state.wifiOnly) { action(PodcastAction.WifiOnly(it)) }
            SettingsSwitch("充電中のみ開始", state.autoDownloadChargingOnly) {
                action(PodcastAction.AutoDownloadChargingOnly(it))
            }
            SettingsSwitch("空き容量が十分なときのみ", state.autoDownloadRequireStorage) {
                action(PodcastAction.AutoDownloadRequireStorage(it))
            }
            Text("番組ごとの新着保存数", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                autoDownloadLimits.forEach { limit ->
                    FilterChip(
                        selected = state.autoDownloadLimit == limit,
                        onClick = { action(PodcastAction.AutoDownloadLimit(limit)) },
                        label = { Text(if (limit == Int.MAX_VALUE) "上限なし" else "${limit}件") },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Unit {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, null)
    }
}
