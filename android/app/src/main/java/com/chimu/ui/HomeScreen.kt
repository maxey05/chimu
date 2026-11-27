package com.chimu.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chimu.Balances
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.ExpenditureSeries
import com.chimu.Identity
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.SpendPeriod
import com.chimu.SyncStatus
import com.chimu.SyncText
import com.chimu.Txn
import com.chimu.TxnType
import com.chimu.ui.theme.ChimuIcons
import com.chimu.ui.theme.chimu
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** The startup animation plays once per app start. Coming back to Home from another tab shows it finished. */
private object HomeIntro {
    var played = false
}

/** Panel fade-and-rise from below: [a] runs 0 to 1. */
private fun Modifier.rise(a: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    alpha = a.value
    translationY = (1f - a.value) * 22.dp.toPx()
}

/**
 * Home (Dusk design): welcome header with the account menu, the balance card, the Expenditures chart (line or bars)
 * and the latest three transactions, which open a small panel of details when tapped. Budgets, Recurring and Goals live
 * on Dashboard, and Inbox, sync and auto-log setup on Profile, which the account menu opens.
 *
 * Startup: once the loading screen starts to fade, the header appears first, the other panels follow 0.2 s later one after another, the total counts up
 * and the chart line is drawn by a dot travelling along it. Android's "remove animations" setting turns all of it off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: ChimuApp,
    identity: Identity,
    attention: Int,
    onBalanceDetails: () -> Unit,
    onProfile: () -> Unit,
    onInbox: () -> Unit,
    onAutoLog: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenTxn: (String) -> Unit,
) {
    val txns by app.repo.txns.collectAsState()
    val inbox by app.repo.inbox.collectAsState()
    val wallets by app.repo.allWallets.collectAsState()
    val sync by app.sync.status.collectAsState()
    val today = remember { LocalDate.now() }
    val month = remember { YearMonth.now().toString() }
    val me = identity.userId

    val totals = remember(txns, month) { Ledger.monthTotals(txns, month) }
    val totalBalance = remember(wallets, txns) { Balances.forWallets(wallets, txns).values.sum() }
    val recent = remember(txns, me) {
        txns.filter { it.ownerId == me && !it.deleted && it.status != "inbox" && it.type != TxnType.TRANSFER }
            .sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.updatedAt })
            .take(3)
    }

    var periodOrdinal by rememberSaveable { mutableStateOf(SpendPeriod.YEAR.ordinal) }
    val period = SpendPeriod.entries[periodOrdinal]
    val series = remember(txns, me, period, today) { ExpenditureSeries.build(txns, me, period, today) }
    var picked by remember(period) { mutableStateOf<Int?>(null) }
    val selected = picked?.takeIf { series.points.getOrNull(it)?.spent != null } ?: series.currentIndex
    var showBars by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    // Animation state. Everything starts finished unless this is the first Home of the app run.
    val context = LocalContext.current
    val reduce = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val animate = remember { !HomeIntro.played && !reduce }
    val rise = remember { List(7) { Animatable(if (animate) 0f else 1f) } }
    val count = remember { Animatable(if (animate) 0f else 1f) }
    val draw = remember { Animatable(if (animate) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        // On a fresh start the loading screen is still on top. Wait until it begins to fade, so the entrance plays
        // as it clears instead of finishing out of sight behind it.
        snapshotFlow { LaunchGate.handedOver }.first { it }
        HomeIntro.played = true
        // header, balance card, chart, "Recent Transactions", then its three rows: the header leads by 0.2 s
        val delays = listOf(0L, 290L, 380L, 470L, 530L, 590L, 650L)
        kotlinx.coroutines.coroutineScope {
            rise.forEachIndexed { i, a -> launch { kotlinx.coroutines.delay(delays[i]); a.animateTo(1f, tween(720, easing = EaseOutExpo)) } }
            launch { kotlinx.coroutines.delay(300); count.animateTo(1f, tween(700, easing = EaseOutCubic)) }
            launch { kotlinx.coroutines.delay(580); draw.animateTo(1f, tween(1250, easing = EaseOutCubic)) }
        }
    }
    // Choosing another period, or switching back to the line, draws the line again. (Bars grow inside SpendChart.)
    var lastKey by remember { mutableStateOf(period to showBars) }
    LaunchedEffect(period, showBars) {
        val key = period to showBars
        if (key != lastKey) {
            lastKey = key
            if (!reduce && !showBars) {
                draw.snapTo(0f)
                draw.animateTo(1f, tween(1250, easing = EaseOutCubic))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    TopWash(Modifier.align(Alignment.TopStart))
        PullToRefreshBox(
            isRefreshing = sync.running,
            onRefresh = { app.sync.requestNow() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = LocalNavInset.current)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Header(
                    name = identity.displayName,
                    email = identity.email,
                    attention = attention,
                    inboxCount = inbox.size,
                    sync = sync,
                    onProfile = onProfile,
                    onInbox = onInbox,
                    onWallets = onBalanceDetails,
                    onAutoLog = onAutoLog,
                    onSyncNow = { app.sync.requestNow() },
                    onSignOut = { confirmSignOut = true },
                    modifier = Modifier.rise(rise[0]),
                )

                BalanceCard(
                    monthName = YearMonth.now().let { it.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + it.year },
                    balance = (totalBalance * count.value.toDouble()).toLong(), // Double: a Float cannot hold centavos above 16.7 million exactly
                    income = totals.income,
                    expenses = totals.spent,
                    reduceMotion = reduce,
                    onDetails = onBalanceDetails,
                    modifier = Modifier.rise(rise[1]),
                )

                Column(
                    modifier = Modifier
                        .rise(rise[2])
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(24.dp))
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Expenditures", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            buildAnnotatedString {
                                append(series.caption + " · ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                                    append(Money.format(series.total))
                                }
                            },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ChartToggle(showBars) { showBars = it }
                    }
                    PeriodTabs(period) { periodOrdinal = it.ordinal }
                    SpendChart(series, draw.value, selected, onSelect = { picked = it }, showBars = showBars)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.rise(rise[3]).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Recent Transactions", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(
                            Modifier
                                .heightIn(min = MinTouchHeight - 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClick = onSeeAll)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (recent.isEmpty()) {
                        Text(
                            "No transactions yet!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rise(rise[4]).padding(vertical = 12.dp),
                        )
                    }
                    recent.forEachIndexed { i, txn ->
                        val category = Categories.all.firstOrNull { it.slug == txn.category }?.label ?: txn.type.label
                        ExpandableTxnRow(
                            app = app,
                            txn = txn,
                            subtitle = "$category · ${shortDate(txn.date, today)}",
                            expanded = openId == txn.id,
                            onToggle = { openId = if (openId == txn.id) null else txn.id },
                            onOpen = { onOpenTxn(txn.id) },
                            modifier = Modifier.rise(rise[4 + i]),
                        )
                    }
                }
            }
        }
    }
    if (confirmSignOut) SignOutDialog(app, onDismiss = { confirmSignOut = false })
}

/**
 * "Welcome back," and the name, with the account pill on the right. The pill (avatar, a count of things waiting,
 * and a chevron) opens a small menu: who is signed in, Profile, Inbox with its number, Wallets, when it last synced
 * (tap to sync now), auto-log setup and Sign out.
 */
