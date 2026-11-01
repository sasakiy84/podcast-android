package io.github.sasakiy84.podcast

import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup

class PodcastDirectory(private val request: ((String) -> WebResponse)? = null) {
    private val http = PodcastHttp()

    private fun requestPage(url: String): WebResponse = request?.invoke(url) ?: http.get(url)

    fun search(query: String): List<PodcastCandidate> {
        if (query.isBlank()) return emptyList()
        val body =
            requestPage(
                    "https://itunes.apple.com/search?media=podcast&entity=podcast&country=JP&limit=40&term=${encode(query.trim())}"
                )
                .body
        return appleResults(body)
    }

    fun fetchFeed(url: String): PodcastFeed {
        if (request == null) return http.fetchFeed(url)
        val response = requestPage(url)
        return FeedParser.parse(response.body, podcastUrl(url).toASCIIString(), response.url)
    }

    fun fetchFeed(url: String, validators: FeedValidators): FeedFetchResult =
        if (request == null) http.fetchFeed(url, validators)
        else FeedFetchResult(fetchFeed(url), FeedValidators())

    fun resolve(input: String): List<PodcastCandidate> {
        val uri = podcastUrl(input)
        if (
            uri.host.lowercase() in
                listOf("podcasts.apple.com", "itunes.apple.com", "geo.itunes.apple.com")
        ) {
            val id =
                Regex("/id(\\d+)").find(uri.path)?.groupValues?.get(1)
                    ?: throw PodcastException("Apple Podcasts の番組URLを入力してください。")
            val candidates =
                appleResults(
                    requestPage("https://itunes.apple.com/lookup?id=$id&entity=podcast").body
                )
            if (candidates.isEmpty()) throw PodcastException("この番組の公開 RSS を取得できませんでした。")
            return candidates
        }
        val response = requestPage(uri.toASCIIString())
        val body = response.body.trimStart()
        if (
            response.contentType.lowercase().contains("application/rss+xml") ||
                response.contentType.lowercase().contains("application/atom+xml") ||
                Regex("<(?:[A-Za-z0-9_]+:)?(?:rss|feed|rdf)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(body.take(4096))
        ) {
            return listOf(
                PodcastCandidate(
                    FeedParser.parse(body, uri.toASCIIString(), response.url).podcast,
                    "RSS",
                )
            )
        }
        val document = Jsoup.parse(response.body, response.url)
        val feed =
            document
                .select("link[rel=alternate]")
                .firstOrNull {
                    it.attr("type").lowercase() in
                        listOf("application/rss+xml", "application/atom+xml")
                }
                ?.absUrl("href")
                .orEmpty()
        if (feed.isNotBlank()) return listOf(PodcastCandidate(fetchFeed(feed).podcast, "RSS"))
        val host = URI(response.url).host.lowercase()
        val platform =
            platformName(host)
                ?: throw PodcastException(
                    "RSS が見つかりません。番組の公式 RSS、Apple Podcasts、Spotify、Amazon Music、Pocket Casts の番組URLを入力してください。"
                )
        var title =
            document
                .select("script[type=application/ld+json]")
                .firstNotNullOfOrNull { script ->
                    try {
                        seriesName(JSONObject(script.data()))
                    } catch (_: org.json.JSONException) {
                        null
                    }
                }
                .orEmpty()
                .ifBlank {
                    document
                        .selectFirst("meta[property=og:title]")
                        ?.attr("content")
                        .orEmpty()
                        .ifBlank { document.title() }
                }
        title = cleanPlatformTitle(title)
        if (title.isBlank()) throw PodcastException("番組情報を取得できません。番組名で検索するか、公式 RSS を入力してください。")
        return candidatesForTitle(title, platform)
    }

    private fun candidatesForTitle(title: String, platform: String): List<PodcastCandidate> {
        val cleaned = cleanPlatformTitle(title)
        if (cleaned.isBlank()) throw PodcastException("番組名を取得できませんでした。番組名で検索するか公式 RSS を入力してください。")
        val results = search(cleaned).map { it.copy(source = "$platform の番組候補 · Apple Podcasts") }
        if (results.isEmpty())
            throw PodcastException("公開 RSS に対応する番組候補が見つかりません。番組名で検索するか、公式 RSS を入力してください。")
        return results
    }

    companion object {
        private fun seriesName(json: JSONObject, depth: Int = 0): String? {
            if (depth > 12) return null
            if (json.optString("@type") == "PodcastSeries")
                return json.optString("name").takeIf { it.isNotBlank() }
            for (key in listOf("partOfSeries", "isPartOf")) {
                json.optJSONObject(key)?.let { parent ->
                    seriesName(parent, depth + 1)?.let {
                        return it
                    }
                    if (json.optString("@type") == "PodcastEpisode")
                        parent
                            .optString("name")
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                return it
                            }
                }
            }
            val graph = json.optJSONArray("@graph") ?: return null
            for (index in 0 until graph.length()) graph.optJSONObject(index)?.let {
                seriesName(it, depth + 1)?.let { name ->
                    return name
                }
            }
            return null
        }

        fun appleResults(body: String): List<PodcastCandidate> {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            return (0 until results.length())
                .mapNotNull { index ->
                    val item = results.optJSONObject(index) ?: return@mapNotNull null
                    val feed = item.optString("feedUrl")
                    val title = item.optString("collectionName", item.optString("trackName"))
                    if (feed.isBlank() || title.isBlank() || absoluteUrl(feed, feed).isBlank())
                        return@mapNotNull null
                    PodcastCandidate(
                        Podcast(
                            feed,
                            title,
                            item.optString("artistName"),
                            item.optString("artworkUrl600", item.optString("artworkUrl100")),
                        )
                    )
                }
                .distinctBy { it.podcast.feedUrl }
        }

        fun platformName(host: String): String? =
            when (host.lowercase()) {
                "open.spotify.com",
                "spotify.link" -> "Spotify"
                "music.amazon.com",
                "music.amazon.co.jp",
                "music.amazon.co.uk",
                "music.amazon.de",
                "music.amazon.ca",
                "music.amazon.com.au" -> "Amazon Music"
                "pca.st",
                "pocketcasts.com",
                "www.pocketcasts.com",
                "play.pocketcasts.com" -> "Pocket Casts"
                "overcast.fm" -> "Overcast"
                else -> null
            }

        fun cleanPlatformTitle(title: String): String =
            title
                .replace(
                    Regex(
                        "\\s*(?:[|–—-]\\s*)?(?:Podcast on Spotify|Podcast on Amazon Music|on Spotify|on Amazon Music|[|–—-]\\s*Pocket Casts|[|–—-]\\s*Spotify|[|–—-]\\s*Amazon Music)\\s*$",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                .trim()

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
