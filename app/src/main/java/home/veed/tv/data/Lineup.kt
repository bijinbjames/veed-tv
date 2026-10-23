package home.veed.tv.data

import kotlinx.serialization.Serializable

/** The channel lineup, as written by tools/lineup/build_lineup.py. */
@Serializable
data class Lineup(
    val version: Int = 1,
    /** ISO-8601 UTC time the lineup was built; newer lineups replace older ones. */
    val generated: String = "",
    val categories: List<Category> = emptyList(),
    /** Sorted by [Channel.number]. */
    val channels: List<Channel> = emptyList(),
    /** Null for veed's tested list; [IptvOrg.SOURCE] for the untested fallback this app makes itself. */
    val source: String? = null,
)

/** [language] is the ISO 639-2 code the server selected the category by ("mal", "eng"). */
@Serializable
data class Category(val id: String, val name: String, val language: String? = null)

@Serializable
data class Channel(
    val number: Int,
    val id: String,
    val name: String,
    val category: String,
    val logo: String? = null,
    /** Working links, best first; the player falls back down the list. */
    val streams: List<Stream> = emptyList(),
)

@Serializable
data class Stream(
    val url: String,
    /** "hls" or "dash". */
    val type: String? = "hls",
    val userAgent: String? = null,
    val referrer: String? = null,
    val height: Int? = null,
)
