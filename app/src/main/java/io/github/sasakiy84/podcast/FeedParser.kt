package io.github.sasakiy84.podcast

import android.util.Xml
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Parse RSS and Atom without loading external entities or trusting embedded HTML. */
object FeedParser {
    private val textContainers = setOf("title", "author", "name", "description", "subtitle", "summary", "content", "encoded", "guid", "id", "pubdate", "published", "updated", "duration", "url", "managingeditor")
    fun parse(xml: String, feedUrl: String, baseUrl: String = feedUrl): PodcastFeed = parse(StringReader(xml), feedUrl, baseUrl)

    fun parse(reader: java.io.Reader, feedUrl: String, baseUrl: String = feedUrl): PodcastFeed {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(reader)
            val path = mutableListOf<String>()
            var title = ""
            var author = ""
            var description = ""
            var artwork = ""
            var website = ""
            var validRoot = false
            var closedRoot = false
            var entry: MutableMap<String, String>? = null
            var chapters = mutableListOf<EpisodeChapter>()
            val episodes = mutableListOf<Episode>()
            val texts = mutableListOf<StringBuilder>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL -> throw PodcastException("外部エンティティを含むフィードには対応していません。")
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT)
                        path.add(tag)
                        if (path.size > 64) throw PodcastException("フィードの入れ子が深すぎるため読み取れません。")
                        texts.add(StringBuilder())
                        if (path.size == 1) validRoot = tag in listOf("rss", "feed", "rdf")
                        if (tag == "item" || tag == "entry") { entry = mutableMapOf(); chapters = mutableListOf() }
                        val current = entry
                        if (current != null && tag == "chapter" && parser.namespace == "http://podlove.org/simple-chapters") {
                            val start = parser.getAttributeValue(null, "start").orEmpty()
                            if (start.isNotBlank() && start.split(':').all { it.toDoubleOrNull()?.let { n -> n.isFinite() && n >= 0 } == true }) chapters += EpisodeChapter(parseDuration(start), parser.getAttributeValue(null, "title").orEmpty(), absoluteUrl(baseUrl, parser.getAttributeValue(null, "href").orEmpty()), absoluteUrl(baseUrl, parser.getAttributeValue(null, "image").orEmpty()))
                        }
                        if (current != null && tag == "chapters" && parser.namespace == "https://podcastindex.org/namespace/1.0") current["chaptersUrl"] = absoluteUrl(baseUrl, parser.getAttributeValue(null, "url").orEmpty())
                        if (path.dropLast(1).any { it in listOf("description", "summary", "content", "encoded") }) {
                            texts.last().append("<").append(tag)
                            if (tag == "a") texts.last().append(" href=\"").append(org.jsoup.nodes.Entities.escape(parser.getAttributeValue(null, "href").orEmpty())).append("\"")
                            texts.last().append(">")
                        }
                        if (tag == "link" && current == null && parser.getAttributeValue(null, "rel").orEmpty() in listOf("", "alternate")) {
                            absoluteUrl(baseUrl, parser.getAttributeValue(null, "href").orEmpty()).takeIf { it.isNotBlank() }?.let { website = it }
                        }
                        if (tag == "enclosure" || (tag == "link" && parser.getAttributeValue(null, "rel") == "enclosure")) {
                            val url = parser.getAttributeValue(null, "url") ?: parser.getAttributeValue(null, "href") ?: ""
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            if (type.isBlank() || type.startsWith("audio/") || type == "application/octet-stream") {
                                current?.set("audio", absoluteUrl(baseUrl, url))
                            }
                        }
                        if (tag == "image" || tag == "thumbnail") {
                            val image = absoluteUrl(baseUrl, parser.getAttributeValue(null, "href") ?: parser.getAttributeValue(null, "url").orEmpty())
                            if (image.isNotBlank()) {
                                if (current != null) current["image"] = image else artwork = image
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> texts.lastOrNull()?.append(parser.text)
                    XmlPullParser.ENTITY_REF -> texts.lastOrNull()?.append(parser.text ?: throw PodcastException("フィードに不明な文字参照が含まれています。"))
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT)
                        val value = texts.removeAt(texts.lastIndex).toString().trim()
                        val current = entry
                        if (current != null) {
                            when (tag) {
                                "title", "guid", "id", "pubdate", "published", "updated", "duration" -> if (value.isNotBlank()) current[tag] = value
                                "description", "summary", "content", "encoded" -> if (value.isNotBlank()) {
                                    val priority = when (tag) { "encoded", "content" -> 3; "description" -> 2; else -> 1 }
                                    if (priority >= (current["descriptionPriority"]?.toIntOrNull() ?: 0)) { current["description"] = descriptionText(value, baseUrl); current["descriptionPriority"] = priority.toString() }
                                }
                            }
                            if (tag == "item" || tag == "entry") {
                                val audio = current["audio"].orEmpty()
                                if (audio.isNotBlank()) {
                                    val guid = current["guid"] ?: current["id"] ?: audio
                                    episodes += Episode(
                                        id = stableId("$feedUrl|$guid"), feedUrl = feedUrl,
                                        title = current["title"].orEmpty().ifBlank { "無題のエピソード" }, audioUrl = audio,
                                        description = current["description"].orEmpty(),
                                        published = parseDate(current["pubdate"] ?: current["published"] ?: current["updated"].orEmpty()),
                                        durationMs = parseDuration(current["duration"].orEmpty()), artwork = current["image"].orEmpty(),
                                        chapters = chapters.distinctBy { it.startMs }.sortedBy { it.startMs }, chaptersUrl = current["chaptersUrl"].orEmpty(),
                                    )
                                }
                                entry = null
                            }
                        } else {
                            when {
                                tag == "title" && "image" !in path -> if (value.isNotBlank()) title = value
                                tag in listOf("author", "managingeditor", "name") -> if (value.isNotBlank()) author = plainText(value)
                                tag == "link" && value.isNotBlank() -> website = absoluteUrl(baseUrl, value)
                                tag == "description" || tag == "subtitle" -> if (value.isNotBlank()) description = descriptionText(value, baseUrl)
                                tag == "url" && "image" in path -> artwork = absoluteUrl(baseUrl, value)
                            }
                        }
                        if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                        if (path.isEmpty()) closedRoot = true
                        if (path.any { it in textContainers }) texts.lastOrNull()?.apply {
                            if (path.any { it in listOf("description", "summary", "content", "encoded") }) append(value).apply { if (tag !in listOf("br", "hr", "img", "wbr")) append("</$tag>") } else append(' ').append(value)
                        }
                    }
                }
                event = parser.nextToken()
            }
            if (!validRoot || !closedRoot || title.isBlank()) throw PodcastException("このURLにポッドキャストの RSS / Atom が見つかりません。")
            return PodcastFeed(Podcast(feedUrl, plainText(title), author, artwork, description, website), episodes.distinctBy { it.id }.sortedByDescending { it.published })
        } catch (error: PodcastException) {
            throw error
        } catch (_: IndexOutOfBoundsException) {
            throw PodcastException("フィードを読み取れません。RSS / Atom のURLを確認してください。")
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
            throw PodcastException("フィードを読み取れません。RSS / Atom のURLを確認してください。")
        }
    }

    fun parseDuration(value: String): Long {
        val parts = value.trim().split(':')
        if (parts.size !in 1..3) return 0
        var seconds = 0.0
        for (part in parts) {
            val number = part.toDoubleOrNull() ?: return 0
            if (!number.isFinite() || number < 0) return 0
            seconds = seconds * 60 + number
        }
        return (seconds * 1000).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }

    private fun parseDate(value: String): Long {
        try { return Instant.parse(value).toEpochMilli() } catch (_: java.time.format.DateTimeParseException) { /* RSS uses RFC dates. */ }
        for (formatter in listOf(DateTimeFormatter.RFC_1123_DATE_TIME, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH))) {
            try { return ZonedDateTime.parse(value, formatter).toInstant().toEpochMilli() } catch (_: java.time.format.DateTimeParseException) { /* Try the next feed date format. */ }
        }
        return 0
    }
}

fun plainText(html: String): String = Jsoup.parse(html).text()

fun absoluteUrl(base: String, value: String): String {
    if (value.isBlank()) return ""
    return try {
        val uri = URI(base).resolve(value.trim())
        if (uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) uri.toASCIIString() else ""
    } catch (_: IllegalArgumentException) { "" } catch (_: java.net.URISyntaxException) { "" }
}
