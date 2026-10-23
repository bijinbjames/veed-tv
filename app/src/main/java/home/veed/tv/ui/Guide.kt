package home.veed.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import home.veed.tv.BuildConfig
import home.veed.tv.data.Channel
import home.veed.tv.data.IptvOrg
import home.veed.tv.ui.TvController.Companion.FAVOURITES
import home.veed.tv.ui.TvController.Companion.UPDATE
import home.veed.tv.update.Updater
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The channel list: categories on the left, their channels beside them, the picture still
 * playing on the right. It opens on the channel being watched.
 */
@Composable
fun Guide(c: TvController) {
    val categories = remember(c.lineup, c.favourites, c.updater.available) { c.guideCategories() }
    val watching = c.current?.number
    val startCategory = remember {
        categories.firstOrNull { cat -> cat.id != FAVOURITES && cat.channels.any { it.number == watching } }?.id
            ?: categories.first().id
    }
    var selected by remember { mutableStateOf(startCategory) }
    // A lineup refresh can take away the category being looked at (no sports link worked last
    // night): fall back to the first, and put the focus there.
    val showing = categories.firstOrNull { it.id == selected } ?: categories.first()
    val categoryFocus = remember(categories) { categories.associate { it.id to FocusRequester() } }
    val watchingRow = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val clock = rememberClock()

    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Color(0xF5060B16),
                0.62f to Color(0xEB060B16),
                0.8f to Color(0x00060B16),
            ),
        ).onPreviewKeyEvent(::isHeldKey),
    ) {
        Column(Modifier.fillMaxHeight().width(940.dp).padding(start = 48.dp, top = 32.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Channels", style = TvType.Title)
                // How fresh the list is: at home and over Tailscale it is rebuilt nightly, so
                // "3 days ago" means this TV has lost touch with the server.
                listAge(c.lineup.generated)?.let { (label, stale) ->
                    // The fallback made without veed (IptvOrg) says so: its links were not tested.
                    val text = if (c.lineup.source == IptvOrg.SOURCE) "$label · links not tested" else label
                    Text("    $text", style = TvType.Small.copy(color = if (stale) TvColors.Warn else TvColors.Dim))
                }
                Spacer(Modifier.weight(1f))
                Text(clock, style = TvType.Clock)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(310.dp)) {
                    categories.forEach { cat ->
                        key(cat.id) {
                            CategoryItem(
                                cat = cat,
                                selected = cat.id == selected,
                                requester = categoryFocus.getValue(cat.id),
                                onFocused = { selected = cat.id },
                                // ▶ or OK on a category jumps into its channels.
                                onEnter = {
                                    if (cat.id == UPDATE) {
                                        c.updater.install()
                                        true
                                    } else {
                                        (if (cat.channels.any { it.number == watching }) watchingRow else firstRow).tryFocus()
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                if (showing.id == UPDATE) UpdatePanel(c.updater) else key(showing.id) {
                    ChannelColumn(
                        cat = showing,
                        c = c,
                        startIndex = if (showing.id == startCategory) showing.channels.indexOfFirst { it.number == watching } - 3 else 0,
                        categoryFocus = categoryFocus.getValue(showing.id),
                        watchingRow = watchingRow,
                        firstRow = firstRow,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (showing.id == UPDATE) UpdateHints() else Hints(inFavourites = showing.id == FAVOURITES)
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!watchingRow.tryFocus()) categoryFocus.getValue(showing.id).tryFocus()
    }
    LaunchedEffect(showing.id) {
        if (showing.id != selected) {
            selected = showing.id
            withFrameNanos { }
            categoryFocus.getValue(showing.id).tryFocus()
        }
    }
}

/** Moves the focus here if this is on screen; false when it could not. */
private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

/**
 * A held key's repeats. Only ▲▼ should repeat here, to run down a list: the repeats of
 * anything else land on whatever the first press moved the focus to, so a long OK on a
 * category would "click" a channel when let go, and a long ▶ would flip a favourite on and off.
 */
private fun isHeldKey(e: KeyEvent): Boolean =
    e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 &&
        e.key != Key.DirectionUp && e.key != Key.DirectionDown

/** "Updated today / yesterday / N days ago", and whether that is old enough to worry about. */
private fun listAge(generated: String): Pair<String, Boolean>? {
    // SimpleDateFormat rather than java.time, which Fire OS 6 (Android 7.1) does not have.
    val at = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(generated) }.getOrNull() ?: return null
    val days = (System.currentTimeMillis() - at.time) / 86_400_000L
    return when {
        days < 1 -> "Updated today" to false
        days == 1L -> "Updated yesterday" to false
        else -> "Updated $days days ago" to (days >= 3)
    }
}

@Composable
private fun CategoryItem(
    cat: GuideCategory,
    selected: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onEnter: () -> Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) TvColors.OnFocus else TvColors.Text
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused -> TvColors.Focus
                    selected -> TvColors.Selected
                    else -> Color.Transparent
                },
            )
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { e ->
                e.type == KeyEventType.KeyDown &&
                    (e.key == Key.DirectionRight || e.key == Key.DirectionCenter || e.key == Key.Enter) &&
                    onEnter()
            }
            .focusable()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (cat.id) {
            FAVOURITES -> Text("★  ", fontSize = 24.sp, color = if (focused) TvColors.OnFocus else TvColors.Accent)
            UPDATE -> Text("●  ", fontSize = 20.sp, color = if (focused) TvColors.OnFocus else TvColors.Accent)
        }
        Text(
            cat.name,
            style = TvType.Category.copy(color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (cat.id != UPDATE) {
            Text("${cat.channels.size}", style = TvType.Small.copy(color = if (focused) TvColors.OnFocus else TvColors.Dim))
        }
    }
}

@Composable
private fun ChannelColumn(
    cat: GuideCategory,
    c: TvController,
    startIndex: Int,
    categoryFocus: FocusRequester,
    watchingRow: FocusRequester,
    firstRow: FocusRequester,
) {
    if (cat.channels.isEmpty()) {
        Column(Modifier.padding(top = 18.dp, start = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("No favourites yet", style = TvType.Row)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Go to any channel in a list and press  ", style = TvType.Body.copy(color = TvColors.Dim))
                KeyCap("▶")
            }
        }
        return
    }
    val listState = remember { LazyListState(firstVisibleItemIndex = startIndex.coerceAtLeast(0)) }
    val nowSeconds = rememberEpochSeconds()
    val rowFocus = remember { HashMap<Int, FocusRequester>() }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(cat.channels, key = { _, ch -> ch.number }) { i, ch ->
            val watching = ch.number == c.current?.number
            var m: Modifier = Modifier.focusProperties { left = categoryFocus }
                .focusRequester(rowFocus.getOrPut(ch.number) { FocusRequester() })
            if (watching) m = m.focusRequester(watchingRow)
            if (i == 0) m = m.focusRequester(firstRow)
            ChannelRow(
                ch = ch,
                watching = watching,
                favourite = ch.number in c.favourites,
                failed = c.isFailed(ch),
                onNow = c.epg.nowNext(ch.id, nowSeconds).first?.title,
                modifier = m,
                onClick = { c.watch(ch) },
                onRight = {
                    // In Favourites, ▶ takes away the row that has the focus: hand the focus
                    // to the row below, else the one above, else the category, before it goes.
                    if (cat.id == FAVOURITES) {
                        val near = listOfNotNull(cat.channels.getOrNull(i + 1), cat.channels.getOrNull(i - 1))
                        if (near.none { rowFocus[it.number]?.tryFocus() == true }) categoryFocus.tryFocus()
                    }
                    c.toggleFavourite(ch)
                },
            )
        }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    watching: Boolean,
    favourite: Boolean,
    failed: Boolean,
    onNow: String?,
    modifier: Modifier,
    onClick: () -> Unit,
    onRight: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        focused -> TvColors.OnFocus
        failed -> TvColors.Dim
        else -> TvColors.Text
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) TvColors.Focus else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    onRight()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ch.number}", style = TvType.RowNumber.copy(color = fg), modifier = Modifier.width(74.dp))
        ChannelLogo(ch, Modifier.size(96.dp, 54.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = TvType.Row.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                watching -> Text(
                    "▶  Watching now" + (onNow?.let { "  ·  $it" } ?: ""),
                    style = TvType.Small.copy(
                        color = if (focused) TvColors.OnFocus else TvColors.Accent,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                failed -> Text(
                    "Not working right now",
                    style = TvType.Small.copy(color = if (focused) TvColors.OnFocus else TvColors.Warn),
                )
                onNow != null -> Text(
                    onNow,
                    style = TvType.Small.copy(color = if (focused) TvColors.OnFocus else TvColors.Dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (favourite) Text("★", fontSize = 30.sp, color = if (focused) TvColors.OnFocus else TvColors.Accent)
    }
}

/** The server has a newer version of the app: what it is, and OK to install it. */
@Composable
private fun UpdatePanel(u: Updater) {
    val r = u.available ?: return
    Column(Modifier.padding(top = 18.dp, start = 8.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Veed TV ${r.versionName} is ready", style = TvType.Row)
        if (r.notes.isNotBlank()) Text(r.notes, style = TvType.Body)
        Text(
            "This TV has version ${BuildConfig.VERSION_NAME}. Channels and favourites stay as they are.",
            style = TvType.Body.copy(color = TvColors.Dim),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Press  ", style = TvType.Body)
            KeyCap("OK")
            Text("  to install it", style = TvType.Body)
        }
        u.status?.let { Text(it, style = TvType.Body.copy(color = TvColors.Accent, fontWeight = FontWeight.Bold)) }
    }
}

@Composable
private fun UpdateHints() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KeyCap("OK")
        Text("  Install        ", style = TvType.Small)
        KeyCap("BACK")
        Text("  Close", style = TvType.Small)
    }
}

@Composable
private fun Hints(inFavourites: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KeyCap("OK")
        Text("  Watch        ", style = TvType.Small)
        KeyCap("▶")
        Text(if (inFavourites) "  Remove from Favourites        " else "  Add to Favourites        ", style = TvType.Small)
        KeyCap("◀")
        Text("  Categories        ", style = TvType.Small)
        KeyCap("BACK")
        Text("  Close", style = TvType.Small)
    }
}