@Composable
private fun Header(
    name: String?,
    email: String?,
    attention: Int,
    inboxCount: Int,
    sync: SyncStatus,
    onProfile: () -> Unit,
    onInbox: () -> Unit,
    onWallets: () -> Unit,
    onAutoLog: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier,
) {
    val first = name?.takeIf { it.isNotBlank() }
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(260, easing = EaseOutExpo), label = "pillChevron")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Welcome back,", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (first != null) Text(first, style = MaterialTheme.typography.titleLarge)
        }
        val label = if (attention > 0) "Account menu, $attention ${if (attention == 1) "thing needs" else "things need"} your attention" else "Account menu"
        Box {
            Row(
                modifier = Modifier
                    .height(52.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.dp, if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), CircleShape)
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { contentDescription = label }
                    .padding(start = 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    ProfileAvatar(first, 42.dp)
                    if (attention > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-3).dp)
                                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(BorderStroke(2.dp, MaterialTheme.colorScheme.surface), CircleShape)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(attention.coerceAtMost(99).toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Icon(ChimuIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp).rotate(turn))
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.width(292.dp),
                offset = DpOffset(0.dp, 8.dp),
                shape = RoundedCornerShape(22.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAvatar(first, 44.dp)
                    Column(Modifier.weight(1f)) {
                        if (first != null) Text(first, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (email != null) Text(email, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                MenuRow(ChimuIcons.Profile, "Profile") { open = false; onProfile() }
                MenuRow(ChimuIcons.Inbox, "Inbox", badge = inboxCount.takeIf { it > 0 }?.toString()) { open = false; onInbox() }
                MenuRow(ChimuIcons.Wallet, "Wallets") { open = false; onWallets() }
                // Tapping syncs now and keeps the menu open, so the spinning icon and the new time are seen.
                MenuRow(
                    ChimuIcons.Sync,
                    "Last synced",
                    detail = if (sync.running) "Syncing..." else SyncText.lastSynced(sync.lastSyncedAt, System.currentTimeMillis()).removePrefix("Synced "),
                    spin = sync.running,
                ) { if (!sync.running) onSyncNow() }
                MenuRow(ChimuIcons.Camera, "Auto-log payments") { open = false; onAutoLog() }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                MenuRow(ChimuIcons.SignOut, "Sign out") { open = false; onSignOut() }
            }
        }
    }
}

/** One line of the account menu: icon, label, then an optional number or short note on the right. */
@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, badge: String? = null, detail: String? = null, spin: Boolean = false, onClick: () -> Unit) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinAngle")
    DropdownMenuItem(
        text = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (detail != null) Text(detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (badge != null) {
                    Box(
                        Modifier
                            .height(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(badge, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).then(if (spin) Modifier.rotate(angle) else Modifier))
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/** Two small buttons that switch the Expenditures chart between a line and bars; a white pill slides under the chosen one. */
@Composable
private fun ChartToggle(showBars: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (showBars) 44.dp else 0.dp, tween(320, easing = EaseOutExpo), label = "chartToggle")
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.chimu.detailWash)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = x)
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(10.dp)),
        )
        Row {
            ToggleButton(ChimuIcons.LineChart, "Line chart", !showBars) { onChange(false) }
            ToggleButton(ChimuIcons.BarChart, "Bar chart", showBars) { onChange(true) }
        }
    }
}

