package io.github.sasakiy84.podcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private var openRequest by androidx.compose.runtime.mutableIntStateOf(0)
    private var pendingNotifications: PodcastAction.SetPodcastOptions? = null
    private var openedFeed by androidx.compose.runtime.mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingNotifications?.let { if (granted) model.dispatch(it) }
        pendingNotifications = null
    }
    private fun dispatch(action: PodcastAction): Unit {
        if (action is PodcastAction.SetPodcastOptions && action.options.notifications && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingNotifications = action
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else model.dispatch(action)
    }
    override fun onNewIntent(intent: android.content.Intent): Unit { super.onNewIntent(intent); setIntent(intent); openedFeed = intent.getStringExtra("feed_url"); openRequest++ }
    private val model: PodcastsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        openedFeed = if (savedInstanceState == null) intent.getStringExtra("feed_url") else null
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val state by model.state.collectAsState()
            PodcastApp(state, ::dispatch, onSettings = { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }, initialFeedUrl = openedFeed, openFeedRequest = openRequest)
        }
    }
}
