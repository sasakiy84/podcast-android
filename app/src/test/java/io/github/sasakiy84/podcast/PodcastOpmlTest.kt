package io.github.sasakiy84.podcast

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PodcastOpmlTest {
    private fun read(xml: String): OpmlSubscriptions = PodcastOpml.read(xml.byteInputStream())

    @Test fun importsNestedFoldersAndSkipsRepeatedFeeds(): Unit {
        val result = read("""<?xml version="1.0"?><opml version="1.0"><head><title>番組</title></head><body>
          <outline text="ニュース"><outline text="朝 &amp; 夜" type="rss" xmlUrl="https://example.org/feed?a=1&amp;b=2"/>
            <outline text="技術"><outline title="技術の話" xmlUrl="https://example.org/tech"/></outline></outline>
          <outline text="重複" xmlUrl="https://example.org/tech"/>
          <outline text="サイト" type="link" url="https://example.org/"/>
        </body></opml>""")
        assertEquals(listOf("朝 & 夜", "技術の話"), result.podcasts.map { it.title })
        assertEquals("https://example.org/feed?a=1&b=2", result.podcasts.first().feedUrl)
        assertEquals(1, result.skipped)
    }

    @Test fun rejectsUnsupportedAndRelativeUrlsButKeepsOtherSubscriptions(): Unit {
        val result = read("""<opml><body>
          <outline xmlUrl="file:///tmp/secret"/><outline xmlUrl="javascript:alert(1)"/>
          <outline xmlUrl="not-a-url"/><outline xmlUrl="https://"/><outline xmlUrl=""/>
          <outline xmlUrl="https://user:password@example.org/rss"/>
          <outline xmlurl=" https://example.org/rss "/>
        </body></opml>""")
        assertEquals(listOf(Podcast("https://example.org/rss", "example.org")), result.podcasts)
        assertEquals(6, result.skipped)
    }

    @Test fun exportedUtf8SubscriptionsRoundTripWithXmlEscaping(): Unit {
        val shows = listOf(Podcast("https://example.org/日本語?a=1&b=2", "日本語 & <朝> \"の話\""), Podcast("https://example.org/tech", "Tech 🎧"))
        val output = ByteArrayOutputStream()
        PodcastOpml.write(shows, output)
        val encoded = output.toString("UTF-8")
        assertTrue(encoded.contains("version=\"2.0\""))
        assertTrue(encoded.contains("&amp;"))
        assertTrue(encoded.contains("type=\"rss\""))
        val parsed = PodcastOpml.read(output.toByteArray().inputStream())
        assertEquals(shows.map { it.title }, parsed.podcasts.map { it.title })
        assertEquals(shows.map { podcastUrl(it.feedUrl).toASCIIString() }, parsed.podcasts.map { it.feedUrl })
    }

    @Test fun recognizesUtf16ByteOrderAndDeclaredEncoding(): Unit {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><opml><body><outline text="日本語" xmlUrl="https://example.org/rss"/></body></opml>"""
        val result = PodcastOpml.read(xml.toByteArray(Charsets.UTF_16).inputStream())
        assertEquals("日本語", result.podcasts.single().title)
    }

    @Test fun rejectsMalformedXmlAndOtherDocumentTypes(): Unit {
        for (xml in listOf("", "<rss/>", "<opml/>", "<opml><body><outline xmlUrl='https://example.org/rss'/>", "<opml><body/></opml><opml><body/></opml>")) {
            assertThrows(xml, PodcastException::class.java) { read(xml) }
        }
    }

    @Test fun rejectsDoctypeAndExternalEntities(): Unit {
        assertThrows(PodcastException::class.java) {
            read("""<!DOCTYPE opml [<!ENTITY external SYSTEM "file:///tmp/not-readable-by-opml">]><opml><body><outline text="&external;" xmlUrl="https://example.org/rss"/></body></opml>""")
        }
    }

    @Test fun boundsFileSizeAndXmlDepth(): Unit {
        assertThrows(PodcastException::class.java) { PodcastOpml.read(ByteArray(5 * 1024 * 1024 + 1).inputStream()) }
        assertThrows(PodcastException::class.java) { read("<opml><body>" + "<outline>".repeat(70) + "</outline>".repeat(70) + "</body></opml>") }
    }

    @Test fun emptyLibraryExportsAValidOpmlDocument(): Unit {
        val output = ByteArrayOutputStream()
        PodcastOpml.write(emptyList(), output)
        assertTrue(PodcastOpml.read(output.toByteArray().inputStream()).podcasts.isEmpty())
    }

    @Test fun importingAddsSubscriptionsWithoutOverwritingExistingData(): Unit {
        val context = RuntimeEnvironment.getApplication()
        val db = "opml-import-test.db"
        context.deleteDatabase(db)
        PodcastStore(context, db).use { store ->
            val show = Podcast("https://example.org/rss", "元の番組名", "配信者", "https://example.org/art.jpg")
            val episode = Episode("one", show.feedUrl, "最初の回", "https://example.org/one.mp3")
            store.saveFeed(PodcastFeed(show, listOf(episode)))
            store.saveProgress(episode.id, ListeningProgress(123, 600))
            store.saveDownload(episode.id, 42)
            store.saveQueue("news", "ニュース")
            store.addToQueue("news", episode.id)
            store.setAutoDownload(show.feedUrl, true)
            val newShow = Podcast("https://example.org/new", "追加する番組")
            val incoming = listOf(show.copy(title = "OPML の番組名", author = ""), newShow, newShow)
            assertEquals(listOf(newShow), store.importSubscriptions(incoming))
            assertTrue(store.importSubscriptions(incoming).isEmpty())
            val library = store.library()
            assertEquals(show, library.podcasts.first { it.feedUrl == show.feedUrl })
            assertEquals(2, library.podcasts.size)
            assertEquals(episode, library.episodes.single())
            assertEquals(123L, store.progress(episode.id).positionMs)
            assertEquals(42L, store.downloadIds()[episode.id])
            assertEquals(listOf(episode.id), library.queues.single().episodeIds)
            assertEquals(setOf(show.feedUrl), library.autoDownloadFeeds)
            val file = java.io.File.createTempFile("subscriptions", ".opml", context.cacheDir)
            try {
                file.outputStream().use { PodcastOpml.write(library.podcasts, it) }
                val imported = file.inputStream().use(PodcastOpml::read)
                assertEquals(library.podcasts.map { it.feedUrl }.toSet(), imported.podcasts.map { it.feedUrl }.toSet())
            } finally { file.delete() }
        }
        context.deleteDatabase(db)
    }
    @Test fun importsAndExportsThroughDocumentProviderUris(): Unit {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("podcasts.db")
        val repository = PodcastRepository(context)
        val resolver = org.robolectric.Shadows.shadowOf(context.contentResolver)
        val input = android.net.Uri.parse("content://test.documents/subscriptions.opml")
        val output = android.net.Uri.parse("content://test.documents/export.opml")
        val xml = """<opml><body><outline text="朝の番組" xmlUrl="https://example.org/rss"/><outline text="技術" xmlUrl="https://example.org/tech"/><outline xmlUrl="https://example.org/rss"/></body></opml>"""
        try {
            resolver.registerInputStream(input, xml.byteInputStream())
            assertEquals(OpmlImportResult(2, 1), kotlinx.coroutines.runBlocking { repository.importOpml(input) })
            resolver.registerInputStream(input, xml.byteInputStream())
            assertEquals(OpmlImportResult(0, 3), kotlinx.coroutines.runBlocking { repository.importOpml(input) })
            assertEquals(2, repository.library.value.podcasts.size)
            val bytes = ByteArrayOutputStream()
            resolver.registerOutputStream(output, bytes)
            kotlinx.coroutines.runBlocking { repository.exportOpml(output) }
            val restored = PodcastOpml.read(bytes.toByteArray().inputStream())
            assertEquals(setOf("朝の番組", "技術"), restored.podcasts.map { it.title }.toSet())
        } finally { repository.store.close(); context.deleteDatabase("podcasts.db") }
    }

    @Test fun malformedDocumentDoesNotPartiallyImportSubscriptions(): Unit {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("podcasts.db")
        val repository = PodcastRepository(context)
        try {
            repository.store.saveFeed(PodcastFeed(Podcast("https://example.org/existing", "登録済み"), emptyList()))
            val uri = android.net.Uri.parse("content://test.documents/broken.opml")
            org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri,
                """<opml><body><outline text="追加しない" xmlUrl="https://example.org/new"/>""".byteInputStream())
            assertThrows(PodcastException::class.java) { kotlinx.coroutines.runBlocking { repository.importOpml(uri) } }
            assertEquals(listOf("登録済み"), repository.store.library().podcasts.map { it.title })
        } finally { repository.store.close(); context.deleteDatabase("podcasts.db") }
    }

}
