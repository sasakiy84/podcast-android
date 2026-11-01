package io.github.sasakiy84.podcast

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PodcastDirectoryTest {
    private val rss = "<rss><channel><title>Morning</title><item><guid>1</guid><title>Episode</title><enclosure url='one.mp3'/></item></channel></rss>"
    private val apple = """{"results":[{"collectionName":"Morning","artistName":"Host","feedUrl":"https://example.org/feed"}]}"""

    @Test
    fun appleEpisodeSharedUrlResolvesItsParentShow(): Unit {
        val directory = PodcastDirectory { url ->
            check(url == "https://itunes.apple.com/lookup?id=123456&entity=podcast")
            WebResponse(url, apple, "application/json")
        }
        val result = directory.resolve("https://podcasts.apple.com/jp/podcast/morning/id123456?i=9999")
        assertEquals("https://example.org/feed", result.single().podcast.feedUrl)
    }

    @Test
    fun spotifyShowPageFindsCandidatesThatRequireConfirmation(): Unit {
        val directory = PodcastDirectory { url ->
            when {
                url.startsWith("https://open.spotify.com/show/") -> WebResponse(url, """<html><head><meta property="og:title" content="Morning | Podcast on Spotify"/></head><body>Latest episode has a different title</body></html>""", "text/html")
                url.contains("term=Morning") -> WebResponse(url, apple, "application/json")
                else -> error("Unexpected public endpoint: $url")
            }
        }
        val result = directory.resolve("https://open.spotify.com/show/abc123")
        assertEquals("Morning", result.single().podcast.title)
        assertTrue(result.single().source.contains("Spotify の番組候補"))
    }

    @Test
    fun spotifyEpisodeMetadataResolvesItsParentSeries(): Unit {
        val directory = PodcastDirectory { url ->
            if (url.contains("itunes.apple.com")) WebResponse(url, apple, "application/json")
            else WebResponse(url, """<html><head><meta property="og:title" content="An unrelated episode title"/>
                <script type="application/ld+json">{"@type":"PodcastEpisode","partOfSeries":{"@type":"PodcastSeries","name":"Morning"}}</script></head></html>""", "text/html")
        }
        assertEquals("Morning", directory.resolve("https://open.spotify.com/episode/abc123").single().podcast.title)
    }

    @Test
    fun sharedPlatformTitlesResolveConfirmedDirectoryCandidates(): Unit {
        for ((url, title, platform) in listOf(
            Triple("https://music.amazon.co.jp/podcasts/uuid", "Morning - Podcast on Amazon Music", "Amazon Music"),
            Triple("https://pca.st/abc", "Morning - Pocket Casts", "Pocket Casts"),
        )) {
            val directory = PodcastDirectory { endpoint ->
                if (endpoint.contains("itunes.apple.com")) {
                    check(endpoint.contains("term=Morning"))
                    WebResponse(endpoint, apple, "application/json")
                } else WebResponse(endpoint, """<html><head><meta property="og:title" content="$title"></head></html>""", "text/html")
            }
            assertTrue(directory.resolve(url).single().source.contains("$platform の番組候補"))
        }
    }

    @Test
    fun sharedPageWithAlternateRssUsesThatExactFeed(): Unit {
        val directory = PodcastDirectory { url ->
            if (url.endsWith("feed.xml")) WebResponse(url, rss, "application/rss+xml")
            else WebResponse(url, "<html><head><link rel='alternate' type='application/rss+xml' href='/feed.xml'/></head></html>", "text/html")
        }
        val result = directory.resolve("https://pca.st/abc")
        assertEquals("https://pca.st/feed.xml", result.single().podcast.feedUrl)
        assertEquals("RSS", result.single().source)
    }

    @Test
    fun directRssWithLeadingCommentsIsRecognized(): Unit {
        val directory = PodcastDirectory { WebResponse(it, "<!-- publisher comment -->\n$rss", "text/xml") }
        assertEquals("RSS", directory.resolve("https://example.org/feed").single().source)
    }

    @Test
    fun temporaryRedirectDoesNotBecomeTheStoredSubscriptionAddress(): Unit {
        val directory = PodcastDirectory { WebResponse("https://cdn.example.org/temporary/feed?token=temporary", rss, "application/rss+xml") }
        val result = directory.fetchFeed("https://example.org/stable-feed")
        assertEquals("https://example.org/stable-feed", result.podcast.feedUrl)
        assertEquals("https://cdn.example.org/temporary/one.mp3", result.episodes.single().audioUrl)
    }

    @Test(expected = PodcastException::class)
    fun unrecognizedHtmlPageDoesNotRegisterAnArbitraryPodcast(): Unit {
        PodcastDirectory { WebResponse(it, "<html><title>Not a feed</title></html>", "text/html") }.resolve("https://open.spotify.com.evil.example/page")
    }

    @Test(expected = PodcastException::class)
    fun platformExclusiveWithoutPublicRssExplainsTheMissingFeed(): Unit {
        PodcastDirectory { url -> if (url.contains("open.spotify.com")) WebResponse(url, "<html><title>Exclusive - Spotify</title></html>", "text/html") else WebResponse(url, """{"results":[]}""", "application/json") }
            .resolve("https://open.spotify.com/show/exclusive")
    }
    @Test
    fun appleDirectoryDiscardsEntriesWithoutPublicRssAndDeduplicates(): Unit {
        val body = """{"results":[{"collectionName":"Show","artistName":"Host","feedUrl":"https://example.org/feed"},
            {"collectionName":"Show","feedUrl":"https://example.org/feed"},{"collectionName":"Exclusive"},
            {"collectionName":"Invalid","feedUrl":"file:///etc/passwd"}]}"""
        val result = PodcastDirectory.appleResults(body)
        assertEquals(1, result.size)
        assertEquals("Host", result.single().podcast.author)
    }

}
