package home.veed.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import home.veed.tv.data.Fetched
import home.veed.tv.data.Lineup
import home.veed.tv.data.LineupRepository
import home.veed.tv.data.Prefs
import home.veed.tv.player.TvPlayer
import home.veed.tv.ui.TvApp
import home.veed.tv.ui.TvController
import home.veed.tv.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var player: TvPlayer
    private lateinit var repo: LineupRepository
    private lateinit var updater: Updater
    private lateinit var controller: TvController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player = TvPlayer(this, lifecycleScope)
        repo = LineupRepository(this, BuildConfig.SERVER_URLS.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        updater = Updater(this, repo, lifecycleScope)
        controller = TvController(Prefs(this), player, updater, lifecycleScope, exit = ::finish)
        lifecycleScope.launch {
            // What is on the box first, so the picture starts without waiting for the server.
            controller.applyLineup(withContext(Dispatchers.IO) { repo.loadLocal() })
            controller.applyEpg(withContext(Dispatchers.IO) { repo.loadLocalEpg() })
            // Then the server, each time the app comes on screen and regularly while it stays there.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { keepFresh(LINEUP_EVERY_MS, ::fetchLineup, controller::applyLineup) }
                launch { keepFresh(RELEASE_EVERY_MS, repo::fetchRelease, controller::offerUpdate) }
                keepFresh(EPG_EVERY_MS, repo::fetchEpg, controller::applyEpg)
            }
        }
        setContent {
            // Laid out for a 1280-dp-wide screen, which is what this Pi is (1920 px at density
            // 240). Most Android TVs are 960 dp wide (density 320) and would show everything a
            // third larger and clipped, so scale the layout to whatever screen this is.
            val design = Density(resources.displayMetrics.widthPixels / DESIGN_WIDTH_DP, fontScale = 1f)
            CompositionLocalProvider(LocalDensity provides design) { TvApp(controller, player.exo) }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        updater.release()
        player.release()
        super.onDestroy()
    }

    /** veed's list; failing that, once veed has been out of reach for a day, iptv-org's untested one. */
    private suspend fun fetchLineup(): Fetched<Lineup> {
        val fromVeed = repo.fetchLineup()
        return if (fromVeed == Fetched.Failed) repo.fallbackFromIptvOrg(controller.lineup) else fromVeed
    }

    /**
     * Fetches now and every [every] after. A failure (veed rebooting, the Wi-Fi not up yet) is
     * retried after 1, 2, 4… minutes instead of waiting out the whole interval.
     */
    private suspend fun <T> keepFresh(every: Long, fetch: suspend () -> Fetched<T>, apply: (T) -> Unit) {
        var retry = RETRY_FIRST_MS
        while (true) {
            val result = fetch()
            if (result is Fetched.Fresh) {
                apply(result.value)
                result.applied()
            }
            if (result == Fetched.Failed) {
                delay(retry)
                retry = (retry * 2).coerceAtMost(every)
            } else {
                retry = RETRY_FIRST_MS
                delay(every)
            }
        }
    }

    // Every remote button comes through here first; see TvController.onKey. Lint flags the super
    // call as library-internal, but overriding dispatchKeyEvent is how a TV app sees every key.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller.onKey(event) || super.dispatchKeyEvent(event)

    private companion object {
        // The server rebuilds the lineup once a night and the guide once a day. Asking is cheap
        // (an unchanged file is a 304 with no body), so a TV left on picks a rebuild up within the hour.
        const val LINEUP_EVERY_MS = 60 * 60 * 1000L
        const val EPG_EVERY_MS = 30 * 60 * 1000L
        const val RELEASE_EVERY_MS = 6 * 60 * 60 * 1000L
        const val RETRY_FIRST_MS = 60 * 1000L
        const val DESIGN_WIDTH_DP = 1280f
    }
}
