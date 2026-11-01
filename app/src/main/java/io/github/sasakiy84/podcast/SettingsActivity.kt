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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

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
        setContent {
            val state by model.state.collectAsState()
            SettingsPage(
                state,
                model::dispatch,
                { finish() },
                { importOpml.launch(arrayOf("*/*")) },
                { exportOpml.launch("podcasts.opml") },
            )
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
        androidx.activity.compose.BackHandler(enabled = management) { management = false }
        if (management) {
            ManagementPage(state, action, { management = false }, snackbar)
            return@PodcastTheme
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("設定") },
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
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
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            state.wifiOnly,
                            role = Role.Switch,
                            onValueChange = { action(PodcastAction.WifiOnly(it)) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Wi-Fi のみでダウンロード", Modifier.weight(1f))
                    Switch(state.wifiOnly, null)
                }
                Text("更新間隔", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1, 3, 6, 12, 24).forEach { hours ->
                        FilterChip(
                            selected = state.refreshHours == hours,
                            onClick = { action(PodcastAction.RefreshInterval(hours)) },
                            label = { Text("${hours}h") },
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
