package com.chimu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.FilterPart
import com.chimu.Ledger
import com.chimu.Txn
import com.chimu.TxnFilter
import com.chimu.TxnType
import com.chimu.ui.theme.ChimuIcons
import com.chimu.ui.theme.Lavender
import com.chimu.ui.theme.chimu
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** What the list is made of: a day heading, then that day's rows. */
private sealed interface Entry {
    data class Day(val date: String, val label: String) : Entry
    data class Item(val txn: Txn) : Entry
}

/** Fade-and-rise from below for the startup animation. A null [a] means this one does not animate. */
private fun Modifier.fadeRise(a: Animatable<Float, AnimationVector1D>?): Modifier =
    if (a == null) this else graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 22.dp.toPx()
    }

/**
 * Transactions (Dusk design): a small centred title, the search field, three filter chips (Month, Types, Class) that
 * share one row, then every entry grouped by day. Each chip opens its own menu, which drops down and fades its choices in. Tapping a row opens a small panel of details (one at a time). The + in the nav bar is the
 * add button, so there is none here.
 *
 * Startup: the title, search, chips and the first rows fade up one after another each time the tab is opened
 * (skipped when Android's animations are turned off).
 */
@Composable
fun TransactionsScreen(app: ChimuApp, onOpen: (String) -> Unit) {
    val txns by app.repo.txns.collectAsState()
    val today = remember { LocalDate.now() }

    // Filters survive rotation. Null / blank means "no filter".
    var month by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var typeSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val filter = TxnFilter(
        month = month,
        category = category,
        type = typeSlug?.let { TxnType.fromSlug(it) },
        search = search,
    )
    val rows = remember(txns, filter) { Ledger.filter(txns, filter) }
    val entries = remember(rows, today) { buildEntries(rows, today) }
    val filtering = filter != TxnFilter()

    val monthChoices = remember(txns) {
        listOf(Choice(null, "All months")) + Ledger.months(txns).map { Choice(it, Ledger.monthLabel(it)) }
    }
    val categoryChoices = remember { listOf(Choice(null, "All classes")) + Categories.all.map { Choice(it.slug, it.label) } }
    val typeChoices = remember { listOf(Choice(null, "All types")) + TxnType.entries.map { Choice(it.slug, it.label) } }
    // How many rows each choice would show, so the menus never promise an empty list.
    val monthCounts = remember(txns, filter) { Ledger.optionCounts(txns, filter, FilterPart.MONTH) }
    val typeCounts = remember(txns, filter) { Ledger.optionCounts(txns, filter, FilterPart.TYPE) }
    val categoryCounts = remember(txns, filter) { Ledger.optionCounts(txns, filter, FilterPart.CATEGORY) }

    // Startup animation: ten pieces, 60 ms apart.
    val reduce = rememberReduceMotion()
    val rise = remember { List(10) { Animatable(if (reduce) 1f else 0f) } }
    LaunchedEffect(Unit) {
        if (reduce) return@LaunchedEffect
        coroutineScope {
            rise.forEachIndexed { i, a -> launch { delay(i * 60L); a.animateTo(1f, tween(620, easing = EaseOutExpo)) } }
        }
    }

    Box(Modifier.fillMaxSize()) {
        TopWash(Modifier.align(Alignment.TopStart))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalNavInset.current),
        ) {
            item {
                Text(
                    "Transactions",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp).fadeRise(rise[0]),
                )
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search merchant or note") },
                    leadingIcon = { Icon(ChimuIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).fadeRise(rise[1]),
                )
            }
            item {
                // Three equal chips share the row, so none runs off the screen.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp).fadeRise(rise[2]),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChipMenu(
                        title = "Month",
                        valueText = month?.let { monthChip(it, today) } ?: "Month",
                        active = month != null,
                        choices = monthChoices,
                        counts = monthCounts,
                        selectedKey = month,
                        side = MenuSide.START,
                        onSelect = { month = it },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChipMenu(
                        title = "Type",
                        valueText = typeSlug?.let { TxnType.fromSlug(it).label } ?: "Types",
                        active = typeSlug != null,
                        choices = typeChoices,
                        counts = typeCounts,
                        selectedKey = typeSlug,
                        side = MenuSide.CENTER,
                        onSelect = { typeSlug = it },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChipMenu(
                        title = "Class",
                        valueText = category?.let { Categories.label(it) } ?: "Class",
                        active = category != null,
                        choices = categoryChoices,
                        counts = categoryCounts,
                        selectedKey = category,
                        side = MenuSide.END,
                        onSelect = { category = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (entries.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (filtering) "Nothing matches these filters." else "No transactions yet!",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (filtering) {
                            OutlinedButton(
                                onClick = { month = null; typeSlug = null; category = null; search = "" },
                                modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp),
                            ) { Text("Clear filters") }
                        }
                    }
                }
            } else {
                items(entries.size, key = { i ->
                    when (val e = entries[i]) {
                        is Entry.Day -> "day-${e.date}"
                        is Entry.Item -> e.txn.id
                    }
                }) { i ->
                    val anim = rise.getOrNull(3 + i)
                    when (val e = entries[i]) {
                        is Entry.Day -> Text(
                            e.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp).fadeRise(anim),
                        )
                        is Entry.Item -> {
                            val txn = e.txn
                            ExpandableTxnRow(
                                app = app,
                                txn = txn,
                                subtitle = subtitleFor(app, txn),
                                expanded = openId == txn.id,
                                onToggle = { openId = if (openId == txn.id) null else txn.id },
                                onOpen = { onOpen(txn.id) },
                                modifier = Modifier.padding(horizontal = 20.dp).fadeRise(anim),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The grey line under a row's name: class (the category) and wallet (the date is in the day heading). */
private fun subtitleFor(app: ChimuApp, txn: Txn): String = when (txn.type) {
    TxnType.TRANSFER -> "${app.repo.walletLabel(txn.walletId)} to ${app.repo.walletLabel(txn.toWalletId)}"
    else -> "${Categories.label(txn.category).ifBlank { "No class" }} · ${app.repo.walletLabel(txn.walletId)}"
}

private fun buildEntries(rows: List<Txn>, today: LocalDate): List<Entry> {
    val out = ArrayList<Entry>(rows.size + 8)
    var last: String? = null
    for (t in rows) {
        if (t.date != last) {
            out += Entry.Day(t.date, dayLabel(t.date, today))
            last = t.date
        }
        out += Entry.Item(t)
    }
    return out
}

/** `Today`, `Yesterday`, otherwise `Tue 24 Nov`. An unreadable date is shown as stored. */
private fun dayLabel(date: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }
}

/** `Nov` for a month of this year, `Nov 25` for an earlier one, so the chip stays short. */
private fun monthChip(month: String, today: LocalDate): String {
    val ym = runCatching { YearMonth.parse(month) }.getOrNull() ?: return month
    val pattern = if (ym.year == today.year) "MMM" else "MMM yy"
    return ym.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}

/** Which side of its chip a menu lines up with, so the menu stays on screen: Month on the left, Class on the right. */
private enum class MenuSide(val alignment: Alignment, val origin: TransformOrigin) {
    START(Alignment.TopStart, TransformOrigin(0.15f, 0f)),
    CENTER(Alignment.TopCenter, TransformOrigin(0.5f, 0f)),
    END(Alignment.TopEnd, TransformOrigin(0.85f, 0f)),
}

/** Room left round a menu so its shadow is not cut off by the popup window. */
private val MenuShadowRoom = 12.dp
private val MenuWidth = 224.dp
private val ChipHeight = 48.dp

/**
 * One filter, drawn as a pill with a chevron. Tapping it drops a menu down from the chip: the menu grows out of the
 * chip's edge while its choices fade in one after another, and the chevron turns over. The chosen value is written on the
 * chip and ticked in the menu (not just coloured), so the state never depends on colour (RULES 21). Each choice shows how
 * many rows it would give. Tapping outside, or Back, closes the menu. With Android's animations turned off it just appears.
 */
@Composable
private fun FilterChipMenu(
    title: String,
    valueText: String,
    active: Boolean,
    choices: List<Choice>,
    counts: Map<String?, Int>,
    selectedKey: String?,
    side: MenuSide,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = rememberReduceMotion()
    val state = remember { MutableTransitionState(false) }
    val open = state.targetState
    val density = LocalDensity.current
    val tint = Lavender.copy(alpha = 0.16f)
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = if (reduce) snap<Float>() else tween<Float>(320, easing = EaseOutExpo),
        label = "chipChevron",
    )

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ChipHeight)
                .clip(CircleShape)
                .background(if (active) tint else Color.Transparent)
                .border(
                    BorderStroke(if (active || open) 1.5.dp else 1.dp, if (active || open) MaterialTheme.colorScheme.primary else MaterialTheme.chimu.outlineStrong),
                    CircleShape,
                )
                .clickable(role = Role.DropdownList) { state.targetState = !state.targetState }
                .semantics { contentDescription = "$title filter: $valueText" }
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            Text(
                valueText,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                ChimuIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.chimu.outlineStrong,
                modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = chevron },
            )
        }

        // The popup stays composed until the closing animation has finished.
        if (state.currentState || state.targetState) {
            val sideRoom = with(density) { MenuShadowRoom.roundToPx() }
            val x = when (side) { MenuSide.START -> -sideRoom; MenuSide.CENTER -> 0; MenuSide.END -> sideRoom }
            val y = with(density) { (ChipHeight + 8.dp - MenuShadowRoom).roundToPx() }
            Popup(
                alignment = side.alignment,
                offset = IntOffset(x, y),
                onDismissRequest = { state.targetState = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = state,
                    enter = if (reduce) EnterTransition.None else
                        fadeIn(tween(180)) +
                            scaleIn(tween(340, easing = EaseOutExpo), initialScale = 0.94f, transformOrigin = side.origin) +
                            slideInVertically(tween(340, easing = EaseOutExpo)) { -it / 14 },
                    exit = if (reduce) ExitTransition.None else
                        fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 0.96f, transformOrigin = side.origin),
                ) {
                    FilterMenuCard(
                        choices = choices,
                        counts = counts,
                        selectedKey = selectedKey,
                        open = open,
                        reduce = reduce,
                        onPick = { key ->
                            state.targetState = false
                            onSelect(key)
                        },
                    )
                }
            }
        }
    }
}

