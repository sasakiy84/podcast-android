package io.github.sasakiy84.podcast

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/** A private database keeps subscriptions, downloaded IDs and listening history on this device. */
class PodcastStore(context: Context, databaseName: String = "podcasts.db") : SQLiteOpenHelper(context, databaseName, null, 4) {
    override fun onCreate(db: SQLiteDatabase): Unit {
        db.execSQL("CREATE TABLE podcasts (feed TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT NOT NULL, description TEXT NOT NULL, website TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE episodes (id TEXT PRIMARY KEY, feed TEXT NOT NULL, title TEXT NOT NULL, audio TEXT NOT NULL, description TEXT NOT NULL, published INTEGER NOT NULL, duration INTEGER NOT NULL, artwork TEXT NOT NULL)")
        db.execSQL("CREATE INDEX episodes_feed ON episodes(feed)")
        db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER NOT NULL, completed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY, manager_id INTEGER NOT NULL)")
        createQueues(db)
        createLibraryIndexes(db)
        createEpisodeFeatures(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit {
        if (oldVersion < 2) createQueues(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE podcasts ADD COLUMN website TEXT NOT NULL DEFAULT ''")
            createLibraryIndexes(db)
        }
        if (oldVersion < 4) createEpisodeFeatures(db)
    }

    private fun createEpisodeFeatures(db: SQLiteDatabase): Unit {
        db.execSQL("ALTER TABLE episodes ADD COLUMN chapters TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE episodes ADD COLUMN chapters_url TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE listening_history (id TEXT PRIMARY KEY, listened INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_listened ON listening_history(listened DESC)")
        db.execSQL("INSERT INTO listening_history SELECT id, 0 FROM progress WHERE position > 0 OR completed = 1")
    }

    private fun createLibraryIndexes(db: SQLiteDatabase): Unit {
        db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY)")
        db.execSQL("CREATE INDEX episodes_published ON episodes(published DESC)")
        db.execSQL("CREATE INDEX episodes_feed_published ON episodes(feed, published DESC)")
    }

    private fun createQueues(db: SQLiteDatabase): Unit {
        db.execSQL("CREATE TABLE queues (id TEXT PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE queue_entries (queue_id TEXT NOT NULL, episode_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(queue_id, episode_id))")
        db.execSQL("CREATE INDEX queue_episode ON queue_entries(episode_id)")
        db.execSQL("CREATE TABLE queue_rules (queue_id TEXT NOT NULL, feed TEXT NOT NULL, include_words TEXT NOT NULL, exclude_words TEXT NOT NULL, match_all INTEGER NOT NULL, auto_download INTEGER NOT NULL, enabled INTEGER NOT NULL, PRIMARY KEY(queue_id, feed))")
        db.execSQL("CREATE TABLE podcast_options (feed TEXT PRIMARY KEY, auto_download INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE automatic_downloads (episode_id TEXT PRIMARY KEY)")
    }

