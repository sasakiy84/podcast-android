package io.github.sasakiy84.podcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val model: PodcastsViewModel by viewModels()
    private val importOpml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importOpml) }
    private val exportOpml = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml+xml")) { uri -> uri?.let(model::exportOpml) }

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val state by model.state.collectAsState()
            PodcastApp(state, model::dispatch,
                onImportOpml = { importOpml.launch(arrayOf("*/*")) },
                onExportOpml = { exportOpml.launch("podcasts.opml") })
        }
    }
}
