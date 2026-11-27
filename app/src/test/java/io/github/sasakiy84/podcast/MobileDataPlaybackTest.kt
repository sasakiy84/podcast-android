package io.github.sasakiy84.podcast

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MobileDataPlaybackTest {
    private val app get() = RuntimeEnvironment.getApplication() as PodcastApplication

    @Test
    fun warningDefaultsOnAndOnlyAppliesToRemoteAudioOnCellular(): Unit {
        val remote = Uri.parse("https://example.org/audio.mp3")
        assertTrue(app.repository.settings.warnOnMobilePlayback)
        setNetworkTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertTrue(app.mobileDataPlayback.requiresConfirmation(remote))
        assertFalse(app.mobileDataPlayback.requiresConfirmation(Uri.parse("file:///saved.audio")))

        setNetworkTransport(NetworkCapabilities.TRANSPORT_WIFI)
        assertFalse(app.mobileDataPlayback.requiresConfirmation(remote))

        app.repository.settings.setWarnOnMobilePlayback(false)
        setNetworkTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(app.mobileDataPlayback.requiresConfirmation(remote))
    }

    @Test
    fun serviceWaitsForApprovalBeforeOpeningAudioAndAsksAgainForTheNextEpisode(): Unit {
        setNetworkTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val paths = ConcurrentLinkedQueue<String>()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    val requestLine = socket.getInputStream().bufferedReader().readLine()
                    paths.add(requestLine.substringAfter(' ').substringBefore(' '))
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val warning = app.mobileDataPlayback
            val player = ReflectionHelpers.getField<MediaSession>(service.get(), "session").player
            fun media(id: String): MediaItem = MediaItem.Builder().setMediaId(id)
                .setUri("http://127.0.0.1:${server.localPort}/$id.mp3")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build()).build()
            val first = media("first")
            val second = media("second")
            player.setMediaItems(listOf(first, second))
            player.prepare()
            player.play()
            await("最初の回を取得する前に確認する") { warning.request.value?.episodeId == first.mediaId }
            assertFalse(player.playWhenReady)
            assertTrue(paths.isEmpty())

            val cancelled = requireNotNull(warning.request.value)
            assertTrue(warning.respond(cancelled.id, allow = false))
            assertTrue(warning.requiresConfirmation(requireNotNull(first.localConfiguration).uri))
            player.prepare()
            player.play()
            await("再生し直したときに再度確認する") { warning.request.value != null }
            val approved = requireNotNull(warning.request.value)
            assertFalse(warning.respond(cancelled.id, allow = true))
            assertTrue(warning.respond(approved.id, allow = true))
            player.prepare()
            player.play()
            await("許可した回の通信を開く") { "/first.mp3" in paths }

            player.seekToNextMediaItem()
            player.prepare()
            player.play()
            await("次の回を取得する前に確認する") { warning.request.value?.episodeId == second.mediaId }
            assertFalse(player.playWhenReady)
            assertFalse("/second.mp3" in paths)
        } finally {
            service.destroy()
            server.close()
            executor.shutdownNow()
        }
    }

    private fun setNetworkTransport(transport: Int): Unit {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        shadowOf(connectivity).setNetworkCapabilities(requireNotNull(connectivity.activeNetwork), capabilities)
    }

    private fun await(message: String, condition: () -> Boolean): Unit {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }
}
