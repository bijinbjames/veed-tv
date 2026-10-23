package home.veed.tv.data

import android.util.JsonReader
import android.util.JsonToken
import home.veed.tv.VeedTvApp
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The fallback for a TV that cannot reach veed, such as a Fire OS 6 stick, which Tailscale no
 * longer runs on: refresh the links of the channels this app already has straight from
 * iptv-org's public lists, the same source veed starts from, but without veed's testing.
 *
 * Same channels, numbers, names and categories as the last list veed made (the APK carries
 * one). For each channel: the links veed tested that iptv-org still lists, in veed's order,
 * then iptv-org's other links for it, best picture first and geo-blocked ones last, up to
 * [MAX_LINKS]. As on veed, a link only counts if its feed carries the category's language.
 */
object IptvOrg {
    const val SOURCE = "iptv-org"
    private const val API = "https://iptv-org.github.io/api"
    private const val MAX_LINKS = 4

    private class Feed(val main: Boolean, val languages: List<String>)
    private class Link(val feed: String?, val stream: Stream, val flagged: Boolean)

    /** Blocking: about 1.2 MB over the network and a few seconds of parsing. */
    fun derive(base: Lineup): Lineup {
        val ids = base.channels.mapTo(HashSet()) { it.id }
        val feeds = HashMap<String, HashMap<String, Feed>>()
        read("feeds.json") { r ->
            var channel: String? = null
            var id: String? = null
            var main = false
            var languages = emptyList<String>()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "channel" -> channel = r.optString()
                    "id" -> id = r.optString()
                    "is_main" -> main = if (r.peek() == JsonToken.BOOLEAN) r.nextBoolean() else { r.skipValue(); false }
                    "languages" -> languages = r.strings()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (channel != null && id != null && channel in ids) feeds.getOrPut(channel) { HashMap() }[id] = Feed(main, languages)
        }
        val links = HashMap<String, MutableList<Link>>()
        read("streams.json") { r ->
            var channel: String? = null
            var feed: String? = null
            var url: String? = null
            var referrer: String? = null
            var userAgent: String? = null
            var quality: String? = null
            var flagged = false
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "channel" -> channel = r.optString()
                    "feed" -> feed = r.optString()
                    "url" -> url = r.optString()
                    "referrer" -> referrer = r.optString()
                    "user_agent" -> userAgent = r.optString()
                    "quality" -> quality = r.optString()
                    "labels" -> flagged = r.strings().isNotEmpty() // "Geo-blocked", "Not 24/7"
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (channel != null && url != null && channel in ids && (url.startsWith("http://") || url.startsWith("https://"))) {
                val height = quality?.let { Regex("(\\d+)[pi]").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val type = if (url.substringBefore('?').endsWith(".mpd", ignoreCase = true)) "dash" else "hls"
                links.getOrPut(channel) { mutableListOf() } += Link(feed, Stream(url, type, userAgent, referrer, height), flagged)
            }
        }
        val language = base.categories.associate { it.id to it.language }
        val channels = base.channels.map { ch ->
            val all = links[ch.id].orEmpty()
            val listed = all.mapTo(HashSet()) { it.stream.url }
            val kept = ch.streams.filter { it.url in listed }
            val lang = language[ch.category]
            val chFeeds = feeds[ch.id].orEmpty()
            val mainFeed = chFeeds.entries.firstOrNull { it.value.main }?.key
            val extra = all
                .filter { link ->
                    val f = chFeeds[link.feed ?: mainFeed]
                    f != null && (lang == null || lang in f.languages) && kept.none { it.url == link.stream.url }
                }
                .sortedWith(compareBy<Link> { it.flagged }.thenByDescending { it.stream.height ?: 0 })
                .map { it.stream }
            val fresh = (kept + extra).distinctBy { it.url }.take(MAX_LINKS)
            // A channel iptv-org no longer lists keeps the links it had.
            if (fresh.isEmpty()) ch else ch.copy(streams = fresh)
        }
        return base.copy(generated = utcNow(), channels = channels, source = SOURCE)
    }

    private fun read(file: String, each: (JsonReader) -> Unit) {
        val conn = (URL("$API/$file").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", VeedTvApp.LOGO_USER_AGENT)
        }
        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "$file: HTTP ${conn.responseCode}" }
            JsonReader(BufferedReader(conn.inputStream.reader(Charsets.UTF_8), 64 * 1024)).use { r ->
                r.beginArray()
                while (r.hasNext()) each(r)
                r.endArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonReader.optString(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun JsonReader.strings(): List<String> {
        if (peek() == JsonToken.NULL) { nextNull(); return emptyList() }
        val out = ArrayList<String>()
        beginArray()
        while (hasNext()) optString()?.let(out::add)
        endArray()
        return out
    }

    /** The same shape veed writes ("2026-10-22T15:04:05+00:00"), so lists compare as strings. */
    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
