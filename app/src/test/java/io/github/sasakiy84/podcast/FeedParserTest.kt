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
        val result =
            FeedParser.parse(
                """
                <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" version="2.0"><channel>
                <title>朝のノート</title><itunes:author>配信者 &amp; 編集</itunes:author><itunes:image href="/cover.jpg" />
                <link>https://example.org/site</link><description><![CDATA[<p>毎日の<strong>発見</strong></p>]]></description>
                <item><guid>one</guid><title>最初の回</title><pubDate>Tue, 14 Nov 2023 22:13:20 GMT</pubDate>
                <enclosure url="/audio/one.mp3" type="audio/mpeg"/><itunes:duration>1:02:03</itunes:duration></item>
                </channel></rss>
                """
                    .trimIndent(),
                feed,
            )
        assertEquals("朝のノート", result.podcast.title)
        assertEquals("https://example.org/site", result.podcast.website)
        assertEquals("配信者 & 編集", result.podcast.author)
        assertEquals("毎日の発見", result.podcast.description)
        assertEquals("https://example.org/cover.jpg", result.podcast.artwork)
        assertEquals("https://example.org/audio/one.mp3", result.episodes.single().audioUrl)
        assertEquals(3_723_000, result.episodes.single().durationMs)
        assertEquals(1_700_000_000_000, result.episodes.single().published)
    }

    @Test
    fun atomEnclosuresAndIsoDatesAreSupported(): Unit {
        val result =
            FeedParser.parse(
                """
                <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom show</title><author><name>Author</name></author>
                <entry><id>episode-1</id><title>Episode</title><published>2023-11-14T22:13:20Z</published>
                <summary>Listen now</summary><link rel="alternate" href="notes/episode"/><link rel="enclosure" type="audio/mp4" href="ep.m4a" /></entry></feed>
                """
                    .trimIndent(),
                feed,
            )
        assertEquals("Author", result.podcast.author)
        assertEquals("https://example.org/podcast/notes/episode", result.episodes.single().webUrl)
        assertEquals("https://example.org/podcast/ep.m4a", result.episodes.single().audioUrl)
        assertEquals(1_700_000_000_000, result.episodes.single().published)
    }

    @Test
    fun redirectBaseDoesNotChangeEpisodeIdentity(): Unit {
        val xml =
            "<rss><channel><title>Show</title><item><guid>https://example.org/episodes/one</guid><title>Episode</title><enclosure url='one.mp3'/></item></channel></rss>"
        val original = FeedParser.parse(xml, feed)
        val moved = FeedParser.parse(xml, feed, "https://new.example.org/feed")
        assertEquals(original.episodes.single().id, moved.episodes.single().id)
        assertEquals("https://example.org/episodes/one", moved.episodes.single().webUrl)
        assertEquals("https://new.example.org/one.mp3", moved.episodes.single().audioUrl)
    }

    @Test
    fun duplicateGuidsAndNonAudioEntriesDoNotPolluteLibrary(): Unit {
        val result =
            FeedParser.parse(
                """<rss><channel><title>Show</title>
            <item><guid>one</guid><enclosure url="https://example.org/a.mp3" type="audio/mpeg"/></item>
            <item><guid>one</guid><enclosure url="https://example.org/a.mp3" type="audio/mpeg"/></item>
            <item><guid>bad</guid><enclosure url="javascript:alert(1)" type="audio/mpeg"/></item>
            <item><guid>image</guid><enclosure url="https://example.org/a.jpg" type="image/jpeg"/></item>
            </channel></rss>""",
                feed,
            )
        assertEquals(1, result.episodes.size)
    }

    @Test(expected = PodcastException::class)
    fun externalEntityDeclarationsAreRejected(): Unit {
        FeedParser.parse(
            "<!DOCTYPE rss [<!ENTITY file SYSTEM 'file:///etc/passwd'>]><rss><channel><title>&file;</title></channel></rss>",
            feed,
        )
    }

    @Test(expected = PodcastException::class)
    fun htmlIsNotMistakenForAFeed(): Unit {
        FeedParser.parse("<html><title>Show</title></html>", feed)
    }

    @Test
    fun invalidDurationsDoNotCreateNegativeOrInfiniteProgress(): Unit {
        assertEquals(0, FeedParser.parseDuration("-10"))
        assertEquals(0, FeedParser.parseDuration("NaN"))
        assertEquals(62_500, FeedParser.parseDuration("1:02.5"))
    }

    @Test(expected = PodcastException::class)
    fun credentialsAndLocalSchemesCannotBeUsedAsSubscriptionUrls(): Unit {
        podcastUrl("https://user:pass@example.org/feed")
    }
}
