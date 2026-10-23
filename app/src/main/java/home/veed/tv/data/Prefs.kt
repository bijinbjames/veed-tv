package home.veed.tv.data

import android.content.Context

/**
 * The little the app remembers between runs. Channels are remembered by **number**, not id:
 * the server keeps a number with a channel's *name*, and on a night when it swaps in another
 * country's feed of the same channel (a different id), the family's favourite and the
 * channel the TV reopens on must not disappear.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("veedtv", Context.MODE_PRIVATE)

    /** The channel to start on, like a set-top box; 0 = none yet. */
    var lastChannelNumber: Int
        get() = sp.getInt("last_number", 0)
        set(value) = sp.edit().putInt("last_number", value).apply()

    var favourites: Set<Int>
        get() = sp.getStringSet("favourite_numbers", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("favourite_numbers", value.map { it.toString() }.toSet()).apply()

    /**
     * Version 0.1 stored channel ids. Convert those this lineup knows to numbers, and keep the
     * rest for a later lineup: a favourite that happens to be down on the night of the upgrade
     * is not lost. True when it changed the favourites.
     */
    fun migrateFromIds(lineup: Lineup): Boolean {
        val oldFavourites = sp.getStringSet("favourites", null)
        val oldLast = sp.getString("last_channel", null)
        if (oldFavourites == null && oldLast == null) return false
        val numberOf = lineup.channels.associate { it.id to it.number }
        val mapped = oldFavourites.orEmpty().mapNotNull { numberOf[it] }
        val unmapped = oldFavourites.orEmpty().filter { it !in numberOf }.toSet()
        val edit = sp.edit().putStringSet("favourite_numbers", (favourites + mapped).map { it.toString() }.toSet())
        if (unmapped.isEmpty()) edit.remove("favourites") else edit.putStringSet("favourites", unmapped)
        // Only the first lineup gets a say in where the TV starts.
        oldLast?.let { numberOf[it] }?.let { if (lastChannelNumber == 0) edit.putInt("last_number", it) }
        edit.remove("last_channel").apply()
        return mapped.isNotEmpty()
    }
}
