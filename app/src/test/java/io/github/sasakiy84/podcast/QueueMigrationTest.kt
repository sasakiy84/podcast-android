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
class QueueMigrationTest {
    @Test fun versionOneDataSurvivesUpgradeToQueues(): Unit {
        val context = RuntimeEnvironment.getApplication()
        val name = "queue-migration.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE podcasts (feed TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT NOT NULL, description TEXT NOT NULL)")
            db.execSQL("CREATE TABLE episodes (id TEXT PRIMARY KEY, feed TEXT NOT NULL, title TEXT NOT NULL, audio TEXT NOT NULL, description TEXT NOT NULL, published INTEGER NOT NULL, duration INTEGER NOT NULL, artwork TEXT NOT NULL)")
            db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER NOT NULL, completed INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY, manager_id INTEGER NOT NULL)")
            db.execSQL("INSERT INTO podcasts VALUES ('feed','show','author','','')")
            db.execSQL("INSERT INTO episodes VALUES ('one','feed','episode','https://example.org/audio.mp3','',0,1000,'')")
            db.execSQL("INSERT INTO progress VALUES ('one',500,1000,0)")
            db.execSQL("INSERT INTO downloads VALUES ('one',42)")
            db.version = 1
        }
        PodcastStore(context, name).use { store ->
            assertEquals("show", store.library().podcasts.single().title)
            assertEquals(500L, store.progress("one").positionMs)
            assertEquals(42L, store.downloadIds()["one"])
            store.saveQueue("new", "新しいキュー")
            assertTrue(store.addToQueue("new", "one"))
        }
        context.deleteDatabase(name)
    }
    @Test fun versionTwoQueuesAndHistorySurviveTheLibraryUpgrade(): Unit {
        val context = RuntimeEnvironment.getApplication()
        val name = "library-v2-migration.db"
        context.deleteDatabase(name)
        PodcastStore(context, name).use { old ->
            val show = Podcast("https://example.org/rss", "番組")
            val episode = Episode("one", show.feedUrl, "回", "https://example.org/one.mp3")
            old.saveFeed(PodcastFeed(show, listOf(episode)))
            old.saveProgress(episode.id, ListeningProgress(500, 1000))
            old.saveDownload(episode.id, 42)
            old.saveQueue("news", "ニュース"); old.addToQueue("news", episode.id)
            old.setAutoDownload(show.feedUrl, true)
            old.writableDatabase.apply {
                execSQL("DROP TABLE favorites"); execSQL("DROP INDEX episodes_published"); execSQL("DROP INDEX episodes_feed_published")
                execSQL("ALTER TABLE podcasts DROP COLUMN website")
                version = 2
            }
        }
        PodcastStore(context, name).use { store ->
            assertEquals("", store.podcast("https://example.org/rss")?.website)
            assertEquals(listOf("one"), store.queues().single().episodeIds)
            assertEquals(500L, store.progress("one").positionMs)
            assertEquals(42L, store.downloadIds()["one"])
            assertEquals(setOf("https://example.org/rss"), store.library().autoDownloadFeeds)
            store.setFavorite("one", true)
            assertEquals(setOf("one"), store.library().favorites)
        }
        context.deleteDatabase(name)
    }

}