    fun importSubscriptions(podcasts: List<Podcast>): List<Podcast> {
        val added = mutableListOf<Podcast>()
        writableDatabase.transaction {
            for (podcast in podcasts) {
                val inserted = insertWithOnConflict("podcasts", null, ContentValues().apply {
                    put("feed", podcast.feedUrl); put("title", podcast.title); put("author", podcast.author)
                    put("artwork", podcast.artwork); put("description", podcast.description.take(64_000)); put("website", podcast.website)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted != -1L) added += podcast
            }
        }
        return added
    }

    fun saveFeed(feed: PodcastFeed): Unit {
        val db = writableDatabase
        db.transaction {
            val p = feed.podcast
            db.insertWithOnConflict("podcasts", null, ContentValues().apply {
                put("feed", p.feedUrl); put("title", p.title); put("author", p.author); put("artwork", p.artwork); put("description", p.description.take(64_000)); put("website", p.website)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            val existing = db.rawQuery("SELECT id FROM episodes WHERE feed = ?", arrayOf(p.feedUrl)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            val completed = db.rawQuery("SELECT p.id FROM progress p JOIN episodes e ON e.id = p.id WHERE e.feed = ? AND p.completed = 1", arrayOf(p.feedUrl)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            val feedRules = rules().filter { it.feedUrl == p.feedUrl && it.enabled }
            val automaticDownload = p.feedUrl in autoDownloadFeeds()
            for (episode in feed.episodes) {
                val isNew = episode.id !in existing
                db.insertWithOnConflict("episodes", null, ContentValues().apply {
                    put("id", episode.id); put("feed", p.feedUrl); put("title", episode.title); put("audio", episode.audioUrl)
                    put("description", episode.description.take(64_000)); put("published", episode.published); put("duration", episode.durationMs); put("artwork", episode.artwork); put("chapters", EpisodeChapters.encode(episode.chapters)); put("chapters_url", episode.chaptersUrl)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                if (isNew && episode.id !in completed) {
                    val matching = feedRules.filter { it.matches(episode) }
                    matching.forEach { addToQueue(it.queueId, episode.id) }
                    if (automaticDownload || matching.any { it.autoDownload }) requestAutomaticDownload(episode.id)
                }
            }
        }
    }

    fun library(limit: Int = 1000, currentId: String? = null, historyLimit: Int = 150): Library {
        val shows = readableDatabase.rawQuery("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(Podcast(c.string("feed"), c.string("title"), c.string("author"), c.string("artwork"), c.string("description"), c.string("website"))) }
        }
        val episodes = readableDatabase.rawQuery("SELECT $episodePreview FROM episodes WHERE id IN (SELECT id FROM episodes ORDER BY published DESC, rowid DESC LIMIT ?) OR id IN (SELECT episode_id FROM queue_entries UNION SELECT id FROM favorites UNION SELECT id FROM downloads) OR id IN (SELECT id FROM listening_history ORDER BY listened DESC, rowid DESC LIMIT " + historyLimit.coerceAtLeast(1) + ") OR id = ? ORDER BY published DESC, rowid DESC", arrayOf(limit.coerceAtLeast(1).toString(), currentId.orEmpty())).use { c ->
            buildList { while (c.moveToNext()) add(c.episode()) }
        }
        val progress = readableDatabase.rawQuery("SELECT * FROM progress", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.string("id"), ListeningProgress(c.long("position"), c.long("duration"), c.long("completed") != 0L)) }
        }
        val counts = linkedMapOf<String, Int>()
        val latest = linkedMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT feed, COUNT(*) AS total, MAX(published) AS latest FROM episodes GROUP BY feed", null).use { c ->
            while (c.moveToNext()) { counts[c.getString(0)] = c.getInt(1); latest[c.getString(0)] = c.getLong(2) }
        }
        val favorites = readableDatabase.rawQuery("SELECT id FROM favorites", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        return Library(shows, episodes, progress, queues = queues(), queueRules = rules(), autoDownloadFeeds = autoDownloadFeeds(), favorites = favorites,
            latestEpisodes = latest, episodeCounts = counts, hasMoreEpisodes = counts.values.sum() > limit,
            historyIds = readableDatabase.rawQuery("SELECT id FROM listening_history ORDER BY listened DESC, rowid DESC LIMIT ?", arrayOf(historyLimit.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } },
            hasMoreHistory = readableDatabase.rawQuery("SELECT COUNT(*) FROM listening_history", null).use { it.moveToFirst(); it.getInt(0) > historyLimit })
    }

    private val episodePreview = "id, feed, title, audio, substr(description, 1, 512) AS description, published, duration, artwork, chapters, chapters_url"

    fun episodeIds(feed: String): Set<String> = readableDatabase.rawQuery("SELECT id FROM episodes WHERE feed = ?", arrayOf(feed)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun podcastEpisodes(feedUrl: String, limit: Int = 150): PodcastEpisodePage {
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM episodes WHERE feed = ?", arrayOf(feedUrl)).use { it.moveToFirst(); it.getInt(0) }
        val episodes = readableDatabase.rawQuery("SELECT $episodePreview FROM episodes WHERE feed = ? ORDER BY published DESC, rowid DESC LIMIT ?", arrayOf(feedUrl, limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.episode()) }
        }
        return PodcastEpisodePage(episodes, total)
    }

    fun setFavorite(id: String, favorite: Boolean): Unit {
        if (favorite && episode(id) != null) writableDatabase.insertWithOnConflict("favorites", null, ContentValues().apply { put("id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("favorites", "id = ?", arrayOf(id))
    }

    fun podcast(feedUrl: String): Podcast? = readableDatabase.rawQuery("SELECT * FROM podcasts WHERE feed = ?", arrayOf(feedUrl)).use { c ->
        if (c.moveToFirst()) Podcast(c.string("feed"), c.string("title"), c.string("author"), c.string("artwork"), c.string("description"), c.string("website")) else null
    }

    fun episode(id: String): Episode? = readableDatabase.rawQuery("SELECT * FROM episodes WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) c.episode() else null
    }

    fun progress(id: String): ListeningProgress = readableDatabase.rawQuery("SELECT * FROM progress WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) ListeningProgress(c.long("position"), c.long("duration"), c.long("completed") != 0L) else ListeningProgress()
    }

    fun saveProgress(id: String, progress: ListeningProgress, resetCompletion: Boolean = false, listened: Boolean = false): Unit {
        if (episode(id) == null) return
        writableDatabase.transaction {
            if (listened) insertWithOnConflict("listening_history", null, ContentValues().apply { put("id", id); put("listened", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
            val previous = this@PodcastStore.progress(id)
            val value = if (!resetCompletion && previous.completed && !progress.completed) previous else progress.copy(durationMs = progress.durationMs.takeIf { it > 0 } ?: previous.durationMs)
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

    private fun episodesForRule(feedUrl: String): Sequence<Episode> = sequence {
        readableDatabase.rawQuery("SELECT * FROM episodes WHERE feed = ?", arrayOf(feedUrl)).use { c -> while (c.moveToNext()) yield(c.episode()) }
    }

    fun saveRule(rule: QueueRule, applyExisting: Boolean): Unit = writableDatabase.transaction {
        require(queues().any { it.id == rule.queueId } && podcast(rule.feedUrl) != null) { "キューと番組を選択してください。" }
        insertWithOnConflict("queue_rules", null, ContentValues().apply {
            put("queue_id", rule.queueId); put("feed", rule.feedUrl); put("include_words", rule.includeWords); put("exclude_words", rule.excludeWords)
            put("match_all", if (rule.matchAll) 1 else 0); put("auto_download", if (rule.autoDownload) 1 else 0); put("enabled", if (rule.enabled) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (applyExisting) episodesForRule(rule.feedUrl).filter { rule.matches(it) && !progress(it.id).completed }.forEach {
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
            for (table in listOf("progress", "downloads", "favorites", "listening_history")) db.execSQL("DELETE FROM $table WHERE id IN (SELECT id FROM episodes WHERE feed = ?)", arrayOf(feedUrl))
            for (table in listOf("queue_entries", "automatic_downloads")) db.execSQL("DELETE FROM $table WHERE episode_id IN (SELECT id FROM episodes WHERE feed = ?)", arrayOf(feedUrl))
            db.delete("queue_rules", "feed = ?", arrayOf(feedUrl))
            db.delete("podcast_options", "feed = ?", arrayOf(feedUrl))
            db.delete("episodes", "feed = ?", arrayOf(feedUrl))
            db.delete("podcasts", "feed = ?", arrayOf(feedUrl))
        }
    }

    private fun Cursor.episode(): Episode = Episode(string("id"), string("feed"), string("title"), string("audio"), string("description"), long("published"), long("duration"), string("artwork"), EpisodeChapters.decode(string("chapters")), string("chapters_url"))
    private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
}
