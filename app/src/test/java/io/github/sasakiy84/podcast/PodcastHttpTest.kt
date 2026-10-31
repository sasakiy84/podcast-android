package io.github.sasakiy84.podcast

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

class PodcastHttpTest {
    @Test
    fun redirectsGzipAndDeclaredCharsetAreHandledTogether(): Unit {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write("<?xml version='1.0' encoding='ISO-8859-1'?><rss><channel><title>Café</title></channel></rss>".toByteArray(Charsets.ISO_8859_1)) }
        fixture(listOf(
            Response("302 Found", mapOf("Location" to "/rss")),
            Response("200 OK", mapOf("Content-Type" to "application/rss+xml; charset=ISO-8859-1", "Content-Encoding" to "gzip"), output.toByteArray()),
        )) { url ->
            val response = PodcastHttp().get("$url/start")
            assertTrue(response.url.endsWith("/rss"))
            assertTrue(response.body.contains("Café"))
        }
    }

    @Test(expected = PodcastException::class)
    fun redirectsToLocalFileSchemesAreRejected(): Unit {
        fixture(listOf(Response("302 Found", mapOf("Location" to "file:///etc/passwd")))) { PodcastHttp().get(it) }
    }

    private data class Response(val status: String, val headers: Map<String, String>, val body: ByteArray = byteArrayOf())

    // Real loopback HTTP responses exercise redirects and decoding without a live vendor dependency.
    private fun fixture(responses: List<Response>, block: (String) -> Unit): Unit {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            for (response in responses) server.accept().use { client ->
                client.soTimeout = 5000
                val input = client.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                val headers = buildString {
                    append("HTTP/1.1 ${response.status}\r\nContent-Length: ${response.body.size}\r\nConnection: close\r\n")
                    response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("\r\n")
                }
                client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(response.body); flush() }
            }
        }
        try { block("http://127.0.0.1:${server.localPort}") }
        finally { server.close(); executor.shutdownNow(); future.get(5, TimeUnit.SECONDS) }
    }
}
