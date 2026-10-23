package home.veed.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import home.veed.tv.data.Channel
import home.veed.tv.data.Programme
import home.veed.tv.player.TuneState

/** The strip along the bottom: number, logo, name, what is on now and next, the time. */
@Composable
fun Banner(
    ch: Channel,
    state: TuneState,
    zapping: Boolean,
    favourite: Boolean,
    category: String,
    onNow: Programme?,
    upNext: Programme?,
    nowSeconds: Long,
) {
    val clock = rememberClock()
    val timeOf = rememberTimeOfDay()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000), Color(0xF2000000))))
                .padding(start = 56.dp, end = 56.dp, top = 72.dp, bottom = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(TvColors.Accent)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) { Text("${ch.number}", style = TvType.BannerNumber) }
            Spacer(Modifier.width(24.dp))
            ChannelLogo(ch, Modifier.size(144.dp, 81.dp))
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ch.name + if (favourite) "  ★" else "",
                    style = TvType.BannerName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(category, style = TvType.Body.copy(color = TvColors.Dim))
                    Text("   ·   ", style = TvType.Body.copy(color = TvColors.Dim))
                    Status(if (zapping) TuneState.Tuning(1, 1) else state)
                }
                if (onNow != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("NOW  ", style = TvType.Small.copy(color = TvColors.Accent, fontWeight = FontWeight.Bold))
                        Text(
                            onNow.title,
                            style = TvType.Body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text("   ${timeOf(onNow.start)} – ${timeOf(onNow.stop)}", style = TvType.Small)
                    }
                    val done = (nowSeconds - onNow.start).toFloat() / (onNow.stop - onNow.start).coerceAtLeast(1)
                    ProgressLine(done.coerceIn(0f, 1f), Modifier.padding(top = 8.dp).width(420.dp))
                }
                if (upNext != null) {
                    Text(
                        "NEXT  ${timeOf(upNext.start)}   ${upNext.title}",
                        style = TvType.Small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(clock, style = TvType.Clock)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyCap("▲▼")
                    Text("  Change channel     ", style = TvType.Small)
                    KeyCap("OK")
                    Text("  All channels", style = TvType.Small)
                }
            }
        }
    }
}

@Composable
private fun Status(state: TuneState) {
    when (state) {
        TuneState.Playing -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(TvColors.Live))
            Text("  LIVE", style = TvType.Body.copy(fontWeight = FontWeight.Bold))
        }
        is TuneState.Tuning -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), color = TvColors.Accent, strokeWidth = 3.dp)
            Text(
                when {
                    state.reconnecting -> "  Reconnecting…"
                    state.attempt == 1 -> "  Tuning…"
                    else -> "  Trying another link (${state.attempt} of ${state.of})…"
                },
                style = TvType.Body,
            )
        }
        TuneState.Buffering -> Text("Loading…", style = TvType.Body)
        TuneState.Paused -> Text("Paused", style = TvType.Body)
        TuneState.Failed -> Text("Not working right now", style = TvType.Body.copy(color = TvColors.Warn))
        TuneState.Idle -> Unit
    }
}

@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(TvColors.Accent))
    }
}

@Composable
fun CenterSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(72.dp), color = TvColors.Accent, strokeWidth = 6.dp)
    }
}

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(TvColors.Card)
                .padding(horizontal = 64.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
fun FailedCard(ch: Channel) = CenterCard {
    Text(
        "${ch.name} is not working right now",
        style = TvType.Title.copy(fontSize = 32.sp),
        textAlign = TextAlign.Center,
    )
    Text(
        "It will come back by itself if it starts working again.",
        style = TvType.Body.copy(color = TvColors.Dim),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Press  ", style = TvType.Body.copy(color = TvColors.Dim))
        KeyCap("▲▼")
        Text("  for another channel, or  ", style = TvType.Body.copy(color = TvColors.Dim))
        KeyCap("OK")
        Text("  to see them all", style = TvType.Body.copy(color = TvColors.Dim))
    }
}

@Composable
fun PausedCard() = CenterCard {
    Text("Paused", style = TvType.Title)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Press  ", style = TvType.Body.copy(color = TvColors.Dim))
        KeyCap("OK")
        Text("  to go back to live TV", style = TvType.Body.copy(color = TvColors.Dim))
    }
}

/** Big digits in the corner while a channel number is typed, with the channel they lead to. */
@Composable
fun NumberEntry(digits: String, target: Channel?) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(TvColors.Card)
                .padding(horizontal = 36.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(digits, style = TvType.Digits)
            Text(target?.name ?: "…", style = TvType.Body)
        }
    }
}

@Composable
fun Toast(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            style = TvType.Body.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.clip(RoundedCornerShape(40.dp)).background(TvColors.Card)
                .padding(horizontal = 36.dp, vertical = 16.dp),
        )
    }
}
