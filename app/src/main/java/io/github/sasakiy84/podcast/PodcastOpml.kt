package io.github.sasakiy84.podcast

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream
import java.io.OutputStream

/** OPML contains subscriptions, independently of this app's queues and playback data. */
data class OpmlSubscriptions(val podcasts: List<Podcast>, val skipped: Int)
data class OpmlImportResult(val added: Int, val skipped: Int)

object PodcastOpml {
    private const val MAX_BYTES = 5 * 1024 * 1024

    fun read(input: InputStream): OpmlSubscriptions {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw PodcastException("OPML ファイルが大きすぎます（最大5MB）。")
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(bytes.inputStream(), null)
            var root = false
            var closedRoot = false
            var body = false
            var insideBody = false
            var skipped = 0
            val podcasts = linkedMapOf<String, Podcast>()
            while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.DOCDECL || parser.depth > 64) throw PodcastException("OPML ファイルを読み取れませんでした。")
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (parser.depth == 1) {
                        if (root || parser.name != "opml") throw PodcastException("OPML ファイルを選択してください。")
                        root = true
                    } else if (parser.depth == 2 && parser.name == "body") {
                        if (body) throw PodcastException("OPML ファイルを読み取れませんでした。")
                        body = true
                        insideBody = true
                    } else if (insideBody && parser.name == "outline") {
                        val raw = parser.attribute("xmlUrl") ?: continue
                        val url = try {
                            val uri = java.net.URI(raw.trim())
                            if (uri.scheme?.lowercase(java.util.Locale.ROOT) in listOf("http", "https")) podcastUrl(raw).toASCIIString() else null
                        } catch (_: java.io.IOException) { null } catch (_: java.net.URISyntaxException) { null }
                        if (url == null || raw.isBlank() || url in podcasts) {
                            skipped++
                        } else {
                            val title = parser.attribute("title")?.trim()?.takeIf(String::isNotEmpty)
                                ?: parser.attribute("text")?.trim()?.takeIf(String::isNotEmpty)
                                ?: podcastUrl(url).host
                            podcasts[url] = Podcast(url, title, website = parser.attribute("htmlUrl")?.let { absoluteUrl(url, it) }.orEmpty())
                        }
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG) {
                    if (parser.depth == 2 && parser.name == "body") insideBody = false
                    if (parser.depth == 1 && parser.name == "opml") closedRoot = true
                }
            }
            if (!root || !body || !closedRoot) throw PodcastException("OPML ファイルを選択してください。")
            return OpmlSubscriptions(podcasts.values.toList(), skipped)
        } catch (_: XmlPullParserException) {
            throw PodcastException("OPML ファイルを読み取れませんでした。")
        }
    }

    fun write(podcasts: List<Podcast>, output: OutputStream): Unit {
        val xml = Xml.newSerializer()
        xml.setOutput(output, "UTF-8")
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "opml").attribute(null, "version", "2.0")
        xml.startTag(null, "head").startTag(null, "title").text("Podcast Android").endTag(null, "title").endTag(null, "head")
        xml.startTag(null, "body")
        for (podcast in podcasts.distinctBy { it.feedUrl }) {
            xml.startTag(null, "outline")
                .attribute(null, "type", "rss")
                .attribute(null, "text", podcast.title)
                .attribute(null, "title", podcast.title)
                .attribute(null, "xmlUrl", podcast.feedUrl)
            if (podcast.website.isNotBlank()) xml.attribute(null, "htmlUrl", podcast.website)
            xml.endTag(null, "outline")
        }
        xml.endTag(null, "body").endTag(null, "opml")
        xml.endDocument()
        xml.flush()
    }

    private fun XmlPullParser.attribute(name: String): String? = (0 until attributeCount)
        .firstOrNull { getAttributeNamespace(it).isNullOrEmpty() && getAttributeName(it).equals(name, ignoreCase = true) }
        ?.let(::getAttributeValue)
}
