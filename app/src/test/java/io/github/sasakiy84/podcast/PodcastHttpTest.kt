package io.github.sasakiy84.podcast

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class PodcastHttpTest {
    @Test
    fun redirectsGzipAndDeclaredCharsetAreHandledTogether(): Unit {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use {
            it.write(
                "<?xml version='1.0' encoding='ISO-8859-1'?><rss><channel><title>Café</title></channel></rss>"
                    .toByteArray(Charsets.ISO_8859_1)
            )
        }
        fixture(
            listOf(
                Response("302 Found", mapOf("Location" to "/rss")),
                Response(
                    "200 OK",
                    mapOf(
                        "Content-Type" to "application/rss+xml; charset=ISO-8859-1",
                        "Content-Encoding" to "gzip",
                    ),
                    output.toByteArray(),
                ),
            )
        ) { url ->
            val response = PodcastHttp().get("$url/start")
            assertTrue(response.url.endsWith("/rss"))
            assertTrue(response.body.contains("Café"))
        }
    }

    @Test(expected = PodcastException::class)
    fun redirectsToLocalFileSchemesAreRejected(): Unit {
        fixture(listOf(Response("302 Found", mapOf("Location" to "file:///etc/passwd")))) {
            PodcastHttp().get(it)
        }
    }

    @Test
    fun largeFeedsStreamPastTheOldEightMegabyteLimit(): Unit {
        val xml =
            "<rss><channel><title>大きな番組</title><link>/site</link><!--" +
                "x".repeat(9 * 1024 * 1024) +
                "--><item><title>回</title><enclosure url='/audio.mp3'/></item></channel></rss>"
        val bytes =
            ByteArrayOutputStream()
                .apply { GZIPOutputStream(this).use { it.write(xml.toByteArray()) } }
                .toByteArray()
        fixture(
            listOf(
                Response("302 Found", mapOf("Location" to "/rss")),
                Response(
                    "200 OK",
                    mapOf(
                        "Content-Type" to "application/rss+xml; charset=UTF-8",
                        "Content-Encoding" to "gzip",
                    ),
                    bytes,
                ),
            )
        ) { url ->
            val feed = PodcastHttp().fetchFeed("$url/start")
            assertEquals("$url/start", feed.podcast.feedUrl)
            assertEquals("$url/site", feed.podcast.website)
            assertEquals("$url/audio.mp3", feed.episodes.single().audioUrl)
        }
    }

    @Test
    fun conditionalRefreshSurvivesRestartAndFailedResponsesCannotPoisonTheCache(): Unit {
        val requests = mutableListOf<List<String>>()
        fun rss(title: String): ByteArray =
            """<rss><channel><title>番組</title><item><guid>one</guid><title>$title</title><link>/episode</link><description>説明</description><enclosure url='/one.mp3'/></item></channel></rss>"""
                .toByteArray()
        fixture(
            listOf(
                Response(
                    "200 OK",
                    mapOf("ETag" to "\"v1\"", "Last-Modified" to "Wed, 01 Jan 2025 00:00:00 GMT"),
                    rss("元の回"),
                ),
                Response("304 Not Modified", emptyMap()),
                Response("503 Service Unavailable", emptyMap()),
                Response("200 OK", emptyMap(), rss("更新された回")),
                Response(
                    "200 OK",
                    mapOf("ETag" to "\"bad\""),
                    "<html>not a feed</html>".toByteArray(),
                ),
                Response("200 OK", emptyMap(), rss("更新された回")),
            ),
            inspect = { _, headers -> requests += headers },
        ) { url ->
            kotlinx.coroutines.runBlocking {
                val context = org.robolectric.RuntimeEnvironment.getApplication()
                context.deleteDatabase("podcasts.db")
                var repository = PodcastRepository(context)
                val feed = "$url/feed"
                try {
                    repository.store.importSubscriptions(listOf(Podcast(feed, "番組")))
                    assertTrue(repository.refresh().isEmpty())
                    val episode = repository.library.value.episodes.single()
                    assertEquals("$url/episode", episode.webUrl)
                    repository.store.saveProgress(episode.id, ListeningProgress(42, 1000))
                    repository.store.setFavorite(episode.id, true)
                    repository.store.saveQueue("q", "キュー")
                    repository.store.addToQueue("q", episode.id)
                    repository.store.close()
                    repository = PodcastRepository(context)
                    assertTrue(repository.refresh().isEmpty())
                    assertEquals(1, repository.library.value.episodes.size)
                    assertEquals("元の回", repository.library.value.episodes.single().title)
                    assertEquals(listOf(episode.id), repository.store.queueEpisodeIds("q"))
                    assertEquals(42L, repository.store.progress(episode.id).positionMs)
                    val success = repository.library.value.feedUpdates.getValue(feed).updatedMs
                    assertEquals(listOf("番組"), repository.refresh())
                    assertTrue(
                        repository.library.value.feedUpdates.getValue(feed).error.contains("503")
                    )
                    assertEquals(
                        success,
                        repository.library.value.feedUpdates.getValue(feed).updatedMs,
                    )
                    assertEquals("\"v1\"", repository.store.feedValidators(feed).etag)
                    assertTrue(repository.refresh().isEmpty())
                    assertEquals("", repository.store.feedValidators(feed).etag)
                    assertEquals(listOf("番組"), repository.refresh())
                    assertEquals("更新された回", repository.library.value.episodes.single().title)
                    assertEquals("", repository.store.feedValidators(feed).etag)
                    assertTrue(repository.refresh().isEmpty())
                    assertEquals("", repository.library.value.feedUpdates.getValue(feed).error)
                    assertTrue(episode.id in repository.library.value.favorites)
                    fun header(index: Int, name: String) =
                        requests[index]
                            .firstOrNull { it.startsWith("$name:", true) }
                            ?.substringAfter(':')
                            ?.trim()
                    assertNull(header(0, "If-None-Match"))
                    assertEquals("\"v1\"", header(1, "If-None-Match"))
                    assertEquals("Wed, 01 Jan 2025 00:00:00 GMT", header(1, "If-Modified-Since"))
                    assertEquals("\"v1\"", header(3, "If-None-Match"))
                    assertNull(header(4, "If-None-Match"))
                    assertNull(header(5, "If-None-Match"))
                } finally {
                    repository.store.close()
                    context.deleteDatabase("podcasts.db")
                }
            }
        }
    }

    @Test
    fun redirectValidatorsBelongOnlyToTheSavedResourceAndUnsolicited304IsRejected(): Unit {
        val requests = mutableListOf<List<String>>()
        fixture(
            listOf(
                Response("302 Found", mapOf("Location" to "/stable")),
                Response("304 Not Modified", emptyMap()),
                Response("302 Found", mapOf("Location" to "/different")),
                Response(
                    "200 OK",
                    emptyMap(),
                    "<rss><channel><title>別のフィード</title></channel></rss>".toByteArray(),
                ),
                Response("304 Not Modified", emptyMap()),
            ),
            inspect = { _, headers -> requests += headers },
        ) { url ->
            val http = PodcastHttp()
            val cached = FeedValidators("\"v1\"", responseUrl = "$url/stable")
            assertNull(http.fetchFeed("$url/start", cached).feed)
            assertEquals("別のフィード", http.fetchFeed("$url/start", cached).feed!!.podcast.title)
            assertThrows(PodcastException::class.java) { http.fetchFeed("$url/start") }
            assertTrue(requests[1].any { it.startsWith("If-None-Match:", true) })
            listOf(0, 2, 3, 4).forEach { index ->
                assertFalse(requests[index].any { it.startsWith("If-None-Match:", true) })
            }
        }
    }

    private data class Response(
        val status: String,
        val headers: Map<String, String>,
        val body: ByteArray = byteArrayOf(),
    )

    // Real loopback HTTP responses exercise redirects and decoding without a live vendor
    // dependency.
    private fun fixture(
        responses: List<Response>,
        inspect: (Int, List<String>) -> Unit = { _, _ -> },
        block: (String) -> Unit,
    ): Unit {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val future =
            executor.submit {
                for ((index, response) in responses.withIndex()) server.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader()
                    val request =
                        generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    inspect(index, request)
                    val headers = buildString {
                        append(
                            "HTTP/1.1 ${response.status}\r\nContent-Length: ${response.body.size}\r\nConnection: close\r\n"
                        )
                        response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                        append("\r\n")
                    }
                    client.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII))
                        write(response.body)
                        flush()
                    }
                }
            }
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            executor.shutdownNow()
            future.get(5, TimeUnit.SECONDS)
        }
    }
}
