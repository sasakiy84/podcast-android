package io.github.sasakiy84.podcast

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedParserTest {
    private val feed = "https://example.org/podcast/feed.xml"

    @Test
    fun rssIncludesMetadataAudioDurationAndPublicationDate(): Unit {
        val result = FeedParser.parse("""
            <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" version="2.0"><channel>
            <title>朝のノート</title><itunes:author>配信者</itunes:author><itunes:image href="/cover.jpg" />
            <description><![CDATA[<p>毎日の<strong>発見</strong></p>]]></description>
            <item><guid>one</guid><title>最初の回</title><pubDate>Tue, 14 Nov 2023 22:13:20 GMT</pubDate>
            <enclosure url="/audio/one.mp3" type="audio/mpeg"/><itunes:duration>1:02:03</itunes:duration></item>
            </channel></rss>
        """.trimIndent(), feed)
        assertEquals("朝のノート", result.podcast.title)
        assertEquals("配信者", result.podcast.author)
        assertEquals("毎日の発見", result.podcast.description)
        assertEquals("https://example.org/cover.jpg", result.podcast.artwork)
        assertEquals("https://example.org/audio/one.mp3", result.episodes.single().audioUrl)
        assertEquals(3_723_000, result.episodes.single().durationMs)
        assertEquals(1_700_000_000_000, result.episodes.single().published)
    }

    @Test
    fun xmlCharacterReferencesArePreserved(): Unit {
        val result = FeedParser.parse("<rss><channel><title>News &amp; Culture</title><item><guid>1</guid><title>It&apos;s a test</title><enclosure url='https://example.org/one.mp3'/></item></channel></rss>", feed)
        assertEquals("News & Culture", result.podcast.title)
        assertEquals("It's a test", result.episodes.single().title)
    }

    @Test
    fun atomEnclosuresAndIsoDatesAreSupported(): Unit {
        val result = FeedParser.parse("""
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom show</title><author><name>Author</name></author>
            <entry><id>episode-1</id><title>Episode</title><published>2023-11-14T22:13:20Z</published>
            <summary>Listen now</summary><link rel="enclosure" type="audio/mp4" href="ep.m4a" /></entry></feed>
        """.trimIndent(), feed)
        assertEquals("Author", result.podcast.author)
        assertEquals("https://example.org/podcast/ep.m4a", result.episodes.single().audioUrl)
        assertEquals(1_700_000_000_000, result.episodes.single().published)
    }

    @Test
    fun redirectBaseDoesNotChangeEpisodeIdentity(): Unit {
        val xml = "<rss><channel><title>Show</title><item><guid>same</guid><title>Episode</title><enclosure url='one.mp3'/></item></channel></rss>"
        val original = FeedParser.parse(xml, feed)
        val moved = FeedParser.parse(xml, feed, "https://new.example.org/feed")
        assertEquals(original.episodes.single().id, moved.episodes.single().id)
        assertEquals("https://new.example.org/one.mp3", moved.episodes.single().audioUrl)
    }

    @Test
    fun duplicateGuidsAndNonAudioEntriesDoNotPolluteLibrary(): Unit {
        val result = FeedParser.parse("""<rss><channel><title>Show</title>
            <item><guid>one</guid><enclosure url="https://example.org/a.mp3" type="audio/mpeg"/></item>
            <item><guid>one</guid><enclosure url="https://example.org/a.mp3" type="audio/mpeg"/></item>
            <item><guid>bad</guid><enclosure url="javascript:alert(1)" type="audio/mpeg"/></item>
            <item><guid>image</guid><enclosure url="https://example.org/a.jpg" type="image/jpeg"/></item>
            </channel></rss>""", feed)
        assertEquals(1, result.episodes.size)
    }

    @Test(expected = PodcastException::class)
    fun externalEntityDeclarationsAreRejected(): Unit {
        FeedParser.parse("<!DOCTYPE rss [<!ENTITY file SYSTEM 'file:///etc/passwd'>]><rss><channel><title>&file;</title></channel></rss>", feed)
    }

    @Test(expected = PodcastException::class)
    fun htmlIsNotMistakenForAFeed(): Unit { FeedParser.parse("<html><title>Show</title></html>", feed) }

    @Test
    fun invalidDurationsDoNotCreateNegativeOrInfiniteProgress(): Unit {
        assertEquals(0, FeedParser.parseDuration("-10"))
        assertEquals(0, FeedParser.parseDuration("NaN"))
        assertEquals(0, FeedParser.parseDuration("wrong"))
        assertEquals(62_500, FeedParser.parseDuration("1:02.5"))
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

    @Test
    fun platformTitlesAreCleanedWithoutTrustingSimilarDomains(): Unit {
        assertEquals("朝のノート", PodcastDirectory.cleanPlatformTitle("朝のノート | Podcast on Spotify"))
        assertEquals("朝のノート", PodcastDirectory.cleanPlatformTitle("朝のノート - Pocket Casts"))
        assertEquals("Spotify", PodcastDirectory.platformName("open.spotify.com"))
        assertNull(PodcastDirectory.platformName("open.spotify.com.evil.example"))
    }

    @Test(expected = PodcastException::class)
    fun credentialsAndLocalSchemesCannotBeUsedAsSubscriptionUrls(): Unit { podcastUrl("https://user:pass@example.org/feed") }
}
