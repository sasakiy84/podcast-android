package io.github.sasakiy84.podcast

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/** A private database keeps subscriptions, downloaded IDs and listening history on this device. */
class PodcastStore(context: Context, databaseName: String = "podcasts.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase): Unit {
        db.execSQL("CREATE TABLE podcasts (feed TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT NOT NULL, description TEXT NOT NULL)")
        db.execSQL("CREATE TABLE episodes (id TEXT PRIMARY KEY, feed TEXT NOT NULL, title TEXT NOT NULL, audio TEXT NOT NULL, description TEXT NOT NULL, published INTEGER NOT NULL, duration INTEGER NOT NULL, artwork TEXT NOT NULL)")
        db.execSQL("CREATE INDEX episodes_feed ON episodes(feed)")
        db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER NOT NULL, completed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY, manager_id INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = error("Unsupported database migration: $oldVersion → $newVersion")

    fun saveFeed(feed: PodcastFeed): Unit {
        val db = writableDatabase
        db.transaction {
            val p = feed.podcast
            db.insertWithOnConflict("podcasts", null, ContentValues().apply {
                put("feed", p.feedUrl); put("title", p.title); put("author", p.author); put("artwork", p.artwork); put("description", p.description)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            for (episode in feed.episodes) {
                db.insertWithOnConflict("episodes", null, ContentValues().apply {
                    put("id", episode.id); put("feed", p.feedUrl); put("title", episode.title); put("audio", episode.audioUrl)
                    put("description", episode.description); put("published", episode.published); put("duration", episode.durationMs); put("artwork", episode.artwork)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun library(): Library {
        val shows = readableDatabase.rawQuery("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(Podcast(c.string("feed"), c.string("title"), c.string("author"), c.string("artwork"), c.string("description"))) }
        }
        val episodes = readableDatabase.rawQuery("SELECT * FROM episodes ORDER BY published DESC, rowid DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.episode()) }
        }
        val progress = readableDatabase.rawQuery("SELECT * FROM progress", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.string("id"), ListeningProgress(c.long("position"), c.long("duration"), c.long("completed") != 0L)) }
        }
        return Library(shows, episodes, progress)
    }

    fun podcast(feedUrl: String): Podcast? = readableDatabase.rawQuery("SELECT * FROM podcasts WHERE feed = ?", arrayOf(feedUrl)).use { c ->
        if (c.moveToFirst()) Podcast(c.string("feed"), c.string("title"), c.string("author"), c.string("artwork"), c.string("description")) else null
    }

    fun episode(id: String): Episode? = readableDatabase.rawQuery("SELECT * FROM episodes WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) c.episode() else null
    }

    fun progress(id: String): ListeningProgress = readableDatabase.rawQuery("SELECT * FROM progress WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) ListeningProgress(c.long("position"), c.long("duration"), c.long("completed") != 0L) else ListeningProgress()
    }

    fun saveProgress(id: String, progress: ListeningProgress): Unit {
        if (episode(id) == null) return
        writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
            put("id", id); put("position", progress.positionMs.coerceAtLeast(0)); put("duration", progress.durationMs.coerceAtLeast(0)); put("completed", if (progress.completed) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveDownload(id: String, managerId: Long): Unit {
        writableDatabase.insertWithOnConflict("downloads", null, ContentValues().apply { put("id", id); put("manager_id", managerId) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun downloadIds(): Map<String, Long> = readableDatabase.rawQuery("SELECT * FROM downloads", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.string("id"), c.long("manager_id")) }
    }

    fun removeDownload(id: String): Unit { writableDatabase.delete("downloads", "id = ?", arrayOf(id)) }

    fun unsubscribe(feedUrl: String): Unit {
        val db = writableDatabase
        db.transaction {
            for (table in listOf("progress", "downloads")) db.execSQL("DELETE FROM $table WHERE id IN (SELECT id FROM episodes WHERE feed = ?)", arrayOf(feedUrl))
            db.delete("episodes", "feed = ?", arrayOf(feedUrl))
            db.delete("podcasts", "feed = ?", arrayOf(feedUrl))
        }
    }

    private fun Cursor.episode(): Episode = Episode(string("id"), string("feed"), string("title"), string("audio"), string("description"), long("published"), long("duration"), string("artwork"))
    private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
}
