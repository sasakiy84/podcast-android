package io.github.sasakiy84.podcast

import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.Collator
import java.util.Locale

enum class PodcastSort(val label: String) { Title("タイトル順"), Updated("新着エピソード順") }

internal val autoDownloadLimits = listOf(1, 2, 3, 5, 10, Int.MAX_VALUE)
internal const val DEFAULT_REFRESH_HOURS = 6
internal val refreshIntervals = listOf(1, 3, 6, 12, 24, 0)

class PlaybackPreferences(private val preferences: SharedPreferences) {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun key(feed: String): String = keys.getOrPut(feed) { "speed_for:${stableId(feed)}" }
    val wifiOnly: Boolean get() = preferences.getBoolean("wifi_only", true)
    val warnOnMobilePlayback: Boolean get() = preferences.getBoolean("warn_on_mobile_playback", true)
    val autoDownloadChargingOnly: Boolean get() = preferences.getBoolean("auto_download_charging", false)
    val autoDownloadRequireStorage: Boolean get() = preferences.getBoolean("auto_download_storage", true)
    val autoDownloadLimit: Int get() = preferences.getInt("auto_download_limit", 2).takeIf { it in autoDownloadLimits } ?: 2
    val normalizeVolume: Boolean get() = preferences.getBoolean("normalize_volume", true)
    val louderVolume: Boolean get() = preferences.getBoolean("louder_volume", false)
    fun setNormalizeVolume(enabled: Boolean): Unit = preferences.edit { putBoolean("normalize_volume", enabled) }
    fun setLouderVolume(enabled: Boolean): Unit = preferences.edit { putBoolean("louder_volume", enabled) }
    val refreshHours: Int get() = preferences.getInt("refresh_hours", DEFAULT_REFRESH_HOURS)
        .takeIf { it in refreshIntervals } ?: DEFAULT_REFRESH_HOURS
    val defaultQueue: String get() = preferences.getString("default_queue", null) ?: "default"
    fun setWifiOnly(value: Boolean): Unit = preferences.edit { putBoolean("wifi_only", value) }
    fun setWarnOnMobilePlayback(value: Boolean): Unit = preferences.edit { putBoolean("warn_on_mobile_playback", value) }
    fun setAutoDownloadChargingOnly(value: Boolean): Unit = preferences.edit { putBoolean("auto_download_charging", value) }
    fun setAutoDownloadRequireStorage(value: Boolean): Unit = preferences.edit { putBoolean("auto_download_storage", value) }
    fun setAutoDownloadLimit(value: Int): Unit {
        require(value in autoDownloadLimits)
        preferences.edit { putInt("auto_download_limit", value) }
    }
    fun setRefreshHours(value: Int): Unit {
        require(value in refreshIntervals)
        preferences.edit { putInt("refresh_hours", value) }
    }
    fun setDefaultQueue(id: String): Unit = preferences.edit { putString("default_queue", id) }
    val globalSpeed: Float get() = preferences.getFloat("speed", 1f).coerceIn(0.5f, 3f)
    val sort: PodcastSort get() = PodcastSort.entries.firstOrNull { it.name == preferences.getString("podcast_sort", null) } ?: PodcastSort.Title
    fun podcastSpeed(feed: String): Float? = if (preferences.contains(key(feed))) preferences.getFloat(key(feed), globalSpeed).coerceIn(0.5f, 3f) else null
    fun speed(feed: String?): Float = feed?.let(::podcastSpeed) ?: globalSpeed
    fun setSpeed(value: Float, feed: String? = null): Unit = preferences.edit { putFloat(feed?.let(::key) ?: "speed", value.coerceIn(0.5f, 3f)) }
    fun useGlobal(feed: String): Unit = preferences.edit { remove(key(feed)) }
    fun setSort(value: PodcastSort): Unit = preferences.edit { putString("podcast_sort", value.name) }
    private fun optionKey(feed: String, name: String): String = "option:$name:${stableId(feed)}"
    fun options(feed: String): PodcastOptions = PodcastOptions(preferences.getBoolean(optionKey(feed, "notify"), false), preferences.getInt(optionKey(feed, "start"), 0), preferences.getInt(optionKey(feed, "end"), 0))
    fun setOptions(feed: String, value: PodcastOptions): Unit = preferences.edit {
        putBoolean(optionKey(feed, "notify"), value.notifications)
        putInt(optionKey(feed, "start"), value.skipStartSeconds.coerceIn(0, 3600))
        putInt(optionKey(feed, "end"), value.skipEndSeconds.coerceIn(0, 3600))
    }
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
