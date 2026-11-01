package io.github.sasakiy84.podcast

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PodcastMigrationTest {
    @Test
    fun releasedDatabaseVersionsPreserveLocalDataOnUpgrade(): Unit {
        val context = RuntimeEnvironment.getApplication()
        for (version in 1..3) {
            val name = "migration-v$version.db"
            context.deleteDatabase(name)
            val sql =
                requireNotNull(javaClass.getResourceAsStream("/migrations/v$version.sql"))
                    .bufferedReader()
                    .use { it.readText() }
                    .lineSequence()
                    .filterNot { it.startsWith("--") }
                    .joinToString("\n")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
                sql.split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(database::execSQL)
                database.version = version
            }
            try {
                PodcastStore(context, name).use { store ->
                    val library = store.library()
                    val source = "schema $version"
                    assertEquals(source, "番組", library.podcasts.single().title)
                    assertEquals(source, "本文", store.episode("one")?.description)
                    assertEquals(source, "", library.podcasts.single().website)
                    assertEquals(source, 500L, store.progress("one").positionMs)
                    assertEquals(source, 42L, store.downloadId("one"))
                    assertEquals(source, listOf("one"), library.historyIds)
                    assertTrue(source, store.episode("one")!!.chapters.isEmpty())
                    if (version >= 2) {
                        assertEquals(source, listOf("one"), library.queues.single().episodeIds)
                        assertEquals(
                            source,
                            setOf("https://example.org/rss"),
                            library.autoDownloadFeeds,
                        )
                        assertEquals(source, "キーワード", library.queueRules.single().includeWords)
                    } else {
                        store.saveQueue("news", "ニュース")
                        assertTrue(source, store.addToQueue("news", "one"))
                    }
                    assertEquals(
                        source,
                        if (version >= 3) setOf("one") else emptySet<String>(),
                        library.favorites,
                    )
                    store.setFavorite("one", true)
                    assertEquals(source, setOf("one"), store.library().favorites)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }
}
