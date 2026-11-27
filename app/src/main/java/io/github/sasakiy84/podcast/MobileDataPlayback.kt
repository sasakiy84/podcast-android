package io.github.sasakiy84.podcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MobileDataPlaybackRequest(val id: Long, val episodeId: String, val title: String)

internal class MobileDataPlayback(private val context: Context, private val settings: PlaybackPreferences) {
    private val mutableRequest = MutableStateFlow<MobileDataPlaybackRequest?>(null)
    val request = mutableRequest.asStateFlow()
    private var requestId = 0L
    private var activeEpisodeId: String? = null
    private var activeUri: Uri? = null
    private var pendingUri: Uri? = null
    @Volatile private var permittedUri: Uri? = null

    fun requiresConfirmation(uri: Uri): Boolean {
        if (!settings.warnOnMobilePlayback || uri == permittedUri ||
            (uri.scheme != "https" && uri.scheme != "http")
        ) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    fun onEpisodeChanged(episodeId: String?, uri: Uri?): Unit {
        if (activeEpisodeId == episodeId && activeUri == uri) return
        activeEpisodeId = episodeId
        activeUri = uri
        reset()
    }

    fun ask(episodeId: String, title: String, uri: Uri): Unit {
        pendingUri = uri
        mutableRequest.value = MobileDataPlaybackRequest(++requestId, episodeId, title)
    }

    fun respond(id: Long, allow: Boolean): Boolean {
        if (mutableRequest.value?.id != id) return false
        if (allow) permittedUri = pendingUri
        pendingUri = null
        mutableRequest.value = null
        return true
    }

    fun reset(): Unit {
        permittedUri = null
        pendingUri = null
        mutableRequest.value = null
    }
}

internal class MobileDataPlaybackBlocked(val uri: Uri) : IOException("Mobile playback requires confirmation")
