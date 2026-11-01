package io.github.sasakiy84.podcast

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Preserve paragraphs, lists and visible link targets without rendering untrusted feed HTML. */
fun descriptionText(value: String, base: String = ""): String {
    if (!Regex("</?[A-Za-z][^>]*>").containsMatchIn(value)) return value.trim()
    val document = Jsoup.parseBodyFragment(value, base)
    document.select("script,style,iframe").remove()
    document.select("a[href]").forEach { a ->
        val url = absoluteUrl(base, a.attr("href"))
        if (url.isNotBlank() && a.text() != url) a.appendText(" ($url)")
    }
    document.select("p,div,h1,h2,h3,h4,ul,ol,blockquote,pre").forEach { it.before("\n"); it.after("\n") }
    document.select("li").forEach { it.before("\n• "); it.after("\n") }
    return document.body().wholeText().replace('\u00a0', ' ').replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n[ \\t]+"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}

object EpisodeChapters {
    fun encode(chapters: List<EpisodeChapter>): String = JSONArray().apply { chapters.forEach { put(JSONObject().put("startTime", it.startMs / 1000.0).put("title", it.title).put("url", it.url).put("img", it.image)) } }.toString()
    fun decode(value: String, base: String = ""): List<EpisodeChapter> {
        if (value.isBlank() || value == "[]") return emptyList()
        val array = if (value.trimStart().startsWith("[")) JSONArray(value) else JSONObject(value).optJSONArray("chapters") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val c = array.optJSONObject(index) ?: return@mapNotNull null
            val start = c.optDouble("startTime", Double.NaN)
            if (!start.isFinite() || start < 0) return@mapNotNull null
            EpisodeChapter((start * 1000).toLong(), c.optString("title"), absoluteUrl(base, c.optString("url")), absoluteUrl(base, c.optString("img")))
        }.distinctBy { it.startMs }.sortedBy { it.startMs }
    }
}
