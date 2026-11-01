package io.github.sasakiy84.podcast

import java.net.HttpURLConnection
import java.net.URI

data class WebResponse(val url: String, val body: String, val contentType: String)

/** Public endpoints only; subscription and listening data never leave the device. */
class PodcastHttp {
    fun get(input: String): WebResponse =
        response(input) { connection, finalUrl ->
            decoded(connection).use { raw ->
                val bytes = raw.readNBytes(8 * 1024 * 1024 + 1)
                if (bytes.size > 8 * 1024 * 1024) throw PodcastException("配信元の応答が大きすぎます。")
                val type = connection.contentType.orEmpty()
                val encoding = charset(type, bytes.take(256).toByteArray())
                WebResponse(
                    finalUrl.toASCIIString(),
                    bytes.toString(encoding).removePrefix("\uFEFF"),
                    type,
                )
            }
        }

    fun fetchFeed(input: String): PodcastFeed =
        response(input) { connection, finalUrl ->
            decoded(connection).use { raw ->
                val limited = LimitedFeedStream(raw).buffered()
                limited.mark(512)
                val prefix = limited.readNBytes(256)
                limited.reset()
                val reader =
                    java.io.PushbackReader(
                        java.io.InputStreamReader(
                            limited,
                            charset(connection.contentType.orEmpty(), prefix),
                        ),
                        1,
                    )
                val first = reader.read()
                if (first != -1 && first != 0xFEFF) reader.unread(first)
                FeedParser.parse(
                    reader,
                    podcastUrl(input).toASCIIString(),
                    finalUrl.toASCIIString(),
                )
            }
        }

    private fun decoded(connection: HttpURLConnection): java.io.InputStream {
        val input = connection.inputStream
        return if (connection.contentEncoding.orEmpty().contains("gzip", true))
            java.util.zip.GZIPInputStream(input)
        else input
    }

    private fun charset(type: String, prefix: ByteArray): java.nio.charset.Charset {
        val encoding =
            Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(type)
                ?.groupValues
                ?.get(1)
                ?.trim('"', '\'')
                ?: Regex("encoding=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                    .find(prefix.toString(Charsets.ISO_8859_1))
                    ?.groupValues
                    ?.get(1)
        return try {
            encoding?.let(java.nio.charset.Charset::forName)
                ?: if (
                    prefix.take(2) in
                        listOf(
                            listOf(0xFF.toByte(), 0xFE.toByte()),
                            listOf(0xFE.toByte(), 0xFF.toByte()),
                        )
                )
                    Charsets.UTF_16
                else Charsets.UTF_8
        } catch (_: IllegalArgumentException) {
            throw PodcastException("配信元の文字コードに対応していません。")
        }
    }

    private fun <T> response(input: String, read: (HttpURLConnection, URI) -> T): T {
        var uri = podcastUrl(input)
        repeat(8) {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty(
                    "User-Agent",
                    "PodcastAndroid/0.5 (+https://github.com/sasakiy84/podcast-android)",
                )
                connection.setRequestProperty(
                    "Accept",
                    "application/rss+xml, application/atom+xml, application/json, text/html, */*",
                )
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location =
                        connection.getHeaderField("Location")
                            ?: throw PodcastException("転送先のURLを取得できません。")
                    uri = podcastUrl(uri.resolve(location).toString())
                } else {
                    if (code !in 200..299) throw PodcastException("配信元に接続できませんでした（HTTP $code）。")
                    return read(connection, uri)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw PodcastException("URL の転送が多すぎます。公式の番組URLを入力してください。")
    }

    private class LimitedFeedStream(private val input: java.io.InputStream) :
        java.io.InputStream() {
        private var count = 0L

        private fun count(size: Int): Int {
            if (size > 0) count += size
            if (count > 64L * 1024 * 1024) throw PodcastException("フィードが大きすぎるため読み取れません（最大64MB）。")
            return size
        }

        override fun read(): Int {
            val value = input.read()
            count(if (value < 0) 0 else 1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            count(input.read(buffer, offset, length))

        override fun close(): Unit = input.close()
    }
}
