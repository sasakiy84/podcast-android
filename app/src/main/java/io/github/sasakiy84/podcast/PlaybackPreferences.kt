package io.github.sasakiy84.podcast

import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.Collator
import java.util.Locale

enum class PodcastSort(val label: String) { Title("タイトル順"), Updated("新着エピソード順") }

class PlaybackPreferences(private val preferences: SharedPreferences) {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun key(feed: String): String = keys.getOrPut(feed) { "speed_for:${stableId(feed)}" }
    val globalSpeed: Float get() = preferences.getFloat("speed", 1f).coerceIn(0.5f, 3f)
    val sort: PodcastSort get() = PodcastSort.entries.firstOrNull { it.name == preferences.getString("podcast_sort", null) } ?: PodcastSort.Title
    fun podcastSpeed(feed: String): Float? = if (preferences.contains(key(feed))) preferences.getFloat(key(feed), globalSpeed).coerceIn(0.5f, 3f) else null
    fun speed(feed: String?): Float = feed?.let(::podcastSpeed) ?: globalSpeed
    fun setSpeed(value: Float, feed: String? = null): Unit = preferences.edit { putFloat(feed?.let(::key) ?: "speed", value.coerceIn(0.5f, 3f)) }
    fun useGlobal(feed: String): Unit = preferences.edit { remove(key(feed)) }
    fun setSort(value: PodcastSort): Unit = preferences.edit { putString("podcast_sort", value.name) }
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener): Unit = preferences.registerOnSharedPreferenceChangeListener(listener)
    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener): Unit = preferences.unregisterOnSharedPreferenceChangeListener(listener)
}

fun sortedPodcasts(library: Library, sort: PodcastSort): List<Podcast> {
    val latest = if (library.latestEpisodes.isNotEmpty()) library.latestEpisodes else library.episodes.groupBy { it.feedUrl }.mapValues { (_, episodes) -> episodes.maxOf { it.published } }
    val collator = Collator.getInstance(Locale.JAPANESE)
    return library.podcasts.sortedWith { a, b ->
        val date = if (sort == PodcastSort.Updated) (latest[b.feedUrl] ?: 0).compareTo(latest[a.feedUrl] ?: 0) else 0
        if (date != 0) date else collator.compare(a.title, b.title).takeIf { it != 0 } ?: a.feedUrl.compareTo(b.feedUrl)
    }
}
