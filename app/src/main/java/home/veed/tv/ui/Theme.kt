package home.veed.tv.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import home.veed.tv.data.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import android.graphics.Color as AColor

/** High contrast and large type: this is read from a sofa, by people who did not set it up. */
object TvColors {
    val Panel = Color(0xF00A1120)
    val Card = Color(0xEE111A2B)
    val Text = Color(0xFFF4F6FA)
    val Dim = Color(0xFFA9B4C6)
    val Accent = Color(0xFFFFB300)
    val Focus = Color(0xFFFFFFFF)
    val OnFocus = Color(0xFF0A1120)
    val Selected = Color(0xFF26324A)
    val Live = Color(0xFFFF4D4D)
    val Warn = Color(0xFFFF8A65)
    val LogoTile = Color(0xFFE9EDF3)
    val LogoTileDark = Color(0xFF243047)
}

object TvType {
    val Title = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TvColors.Text)
    val Body = TextStyle(fontSize = 24.sp, color = TvColors.Text)
    val Small = TextStyle(fontSize = 19.sp, color = TvColors.Dim)
    val Row = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = TvColors.Text)
    val RowNumber = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TvColors.Text)
    val Category = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Medium, color = TvColors.Text)
    val BannerName = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = TvColors.Text)
    val BannerNumber = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, color = TvColors.OnFocus)
    val Clock = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Medium, color = TvColors.Text)
    val Digits = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black, color = TvColors.Text)
}

/** Logo URL -> whether it needs a dark tile; worked out once per logo per run, on the main thread only. */
private val darkTiles = HashMap<String, Boolean>()

/**
 * The channel's logo on a tile, or its initials when there is no logo or it fails to load.
 * The tile is light, except for logos that are mostly white on a transparent background,
 * which would vanish on it (Star Movies, for one) — those get a dark tile.
 */
@Composable
fun ChannelLogo(ch: Channel, modifier: Modifier = Modifier) {
    var failed by remember(ch.logo) { mutableStateOf(ch.logo == null) }
    var dark by remember(ch.logo) { mutableStateOf(ch.logo?.let { darkTiles[it] } ?: false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (dark) TvColors.LogoTileDark else TvColors.LogoTile),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(
                initials(ch.name),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = if (dark) TvColors.Text else TvColors.OnFocus,
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(ch.logo).size(192, 108).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                onSuccess = { s ->
                    val url = ch.logo
                    if (url != null) {
                        val known = darkTiles[url]
                        if (known != null) {
                            dark = known
                        } else {
                            // Reading pixels is no job for the thread that draws the list.
                            scope.launch {
                                val needs = withContext(Dispatchers.Default) { needsDarkTile(s.result.drawable) }
                                darkTiles[url] = needs
                                dark = needs
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(5.dp),
            )
        }
    }
}

/** True for a mostly white logo with transparency around it. */
private fun needsDarkTile(drawable: Drawable): Boolean {
    val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return false
    // Bitmap.Config.HARDWARE does not exist before Android 8; touching it on Fire OS 6 would crash.
    if (bmp.isRecycled || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE)) return false
    val small = Bitmap.createScaledBitmap(bmp, 32, 18, true)
    val pixels = IntArray(small.width * small.height)
    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
    if (small !== bmp) small.recycle()
    var opaque = 0
    var light = 0
    for (p in pixels) {
        if (AColor.alpha(p) < 128) continue
        opaque++
        if (0.299 * AColor.red(p) + 0.587 * AColor.green(p) + 0.114 * AColor.blue(p) > 200) light++
    }
    return opaque in 1 until pixels.size * 9 / 10 && light > opaque * 6 / 10
}

private fun initials(name: String): String =
    name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

/** A remote-control button drawn on screen, for the hints. */
@Composable
fun KeyCap(label: String) {
    Box(
        Modifier
            .border(2.dp, TvColors.Dim, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) { Text(label, style = TvType.Small.copy(color = TvColors.Text, fontWeight = FontWeight.Bold)) }
}

/** Now, in epoch seconds, updated on the minute: enough to keep "what's on" current. */
@Composable
fun rememberEpochSeconds(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/** Formats epoch seconds as a time of day, in the TV's 12/24-hour setting. */
@Composable
fun rememberTimeOfDay(): (Long) -> String {
    val context = LocalContext.current
    return remember(context) {
        val format = DateFormat.getTimeFormat(context)
        val timeOf: (Long) -> String = { seconds -> format.format(Date(seconds * 1000)) }
        timeOf
    }
}

/** The time, in the TV's 12/24-hour setting, updated on the minute. */
@Composable
fun rememberClock(): String = rememberTimeOfDay()(rememberEpochSeconds())
