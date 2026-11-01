package io.github.sasakiy84.podcast

import android.graphics.Bitmap
import android.os.Looper
import coil.decode.DataSource
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkCacheTest {
    @Test fun artworkRemainsAvailableOfflineEvenWhenTheServerSendsNoStore(): Unit {
        val context = (RuntimeEnvironment.getApplication() as PodcastApplication)
        val loader = context.newImageLoader()
        val bytes = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val network = Executors.newSingleThreadExecutor()
        val requests = Executors.newSingleThreadExecutor()
        val response = network.submit {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
        }
        fun execute(request: ImageRequest): ImageResult {
            val future = requests.submit<ImageResult> { kotlinx.coroutines.runBlocking { loader.execute(request) } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!future.isDone && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            return future.get(1, TimeUnit.SECONDS)
        }
        try {
            val url = "http://127.0.0.1:${server.localPort}/art.png"
            val first = execute(ImageRequest.Builder(context).data(url).size(32).allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).build())
            assertTrue(first.toString(), first is SuccessResult)
            response.get(2, TimeUnit.SECONDS)
            server.close()
            loader.memoryCache?.clear()
            val offline = execute(ImageRequest.Builder(context).data(url).size(32).allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).networkCachePolicy(CachePolicy.DISABLED).build())
            assertTrue(offline.toString(), offline is SuccessResult)
            assertEquals(DataSource.DISK, (offline as SuccessResult).dataSource)
        } finally { server.close(); loader.shutdown(); network.shutdownNow(); requests.shutdownNow() }
    }
}