/** The menu itself: one row per choice. Tall lists (Class) scroll inside the card; there is no heading, the chip above already names the filter. */
@Composable
private fun FilterMenuCard(
    choices: List<Choice>,
    counts: Map<String?, Int>,
    selectedKey: String?,
    open: Boolean,
    reduce: Boolean,
    onPick: (String?) -> Unit,
) {
    val tint = Lavender.copy(alpha = 0.16f)
    Box(Modifier.padding(MenuShadowRoom)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.width(MenuWidth),
        ) {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
            ) {
                choices.forEachIndexed { i, choice ->
                    val on = choice.key == selectedKey
                    // Each row fades in a little after the one above it (a short beat, then 28 ms apart).
                    val shown by animateFloatAsState(
                        targetValue = if (open) 1f else 0f,
                        animationSpec = if (reduce) snap<Float>() else tween<Float>(
                            durationMillis = if (open) 240 else 100,
                            delayMillis = if (open) 70 + i * 28 else 0,
                            easing = EaseOutExpo,
                        ),
                        label = "menuRow$i",
                    )
                    val count = if (choice.key == null) null else counts[choice.key]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .graphicsLayer {
                                alpha = shown
                                translationY = (1f - shown) * -4.dp.toPx()
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) tint else Color.Transparent)
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(choice.key) })
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            choice.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (count != null && count > 0) {
                            Text(
                                count.toString(),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            ChimuIcons.Check,
                            contentDescription = if (on) "Selected" else null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp).alpha(if (on) 1f else 0f),
                        )
                    }
                }
            }
        }
    }
}