@Composable
private fun ToggleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = name,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.chimu.outlineStrong,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BalanceCard(
    monthName: String,
    balance: Long,
    income: Long,
    expenses: Long,
    reduceMotion: Boolean,
    onDetails: () -> Unit,
    modifier: Modifier,
) {
    val ink = MaterialTheme.chimu.onHero
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(0f) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(MaterialTheme.chimu.heroGradient))
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(monthName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Text("Total balance", fontSize = 16.sp, color = ink)
                Text(Money.format(balance), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = ink)
            }
            // The arrow slides out the way it points (up and right), then returns from the lower left.
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground)
                    .clickable(role = Role.Button) {
                        scope.launch {
                            if (reduceMotion) {
                                onDetails()
                            } else {
                                shift.animateTo(1f, tween(240, easing = FastOutLinearInEasing))
                                onDetails()
                                shift.snapTo(-1f)
                                shift.animateTo(0f, tween(320, easing = EaseOutExpo))
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ChimuIcons.ArrowUpRight,
                    contentDescription = "View balance details",
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            translationX = shift.value * 16.dp.toPx()
                            translationY = -shift.value * 16.dp.toPx()
                            alpha = 1f - abs(shift.value) * 0.9f
                        },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPanel("Income", income, ink, Modifier.weight(1f))
            GlassPanel("Expenses", expenses, ink, Modifier.weight(1f))
        }
    }
}

/** A frosted-looking panel: translucent white with a light edge (Compose has no blur of what is behind a view). */
@Composable
private fun GlassPanel(label: String, amount: Long, ink: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.5f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.65f)), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink)
        Text(Money.format(amount), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
    }
}

/** Week, Month, 6 Months, Year: a track with a white pill that slides to the chosen one. */
@Composable
private fun PeriodTabs(selected: SpendPeriod, onSelect: (SpendPeriod) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(4.dp),
    ) {
        val slot = maxWidth / SpendPeriod.entries.size
        val x by animateDpAsState(slot * selected.ordinal, tween(360, easing = EaseOutExpo), label = "pill")
        Box(
            Modifier
                .offset(x = x)
                .width(slot)
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(1.5.dp, primary), RoundedCornerShape(10.dp)),
        )
        Row {
            SpendPeriod.entries.forEach { p ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .selectable(selected = p == selected, role = Role.Tab, onClick = { onSelect(p) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        p.label,
                        fontSize = 14.sp,
                        maxLines = 1,
                        fontWeight = if (p == selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (p == selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
