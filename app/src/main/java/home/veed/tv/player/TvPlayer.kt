package home.veed.tv.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import home.veed.tv.data.Channel
import home.veed.tv.data.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the viewer should be told about the channel on screen. */
sealed interface TuneState {
    data object Idle : TuneState

    /** Starting link [attempt] of [of]; [reconnecting] when a link that was playing dropped. */
    data class Tuning(val attempt: Int, val of: Int, val reconnecting: Boolean = false) : TuneState
    data object Playing : TuneState
    data object Buffering : TuneState
    data object Paused : TuneState

    /** Every link failed. The player keeps retrying quietly while the channel stays on screen. */
    data object Failed : TuneState
}

/**
 * ExoPlayer, set up for live TV on a 2 GB Raspberry Pi: short buffers for quick channel
 * changes, and patience with the flaky servers these free streams live on. A link that was
 * playing gets reconnected; a dead one hands over to the channel's next link; a channel with
 * no working link is retried in the background, so the picture comes back on its own.
 */
@OptIn(UnstableApi::class)
class TvPlayer(context: Context, private val scope: CoroutineScope) {
    private val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setMaxVideoSize(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true),
        )
    }

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(12_000, 30_000, 1_000, 2_500)
                .build(),
        )
        .build()

    private val _state = MutableStateFlow<TuneState>(TuneState.Idle)
    val state: StateFlow<TuneState> = _state

    private var channel: Channel? = null

    /** The links being tried, normally [channel]'s; [attempt] indexes into it. */
    private var links: List<Stream> = emptyList()
    private var attempt = 0

    /** This link has shown a picture, so a later error is worth reconnecting for. */
    private var hadPicture = false
    private var reconnects = 0
    private var restarts = 0

    /** Retrying a failed channel without telling the viewer each time. */
    private var quietRetry = false
    private var failures = 0
    private var watchdog: Job? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (_state.value != TuneState.Paused) {
                        watchdog?.cancel()
                        hadPicture = true
                        quietRetry = false
                        reconnects = 0
                        restarts = 0
                        failures = 0
                        _state.value = TuneState.Playing
                    }
                    Player.STATE_BUFFERING -> if (_state.value == TuneState.Playing) {
                        _state.value = TuneState.Buffering
                        arm(REBUFFER_TIMEOUT_MS) { restartOrNext("stalled") }
                    }
                    Player.STATE_ENDED -> restartOrNext("stream ended")
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "${channel?.name} link ${attempt + 1}: ${error.errorCodeName} ${error.cause?.message}")
                when {
                    // Reconnecting would also start playing: wait until the viewer resumes.
                    _state.value == TuneState.Paused -> Unit
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    hadPicture && reconnects < MAX_RECONNECTS -> {
                        reconnects++
                        _state.value = TuneState.Tuning(attempt + 1, links.size, reconnecting = true)
                        arm(RECONNECT_STEP_MS * reconnects) { start() }
                    }
                    else -> next(error.errorCodeName)
                }
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.i(TAG, "video decoder $decoderName")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                Log.i(
                    TAG,
                    "video ${format.width}x${format.height} ${format.sampleMimeType} " +
                        "${format.frameRate}fps ${format.bitrate / 1000}kbps",
                )
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                Log.i(TAG, "dropped $droppedFrames frames in ${elapsedMs}ms")
            }
        })
        // Decoder health every 10 s, so the Pi's real frame rate can be read from logcat.
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (_state.value != TuneState.Playing) continue
                exo.videoDecoderCounters?.let { c ->
                    c.ensureUpdated()
                    Log.i(
                        TAG,
                        "frames rendered=${c.renderedOutputBufferCount} dropped=${c.droppedBufferCount} " +
                            "skipped=${c.skippedOutputBufferCount} worstRun=${c.maxConsecutiveDroppedBufferCount}",
                    )
                }
            }
        }
    }

    /** Tunes [ch], preferring audio in [audioLanguage] (an ISO 639-1 code) when a stream has several. */
    fun play(ch: Channel, audioLanguage: String) {
        channel = ch
        links = ch.streams
        attempt = 0
        failures = 0
        quietRetry = false
        hadPicture = false
        reconnects = 0
        restarts = 0
        trackSelector.setParameters(trackSelector.buildUponParameters().setPreferredAudioLanguage(audioLanguage))
        _state.value = TuneState.Tuning(1, links.size)
        start()
    }

    /**
     * The lineup was refreshed while [ch] is on screen. The link in use is kept if it has shown
     * a picture or the server still lists it, with the fresh links behind it for when it drops;
     * otherwise, and for a channel that has failed, it starts again on the fresh links rather
     * than retrying ones the server has dropped.
     */
    fun refresh(ch: Channel, audioLanguage: String) {
        val old = channel ?: return
        val inUse = links.getOrNull(attempt)
        when {
            ch.streams == old.streams || _state.value == TuneState.Idle -> channel = ch
            inUse != null && _state.value != TuneState.Failed && (hadPicture || ch.streams.any { it.url == inUse.url }) -> {
                channel = ch
                links = listOf(inUse) + ch.streams.filter { it.url != inUse.url }
                attempt = 0
            }
            else -> play(ch, audioLanguage)
        }
    }

    fun stop() {
        watchdog?.cancel()
        exo.stop()
        _state.value = TuneState.Idle
    }

    fun pause() {
        if (_state.value == TuneState.Playing || _state.value == TuneState.Buffering) {
            watchdog?.cancel()
            exo.pause()
            _state.value = TuneState.Paused
        }
    }

    /** Live TV resumes at the live edge, not where it was paused. */
    fun resumeLive() {
        if (_state.value != TuneState.Paused) return
        _state.value = TuneState.Tuning(attempt + 1, links.size)
        if (exo.playbackState == Player.STATE_IDLE) {
            start() // the stream dropped during the pause
        } else {
            exo.seekToDefaultPosition()
            exo.play()
            arm(TUNE_TIMEOUT_MS) { next("no picture after pause") }
        }
    }

    fun release() {
        watchdog?.cancel()
        exo.release()
    }

    private fun start() {
        val ch = channel ?: return
        val s = links.getOrNull(attempt) ?: return fail()
        Log.i(TAG, "tune ${ch.number} ${ch.name} link ${attempt + 1}/${links.size} quiet=$quietRetry ${s.url}")
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(s.userAgent ?: DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
            .setDefaultRequestProperties(s.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
        val item = MediaItem.Builder()
            .setUri(s.url)
            .setMimeType(if (s.type == "dash") MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
            .build()
        exo.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        exo.prepare()
        exo.play()
        arm(TUNE_TIMEOUT_MS) { next("no picture after ${TUNE_TIMEOUT_MS / 1000}s") }
    }

    private fun next(why: String) {
        Log.i(TAG, "${channel?.name} link ${attempt + 1} given up: $why")
        attempt++
        hadPicture = false
        reconnects = 0
        restarts = 0
        if (attempt < links.size) {
            if (!quietRetry) _state.value = TuneState.Tuning(attempt + 1, links.size)
            start()
        } else {
            fail()
        }
    }

    /** A stall gets one fresh start on the same link before moving on. */
    private fun restartOrNext(why: String) {
        if (restarts++ < 1) start() else next(why)
    }

    /** Black screen and a message, then keep trying every link again, backing off up to 5 minutes. */
    private fun fail() {
        exo.stop()
        exo.clearMediaItems()
        _state.value = TuneState.Failed
        val wait = QUIET_RETRY_MS.getOrElse(failures) { QUIET_RETRY_MS.last() }
        failures++
        Log.i(TAG, "${channel?.name}: no working link; quiet retry in ${wait / 1000}s")
        arm(wait) {
            links = channel?.streams.orEmpty()
            attempt = 0
            hadPicture = false
            reconnects = 0
            restarts = 0
            quietRetry = true
            start()
        }
    }

    private fun arm(ms: Long, action: () -> Unit) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(ms)
            action()
        }
    }

    companion object {
        const val TAG = "VeedTV"

        /** What the streams were tested with (VLC); some servers refuse unknown players. */
        const val DEFAULT_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
        const val TUNE_TIMEOUT_MS = 12_000L
        const val REBUFFER_TIMEOUT_MS = 20_000L
        const val MAX_RECONNECTS = 3
        const val RECONNECT_STEP_MS = 2_000L
        val QUIET_RETRY_MS = longArrayOf(30_000, 60_000, 120_000, 300_000)

        // The TV has no hardware video decoder, but its software one measured 1080p25 at
        // 5 Mbit/s with no dropped frames (2026-10-20), so the full picture is allowed.
        const val MAX_VIDEO_WIDTH = 1920
        const val MAX_VIDEO_HEIGHT = 1080
    }
}
