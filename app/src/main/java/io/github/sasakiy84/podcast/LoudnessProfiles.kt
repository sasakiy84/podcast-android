package io.github.sasakiy84.podcast

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONException
import org.json.JSONObject

/** Small, versioned analysis records; the playback thread only reads the in-memory map. */
internal class LoudnessProfiles(context: Context, private val settings: PlaybackPreferences) {
    private val preferences = context.getSharedPreferences("audio_loudness", Context.MODE_PRIVATE)
    private val records = ConcurrentHashMap<String, Record>()
    @Volatile
    var options = NormalizationOptions(settings.normalizeVolume, settings.louderVolume)
        private set

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "normalize_volume" || key == "louder_volume") {
                options = NormalizationOptions(settings.normalizeVolume, settings.louderVolume)
            }
        }

    init {
        for ((id, value) in preferences.all) {
            if (value is String) decode(value)?.let { records[id] = it }
        }
        settings.listen(preferenceListener)
    }

    fun needsAnalysis(id: String, file: File): Boolean {
        val record = records[id] ?: return true
        if (record.matches(file)) return false
        remove(id)
        return true
    }

    fun forPlayback(item: MediaItem): Double? {
        val uri = item.localConfiguration?.uri ?: return null
        val record = records[item.mediaId] ?: return null
        return record.lufs.takeIf { uri.scheme == "file" && uri.path == record.path }
    }

    fun save(id: String, file: File, lufs: Double?) {
        val record =
            Record(
                file.absolutePath,
                file.length(),
                file.lastModified(),
                lufs?.takeIf { it.isFinite() },
            )
        records[id] = record
        preferences.edit {
            putString(
                id,
                JSONObject()
                    .apply {
                        put("version", 1)
                        put("path", record.path)
                        put("size", record.size)
                        put("modified", record.modified)
                        put("lufs", record.lufs ?: JSONObject.NULL)
                    }
                    .toString(),
            )
        }
    }

    fun remove(id: String) {
        records.remove(id)
        preferences.edit { remove(id) }
    }

    private data class Record(
        val path: String,
        val size: Long,
        val modified: Long,
        val lufs: Double?,
    ) {
        fun matches(file: File): Boolean =
            file.absolutePath == path &&
                file.isFile &&
                file.length() == size &&
                file.lastModified() == modified
    }

    private fun decode(value: String): Record? =
        try {
            val json = JSONObject(value)
            if (json.getInt("version") != 1) null
            else {
                val lufs = if (json.isNull("lufs")) null else json.getDouble("lufs")
                if (lufs != null && (!lufs.isFinite() || lufs !in -100.0..30.0)) null
                else
                    Record(
                        json.getString("path"),
                        json.getLong("size"),
                        json.getLong("modified"),
                        lufs,
                    )
            }
        } catch (_: JSONException) {
            null
        }
}
