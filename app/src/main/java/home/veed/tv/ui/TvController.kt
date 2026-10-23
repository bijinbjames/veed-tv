package home.veed.tv.ui

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import home.veed.tv.data.AppRelease
import home.veed.tv.data.Channel
import home.veed.tv.data.Epg
import home.veed.tv.data.Lineup
import home.veed.tv.data.Prefs
import home.veed.tv.player.TuneState
import home.veed.tv.player.TvPlayer
import home.veed.tv.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class Overlay { None, Guide }

class GuideCategory(val id: String, val name: String, val channels: List<Channel>)

/**
 * Everything the remote can do, in one place. Both of the household's remotes (the Samsung
 * TV's, through HDMI-CEC, and the Google TV phone app) arrive as ordinary key events, so the
 * whole app works from ▲▼◀▶, OK and BACK; number and channel keys are extras.
 */
class TvController(
    private val prefs: Prefs,
    private val player: TvPlayer,
    val updater: Updater,
    private val scope: CoroutineScope,
    private val exit: () -> Unit,
) {
    var lineup by mutableStateOf(Lineup())
        private set
    var current by mutableStateOf<Channel?>(null)
        private set

    /** Shown in the banner while ▲/▼ is still being pressed; tuned once the viewer pauses. */
    var pending by mutableStateOf<Channel?>(null)
        private set
    var overlay by mutableStateOf(Overlay.None)
        private set
    var bannerVisible by mutableStateOf(false)
        private set
    var digits by mutableStateOf("")
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var favourites by mutableStateOf(prefs.favourites)
        private set
    var epg by mutableStateOf(Epg())
        private set

    /** Channels whose every link failed, and when; see [isFailed]. */
    private val failedAt = mutableStateMapOf<String, Long>()

    val tuneState: StateFlow<TuneState> get() = player.state

    /** The channel the banner describes. */
    val shown: Channel? get() = pending ?: current

    /** On screen (between onStart and onStop); nothing plays otherwise. */
    private var visible = false
    private var previousNumber = 0
    private var zapJob: Job? = null
    private var bannerJob: Job? = null
    private var digitJob: Job? = null
    private var toastJob: Job? = null
    private var guideIdleJob: Job? = null
    private var exitArmedUntil = 0L

    /** Keys whose press this controller acted on: their auto-repeats and release are eaten too. */
    private val eaten = HashSet<Int>()

    init {
        scope.launch {
            player.state.collect { s ->
                val ch = current ?: return@collect
                when (s) {
                    TuneState.Playing -> failedAt.remove(ch.id)
                    TuneState.Failed -> failedAt[ch.id] = SystemClock.elapsedRealtime()
                    else -> Unit
                }
            }
        }
    }

    fun applyLineup(received: Lineup) {
        // Numbers are how everything here finds a channel; never trust them to be unique and sorted.
        val l = received.copy(channels = received.channels.distinctBy { it.number }.sortedBy { it.number })
        val before = lineup.channels.associateBy { it.id }
        lineup = l
        // A channel with new links deserves another try.
        l.channels.forEach { if (before[it.id]?.streams != it.streams) failedAt.remove(it.id) }
        if (prefs.migrateFromIds(l)) favourites = prefs.favourites
        pending?.let { p -> pending = l.channels.firstOrNull { it.number == p.number } }
        val on = current
        if (on == null) {
            val last = prefs.lastChannelNumber
            val start = l.channels.firstOrNull { it.number == last }
            // The last channel may be missing from tonight's list and back tomorrow: a stand-in
            // must not take its place as the channel to open on.
            if (start != null) tune(start) else l.channels.firstOrNull()?.let { tune(it, remember = last == 0) }
            return
        }
        // Keep watching, on the refreshed links. A channel the server dropped plays on as it was.
        val fresh = l.channels.firstOrNull { it.number == on.number } ?: return
        current = fresh
        if (visible) player.refresh(fresh, audioLanguage(fresh))
    }

    fun applyEpg(e: Epg) {
        epg = e
    }

    /** The server announced a version; mention it once if it is new to this TV. */
    fun offerUpdate(r: AppRelease) {
        val known = updater.available != null
        updater.offer(r)
        if (!known && updater.available != null) showToast("A new Veed TV is ready: see the end of the channel list")
    }

    fun guideCategories(): List<GuideCategory> {
        val favs = lineup.channels.filter { it.number in favourites }
        val cats = lineup.categories
            .map { c -> GuideCategory(c.id, c.name, lineup.channels.filter { it.category == c.id }) }
            .filter { it.channels.isNotEmpty() }
        val update = listOfNotNull(updater.available?.let { GuideCategory(UPDATE, "Update Veed TV", emptyList()) })
        return listOf(GuideCategory(FAVOURITES, "Favourites", favs)) + cats + update
    }

    fun categoryName(ch: Channel): String = lineup.categories.firstOrNull { it.id == ch.category }?.name ?: ""

    fun digitPreview(): Channel? = digits.toIntOrNull()?.let { n -> lineup.channels.firstOrNull { it.number == n } }

    /** Every link of [ch] failed in the last quarter hour: shown dimmed, and zapping skips it. */
    fun isFailed(ch: Channel): Boolean =
        failedAt[ch.id]?.let { SystemClock.elapsedRealtime() - it < SKIP_FAILED_MS } == true

    fun onStart() {
        visible = true
        val ch = current ?: return
        if (player.state.value == TuneState.Idle) {
            player.play(ch, audioLanguage(ch))
            showBanner()
        }
    }

    /**
     * Leaving the app (HOME, or the TV switched off over HDMI-CEC) stops the stream, since the
     * Pi has no memory or bandwidth to spare, and drops anything half-done, so a channel number
     * typed just before cannot tune itself behind the launcher. It comes back on the picture.
     */
    fun onStop() {
        visible = false
        listOf(zapJob, bannerJob, digitJob, toastJob, guideIdleJob).forEach { it?.cancel() }
        pending = null
        digits = ""
        toast = null
        bannerVisible = false
        overlay = Overlay.None
        exitArmedUntil = 0
        eaten.clear()
        player.stop()
    }

    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        when (e.action) {
            KeyEvent.ACTION_UP -> return eaten.remove(code)
            KeyEvent.ACTION_DOWN -> Unit
            else -> return false
        }
        if (e.repeatCount == 0) {
            eaten.remove(code)
            Log.d(TAG, "key ${KeyEvent.keyCodeToString(code)} source=0x${e.source.toString(16)} device=${e.deviceId}")
        } else if (code in eaten) {
            // A held key: holding ▲▼ runs through the channels, everything else acts once, so
            // a long press on BACK cannot walk through "hide, arm exit, exit" in a blink.
            zapDirection(code)?.let { if (overlay == Overlay.None) zap(it) }
            return true
        }
        val handled = press(code)
        if (handled) eaten += code
        return handled
    }

    /** A key going down; true when it was acted on. */
    private fun press(code: Int): Boolean {
        digitOf(code)?.let {
            typeDigit(it)
            return true
        }
        if (overlay == Overlay.Guide) {
            touchGuide()
            return when (code) {
                // Like full screen, BACK first drops a half-typed channel number.
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (digits.isNotEmpty()) clearDigits() else closeGuide()
                    true
                }
                KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> {
                    clearDigits()
                    closeGuide()
                    true
                }
                // Everything else moves focus or clicks inside the list.
                else -> false
            }
        }
        zapDirection(code)?.let {
            zap(it)
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                digits.isNotEmpty() -> commitDigits()
                player.state.value == TuneState.Paused -> player.resumeLive()
                else -> openGuide()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> openGuide()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> showBanner()
            KeyEvent.KEYCODE_LAST_CHANNEL -> lineup.channels.firstOrNull { it.number == previousNumber }?.let(::tune)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY ->
                togglePause(code)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> back()
            else -> return false
        }
        return true
    }

    /** Watch [ch] from the channel list. */
    fun watch(ch: Channel) {
        closeGuide()
        clearDigits()
        if (ch.number != current?.number || player.state.value == TuneState.Failed) tune(ch) else showBanner()
    }

    fun toggleFavourite(ch: Channel) {
        val now = if (ch.number in favourites) favourites - ch.number else favourites + ch.number
        favourites = now
        prefs.favourites = now
        showToast(if (ch.number in now) "★  ${ch.name} added to Favourites" else "${ch.name} removed from Favourites")
    }

    fun closeGuide() {
        overlay = Overlay.None
        guideIdleJob?.cancel()
    }

    private fun openGuide() {
        overlay = Overlay.Guide
        bannerVisible = false
        touchGuide()
    }

    /** The list closes itself after a minute untouched, back to the picture. */
    private fun touchGuide() {
        guideIdleJob?.cancel()
        guideIdleJob = scope.launch {
            delay(GUIDE_IDLE_MS)
            closeGuide()
        }
    }

    private fun tune(ch: Channel, remember: Boolean = true) {
        zapJob?.cancel()
        pending = null
        clearDigits()
        current?.let { if (it.number != ch.number) previousNumber = it.number }
        current = ch
        if (remember) prefs.lastChannelNumber = ch.number
        if (visible) {
            player.play(ch, audioLanguage(ch))
            showBanner()
        }
    }

    /** Next/previous channel by number, wrapping around; recently dead channels are skipped. */
    private fun zap(dir: Int) {
        val list = lineup.channels
        if (list.isEmpty()) return
        clearDigits()
        val from = shown?.number ?: 0
        var i = list.indexOfFirst { it.number == from }
        if (i < 0) {
            // The channel on screen has left the lineup: carry on from where its number was.
            val above = list.indexOfFirst { it.number > from }.let { if (it < 0) list.size else it }
            i = if (dir > 0) above - 1 else above
        }
        for (step in list.indices) {
            i = (i + dir).mod(list.size)
            if (!isFailed(list[i])) break
        }
        pending = list[i]
        showBanner()
        zapJob?.cancel()
        zapJob = scope.launch {
            delay(ZAP_SETTLE_MS)
            pending?.let(::tune)
        }
    }

    private fun showBanner() {
        bannerVisible = true
        bannerJob?.cancel()
        bannerJob = scope.launch {
            delay(BANNER_MS)
            // Keep "Tuning…" on screen until there is something to watch.
            player.state.first { it !is TuneState.Tuning }
            if (pending == null) bannerVisible = false
        }
    }

    private fun typeDigit(d: Int) {
        digits = (digits + d).takeLast(3)
        digitJob?.cancel()
        if (digits.length == 3) {
            commitDigits()
        } else {
            digitJob = scope.launch {
                delay(DIGIT_WAIT_MS)
                commitDigits()
            }
        }
    }

    private fun commitDigits() {
        val n = digits.toIntOrNull()
        clearDigits()
        val ch = lineup.channels.firstOrNull { it.number == n }
        if (ch == null) {
            showToast("There is no channel $n")
            return
        }
        closeGuide()
        tune(ch)
    }

    private fun clearDigits() {
        digitJob?.cancel()
        digits = ""
    }

    private fun togglePause(code: Int) {
        val s = player.state.value
        when {
            s == TuneState.Paused && code != KeyEvent.KEYCODE_MEDIA_PAUSE -> player.resumeLive()
            (s == TuneState.Playing || s == TuneState.Buffering) && code != KeyEvent.KEYCODE_MEDIA_PLAY -> player.pause()
        }
    }

    private fun back() {
        when {
            digits.isNotEmpty() -> clearDigits()
            bannerVisible -> bannerVisible = false
            SystemClock.elapsedRealtime() < exitArmedUntil -> exit()
            else -> {
                exitArmedUntil = SystemClock.elapsedRealtime() + EXIT_WINDOW_MS
                showToast("Press BACK again to close Veed TV")
            }
        }
    }

    private fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(TOAST_MS)
            toast = null
        }
    }

    /**
     * The audio to prefer when a stream carries several: the language the server chose the
     * channel's category by (ISO 639-2, which the player maps to 639-1), else English.
     */
    private fun audioLanguage(ch: Channel): String =
        lineup.categories.firstOrNull { it.id == ch.category }?.language
            ?: if (ch.category == "malayalam") "ml" else "en"

    private fun zapDirection(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> +1
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> -1
        else -> null
    }

    private fun digitOf(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    companion object {
        const val TAG = "VeedTV"
        const val FAVOURITES = "favourites"
        const val UPDATE = "update"
        private const val ZAP_SETTLE_MS = 600L
        private const val BANNER_MS = 5_000L
        private const val GUIDE_IDLE_MS = 60_000L
        private const val DIGIT_WAIT_MS = 2_000L
        private const val TOAST_MS = 3_000L
        private const val EXIT_WINDOW_MS = 3_000L
        private const val SKIP_FAILED_MS = 15 * 60_000L
    }
}
