package io.github.sasakiy84.podcast

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/** A private database keeps subscriptions, downloaded IDs and listening history on this device. */
class PodcastStore(context: Context, databaseName: String = "podcasts.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase): Unit {
        db.execSQL("CREATE TABLE podcasts (feed TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT NOT NULL, description TEXT NOT NULL)")
        db.execSQL("CREATE TABLE episodes (id TEXT PRIMARY KEY, feed TEXT NOT NULL, title TEXT NOT NULL, audio TEXT NOT NULL, description TEXT NOT NULL, published INTEGER NOT NULL, duration INTEGER NOT NULL, artwork TEXT NOT NULL)")
        db.execSQL("CREATE INDEX episodes_feed ON episodes(feed)")
        db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER NOT NULL, completed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY, manager_id INTEGER NOT NULL)")
        createQueues(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit {
        if (oldVersion < 2) createQueues(db)
    }

    private fun createQueues(db: SQLiteDatabase): Unit {
        db.execSQL("CREATE TABLE queues (id TEXT PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE queue_entries (queue_id TEXT NOT NULL, episode_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(queue_id, episode_id))")
        db.execSQL("CREATE INDEX queue_episode ON queue_entries(episode_id)")
        db.execSQL("CREATE TABLE queue_rules (queue_id TEXT NOT NULL, feed TEXT NOT NULL, include_words TEXT NOT NULL, exclude_words TEXT NOT NULL, match_all INTEGER NOT NULL, auto_download INTEGER NOT NULL, enabled INTEGER NOT NULL, PRIMARY KEY(queue_id, feed))")
        db.execSQL("CREATE TABLE podcast_options (feed TEXT PRIMARY KEY, auto_download INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE automatic_downloads (episode_id TEXT PRIMARY KEY)")
    }

    fun saveFeed(feed: PodcastFeed): Unit {
        val db = writableDatabase
        db.transaction {
            val p = feed.podcast
            db.insertWithOnConflict("podcasts", null, ContentValues().apply {
                put("feed", p.feedUrl); put("title", p.title); put("author", p.author); put("artwork", p.artwork); put("description", p.description)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            for (episode in feed.episodes) {
                val isNew = db.rawQuery("SELECT 1 FROM episodes WHERE id = ?", arrayOf(episode.id)).use { !it.moveToFirst() }
                db.insertWithOnConflict("episodes", null, ContentValues().apply {
                    put("id", episode.id); put("feed", p.feedUrl); put("title", episode.title); put("audio", episode.audioUrl)
                    put("description", episode.description); put("published", episode.published); put("duration", episode.durationMs); put("artwork", episode.artwork)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                if (isNew && !progress(episode.id).completed) {
                    val matching = rules().filter { it.matches(episode) }
                    matching.forEach { addToQueue(it.queueId, episode.id) }
                    if (p.feedUrl in autoDownloadFeeds() || matching.any { it.autoDownload }) requestAutomaticDownload(episode.id)
                }
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
        return Library(shows, episodes, progress, queues = queues(), queueRules = rules(), autoDownloadFeeds = autoDownloadFeeds())
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

    fun saveProgress(id: String, progress: ListeningProgress, resetCompletion: Boolean = false): Unit {
        if (episode(id) == null) return
        writableDatabase.transaction {
            val previous = this@PodcastStore.progress(id)
            val value = if (!resetCompletion && previous.completed && !progress.completed) previous else progress
            writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
                put("id", id); put("position", value.positionMs.coerceAtLeast(0)); put("duration", value.durationMs.coerceAtLeast(0)); put("completed", if (value.completed) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (value.completed) {
                writableDatabase.delete("queue_entries", "episode_id = ?", arrayOf(id))
                writableDatabase.delete("automatic_downloads", "episode_id = ?", arrayOf(id))
            }
        }
    }

    fun saveDownload(id: String, managerId: Long): Unit {
        writableDatabase.insertWithOnConflict("downloads", null, ContentValues().apply { put("id", id); put("manager_id", managerId) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun downloadId(id: String): Long? = readableDatabase.rawQuery("SELECT manager_id FROM downloads WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun downloadIds(): Map<String, Long> = readableDatabase.rawQuery("SELECT * FROM downloads", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.string("id"), c.long("manager_id")) }
    }

    fun removeDownload(id: String): Unit { writableDatabase.delete("downloads", "id = ?", arrayOf(id)) }

    fun queues(): List<PlaybackQueue> = readableDatabase.rawQuery("SELECT * FROM queues ORDER BY created, rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(PlaybackQueue(c.string("id"), c.string("name"), queueEpisodeIds(c.string("id")))) }
    }

    fun queueEpisodeIds(queueId: String): List<String> = readableDatabase.rawQuery("SELECT episode_id FROM queue_entries WHERE queue_id = ? ORDER BY position, rowid", arrayOf(queueId)).use { c ->
        buildList { while (c.moveToNext()) add(c.string("episode_id")) }
    }

    fun saveQueue(id: String, name: String): Unit {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 80) { "キュー名は1〜80文字で入力してください。" }
        val values = ContentValues().apply { put("id", id); put("name", trimmed); put("created", System.currentTimeMillis()) }
        if (writableDatabase.update("queues", ContentValues().apply { put("name", trimmed) }, "id = ?", arrayOf(id)) == 0) writableDatabase.insertOrThrow("queues", null, values)
    }

    fun deleteQueue(id: String): Unit = writableDatabase.transaction {
        delete("queue_entries", "queue_id = ?", arrayOf(id))
        delete("queue_rules", "queue_id = ?", arrayOf(id))
        delete("queues", "id = ?", arrayOf(id))
        Unit
    }

    fun addToQueue(queueId: String, episodeId: String): Boolean {
        if (episode(episodeId) == null || progress(episodeId).completed) return false
        if (readableDatabase.rawQuery("SELECT 1 FROM queues WHERE id = ?", arrayOf(queueId)).use { !it.moveToFirst() }) return false
        val position = readableDatabase.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM queue_entries WHERE queue_id = ?", arrayOf(queueId)).use { it.moveToFirst(); it.getLong(0) }
        return writableDatabase.insertWithOnConflict("queue_entries", null, ContentValues().apply {
            put("queue_id", queueId); put("episode_id", episodeId); put("position", position)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun removeFromQueue(queueId: String, episodeId: String): Unit { writableDatabase.delete("queue_entries", "queue_id = ? AND episode_id = ?", arrayOf(queueId, episodeId)) }

    fun moveInQueue(queueId: String, episodeId: String, direction: Int): Unit = writableDatabase.transaction {
        val ids = queueEpisodeIds(queueId).toMutableList()
        val index = ids.indexOf(episodeId)
        val destination = index + direction.coerceIn(-1, 1)
        if (index >= 0 && destination in ids.indices) {
            java.util.Collections.swap(ids, index, destination)
            ids.forEachIndexed { order, id -> update("queue_entries", ContentValues().apply { put("position", order) }, "queue_id = ? AND episode_id = ?", arrayOf(queueId, id)) }
        }
    }

    fun rules(): List<QueueRule> = readableDatabase.rawQuery("SELECT * FROM queue_rules ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(QueueRule(c.string("queue_id"), c.string("feed"), c.string("include_words"), c.string("exclude_words"), c.long("match_all") != 0L, c.long("auto_download") != 0L, c.long("enabled") != 0L)) }
    }

    fun saveRule(rule: QueueRule, applyExisting: Boolean): Unit = writableDatabase.transaction {
        require(queues().any { it.id == rule.queueId } && podcast(rule.feedUrl) != null) { "キューと番組を選択してください。" }
        insertWithOnConflict("queue_rules", null, ContentValues().apply {
            put("queue_id", rule.queueId); put("feed", rule.feedUrl); put("include_words", rule.includeWords); put("exclude_words", rule.excludeWords)
            put("match_all", if (rule.matchAll) 1 else 0); put("auto_download", if (rule.autoDownload) 1 else 0); put("enabled", if (rule.enabled) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (applyExisting) library().episodes.filter { rule.matches(it) && !progress(it.id).completed }.forEach {
            addToQueue(rule.queueId, it.id)
            if (rule.autoDownload) requestAutomaticDownload(it.id)
        }
    }

    fun deleteRule(queueId: String, feedUrl: String): Unit { writableDatabase.delete("queue_rules", "queue_id = ? AND feed = ?", arrayOf(queueId, feedUrl)) }

    fun setAutoDownload(feedUrl: String, enabled: Boolean): Unit {
        require(podcast(feedUrl) != null)
        writableDatabase.insertWithOnConflict("podcast_options", null, ContentValues().apply { put("feed", feedUrl); put("auto_download", if (enabled) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun autoDownloadFeeds(): Set<String> = readableDatabase.rawQuery("SELECT feed FROM podcast_options WHERE auto_download = 1", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.string("feed")) }
    }

    private fun requestAutomaticDownload(id: String): Unit {
        writableDatabase.insertWithOnConflict("automatic_downloads", null, ContentValues().apply { put("episode_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun automaticDownloadIds(): List<String> = readableDatabase.rawQuery("SELECT episode_id FROM automatic_downloads ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(c.string("episode_id")) }
    }

    fun cancelAutomaticDownload(id: String): Unit { writableDatabase.delete("automatic_downloads", "episode_id = ?", arrayOf(id)) }

    fun needsBackgroundSync(): Boolean = rules().any { it.enabled } || autoDownloadFeeds().isNotEmpty() || automaticDownloadIds().isNotEmpty()

    fun unsubscribe(feedUrl: String): Unit {
        val db = writableDatabase
        db.transaction {
            for (table in listOf("progress", "downloads")) db.execSQL("DELETE FROM $table WHERE id IN (SELECT id FROM episodes WHERE feed = ?)", arrayOf(feedUrl))
            for (table in listOf("queue_entries", "automatic_downloads")) db.execSQL("DELETE FROM $table WHERE episode_id IN (SELECT id FROM episodes WHERE feed = ?)", arrayOf(feedUrl))
            db.delete("queue_rules", "feed = ?", arrayOf(feedUrl))
            db.delete("podcast_options", "feed = ?", arrayOf(feedUrl))
            db.delete("episodes", "feed = ?", arrayOf(feedUrl))
            db.delete("podcasts", "feed = ?", arrayOf(feedUrl))
        }
    }

    private fun Cursor.episode(): Episode = Episode(string("id"), string("feed"), string("title"), string("audio"), string("description"), long("published"), long("duration"), string("artwork"))
    private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
}
