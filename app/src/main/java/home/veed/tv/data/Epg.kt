package home.veed.tv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One programme; times are epoch seconds. */
class Programme(val start: Long, val stop: Long, val title: String)

/**
 * Programme titles per channel id, from the home server's epg.json
 * (`{"channels": {"AsianetNews.in": [[start, stop, "title"], ...]}}`, sorted by start).
 */
class Epg(private val byChannel: Map<String, List<Programme>> = emptyMap()) {
    val isEmpty: Boolean get() = byChannel.isEmpty()

    /** What [channelId] is showing at [now], and what comes after it. Either may be null. */
    fun nowNext(channelId: String, now: Long): Pair<Programme?, Programme?> {
        val list = byChannel[channelId] ?: return null to null
        val i = list.indexOfFirst { it.stop > now }
        if (i < 0) return null to null
        val current = list[i].takeIf { it.start <= now }
        return current to (if (current != null) list.getOrNull(i + 1) else list[i])
    }

    companion object {
        fun parse(text: String): Epg {
            val channels = Json.parseToJsonElement(text).jsonObject["channels"]?.jsonObject ?: return Epg()
            return Epg(
                channels.mapValues { (_, progs) ->
                    progs.jsonArray.map { p ->
                        val a = p.jsonArray
                        Programme(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content)
                    }
                },
            )
        }
    }
}
