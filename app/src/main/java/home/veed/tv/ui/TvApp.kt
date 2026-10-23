package home.veed.tv.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import home.veed.tv.player.TuneState

/** The picture, with whatever the remote has asked for drawn on top. */
@OptIn(UnstableApi::class)
@Composable
fun TvApp(c: TvController, exo: ExoPlayer) {
    val state by c.tuneState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Keys belong to the app, never to the video view.
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = exo
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val shown = c.shown
        val guideOpen = c.overlay == Overlay.Guide
        if (shown == null) {
            CenterSpinner() // the lineup is still loading
        } else {
            when {
                state == TuneState.Failed && c.pending == null -> FailedCard(shown)
                state == TuneState.Paused -> PausedCard()
                (state is TuneState.Tuning || state == TuneState.Buffering) && !c.bannerVisible && !guideOpen ->
                    CenterSpinner()
            }
            if (c.bannerVisible && !guideOpen) {
                val nowSeconds = rememberEpochSeconds()
                val (onNow, upNext) = c.epg.nowNext(shown.id, nowSeconds)
                Banner(
                    ch = shown,
                    state = state,
                    zapping = c.pending != null,
                    favourite = shown.number in c.favourites,
                    category = c.categoryName(shown),
                    onNow = onNow,
                    upNext = upNext,
                    nowSeconds = nowSeconds,
                )
            }
        }
        if (guideOpen) Guide(c)
        if (c.digits.isNotEmpty()) NumberEntry(c.digits, c.digitPreview())
        c.toast?.let { Toast(it) }
    }
}
