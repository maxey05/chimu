package com.chimu.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chimu.ChimuApp
import com.chimu.Identity
import com.chimu.Supa
import com.chimu.ui.theme.chimu
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.math.abs
import kotlinx.coroutines.launch
import com.chimu.SampleData

/** Route id for "add a new one" on the entry and wallet forms. */
const val NEW_ID = "new"

private const val HOME = "home"
private const val TRANSACTIONS = "transactions"
private const val GROUP = "group"
private const val GROUP_CREATE = "group_create"
private const val GROUP_JOIN = "group_join"
private const val GROUP_TXN = "group_txn"
private const val DASHBOARD = "dashboard"
private const val PROFILE = "profile"
private const val WALLETS = "wallets"
private const val BUDGETS = "budgets"
private const val INBOX = "inbox"
private const val AUTO_LOG = "autolog"
private const val TXN_FORM = "txn"
private const val WALLET_FORM = "wallet"
private const val BUDGET_FORM = "budget"
private const val RECURRING = "recurring"
private const val RECURRING_FORM = "recurring_item"
private const val GOALS = "goals"
private const val GOAL_FORM = "goal"
private const val GOAL_ADD = "goal_add"

private val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** The route each tab lives at. */
private fun NavTab.routeName(): String = when (this) {
    NavTab.HOME -> HOME
    NavTab.TRANSACTIONS -> TRANSACTIONS
    NavTab.DASHBOARD -> DASHBOARD
    NavTab.GROUP -> GROUP
}

private val TAB_ROUTES = setOf(HOME, TRANSACTIONS, DASHBOARD, GROUP)

/**
 * A tab's destination. Moving between two tabs has no built-in fade, because [MainShell] slides the screen itself (a swipe
 * or a tap on the bar). Opening or leaving a form still uses the normal transitions.
 */
private fun NavGraphBuilder.tab(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
    composable(
        route = route,
        enterTransition = { if (initialState.destination.route in TAB_ROUTES) EnterTransition.None else null },
        exitTransition = { if (targetState.destination.route in TAB_ROUTES) ExitTransition.None else null },
        popEnterTransition = { if (initialState.destination.route in TAB_ROUTES) EnterTransition.None else null },
        popExitTransition = { if (targetState.destination.route in TAB_ROUTES) ExitTransition.None else null },
    ) { entry -> content(entry) }
}

/**
 * Decides what the person sees: setup help, the sign-in screen, or the app, with the animated loading screen
 * laid over it when the app starts.
 *
 * Being signed in comes from [com.chimu.Account], which is remembered on the phone. That is what
 * lets the app open straight into the user's data with no network (it is offline-first), while
 * supabase-kt restores and refreshes the real session in the background.
 *
 * The loading screen ([LaunchSplash]) sits on top while the screen underneath gets ready, then fades away over it.
 * "Ready" means what the person would otherwise have waited for with a spinner: their data open, or the sign-in
 * screen decided on.
 */
@Composable
fun ChimuRoot() {
    if (!Supa.isConfigured) {
        // Developer setup help: nothing to wait for, so no loading screen.
        LaunchedEffect(Unit) { LaunchGate.handOver() }
        SetupNeededScreen()
        return
    }

    val app = LocalContext.current.applicationContext as ChimuApp
    val status by Supa.client.auth.sessionStatus.collectAsState()
    val identity by app.account.identity.collectAsState()
    val openOwner by app.repo.openOwner.collectAsState()

    // A fresh sign-in, sign-up or restored session: remember who it is.
    LaunchedEffect(status) {
        (status as? SessionStatus.Authenticated)?.let { app.account.signedIn(it.session) }
    }

    val signedIn = identity
    Box(Modifier.fillMaxSize()) {
        when {
            signedIn != null -> MainShell(app, signedIn)
            status is SessionStatus.Initializing -> Splash()
            else -> AuthScreen()
        }
        val ready = if (signedIn != null) openOwner == signedIn.userId else status !is SessionStatus.Initializing
        LaunchSplash(ready)
    }
}

@Composable
private fun MainShell(app: ChimuApp, identity: Identity) {
    val openOwner by app.repo.openOwner.collectAsState()
    LaunchedEffect(identity.userId) {
        app.repo.open(identity.userId)
        app.sync.start(identity.userId) // shows what the phone remembers, then syncs once
        SampleData.loadOnceAfterSignIn(app) // TEMPORARY: debug builds only, see SampleData.kt
    }
    if (openOwner != identity.userId) {
        Splash()
        return
    }

    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val selectedTab = when (route) {
        HOME -> NavTab.HOME
        TRANSACTIONS -> NavTab.TRANSACTIONS
        DASHBOARD -> NavTab.DASHBOARD
        GROUP -> NavTab.GROUP
        else -> null
    }
    val onTab = selectedTab != null

    // The + button's two jobs. The scanner lives here (not on Home) so it works from every tab.
    val scanner = rememberReceiptScanner(app)
    var showAdd by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(scanner.busy) { if (scanner.busy) snackbars.showSnackbar("Saving the photo...") }
    LaunchedEffect(scanner.message) { scanner.message?.let { snackbars.showSnackbar(it) } }
    LaunchedEffect(scanner.error) { scanner.error?.let { snackbars.showSnackbar(it) } }

    // Things waiting for the person: Inbox items, and a failed sync (RULES 20: errors stay visible).
    val inbox by app.repo.inbox.collectAsState()
    val sync by app.sync.status.collectAsState()
    val attention = inbox.size + if (sync.error != null) 1 else 0

    // Changing tab, by a tap on the bar or a swipe (v5 design; four tabs since v6). `shift` is how far the screen is drawn from where it rests, in px.
    // A swipe follows the finger; letting go past 72 dp (or with a flick) slides the screen out, opens the next tab and
    // slides that in from the other side. A tap does the slide-in only. With Android's animations off it just switches.
    val reduceMotion = rememberReduceMotion()
    val uiDensity = LocalDensity.current
    val scope = rememberCoroutineScope()
    var shift by remember { mutableFloatStateOf(0f) }
    var switching by remember { mutableStateOf(false) }

    fun slideTo(tab: NavTab, swiped: Boolean) {
        val from = selectedTab ?: return
        if (tab == from || switching) return
        val step = if (tab.ordinal > from.ordinal) 1 else -1
        scope.launch {
            switching = true
            try {
                if (reduceMotion) {
                    nav.goToTab(tab.routeName())
                    return@launch
                }
                if (swiped) {
                    val out = -step * with(uiDensity) { 150.dp.toPx() }
                    animate(shift, out, animationSpec = tween(200, easing = FastOutLinearInEasing)) { v, _ -> shift = v }
                }
                nav.goToTab(tab.routeName())
                shift = step * with(uiDensity) { (if (swiped) 110.dp else 36.dp).toPx() }
                animate(shift, 0f, animationSpec = tween(if (swiped) 460 else 420, easing = EaseOutExpo)) { v, _ -> shift = v }
            } finally {
                shift = 0f
                switching = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars, modifier = Modifier.padding(bottom = if (onTab) NavInset - 24.dp else 0.dp)) },
    ) { padding ->
        // Home and Transactions have a lavender wash at the top. The screen is padded below the status bar, so the wash
        // used to start under it and left a white strip across the top. This strip carries the wash's top colour up behind
        // the status bar, so the two meet with no seam. Other screens keep a white top; it fades between the two (v7).
        val washTop = MaterialTheme.chimu.topWash[0]
        val statusStrip by animateColorAsState(
            targetValue = if (selectedTab == NavTab.HOME || selectedTab == NavTab.TRANSACTIONS) washTop else Color.White,
            animationSpec = tween(if (reduceMotion) 0 else 300),
            label = "statusStrip",
        )
        Box(Modifier.fillMaxWidth().height(padding.calculateTopPadding()).background(statusStrip))
        Box(Modifier.fillMaxSize().padding(padding)) {
        // The outer box stays put and watches for swipes; only the inner one moves, so the drag is measured against a fixed frame.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(selectedTab, switching) {
                    val tab = selectedTab ?: return@pointerInput
                    if (switching) return@pointerInput
                    val scale = density // px per dp, from the PointerInputScope
                    var total = 0f
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            total = 0f
                            tracker.resetTracking()
                        },
                        onDragCancel = {
                            scope.launch { animate(shift, 0f, animationSpec = tween(380, easing = EaseOutExpo)) { v, _ -> shift = v } }
                        },
                        onDragEnd = {
                            val target = SwipeNav.target(tab, total / scale, tracker.calculateVelocity().x / scale)
                            if (target != null) {
                                slideTo(target, swiped = true)
                            } else {
                                scope.launch { animate(shift, 0f, animationSpec = tween(380, easing = EaseOutExpo)) { v, _ -> shift = v } }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            total += dragAmount
                            shift = SwipeNav.shown(tab, total / scale) * scale
                        },
                    )
                },
        ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = shift
                    alpha = 1f - (abs(shift) / (170.dp.toPx())).coerceIn(0f, 1f)
                },
        ) {
        CompositionLocalProvider(LocalNavInset provides (if (onTab) NavInset else 0.dp)) {
        NavHost(nav, startDestination = HOME) {
            tab(HOME) {
                HomeScreen(
                    app,
                    identity,
                    attention = attention,
                    onBalanceDetails = { nav.navigate(WALLETS) },
                    onProfile = { nav.navigate(PROFILE) },
                    onInbox = { nav.navigate(INBOX) },
                    onAutoLog = { nav.navigate(AUTO_LOG) },
                    onSeeAll = { nav.goToTab(TRANSACTIONS) },
                    onOpenTxn = { id -> nav.navigate("$TXN_FORM/$id") },
                )
            }
            tab(TRANSACTIONS) {
                TransactionsScreen(
                    app,
                    onOpen = { id -> nav.navigate("$TXN_FORM/$id") },
                )
            }
            tab(GROUP) {
                GroupScreen(
                    app,
                    onCreate = { nav.navigate(GROUP_CREATE) },
                    onJoin = { nav.navigate(GROUP_JOIN) },
                    onOpenOwn = { id -> nav.navigate("$TXN_FORM/$id") },
                    onOpenShared = { id -> nav.navigate("$GROUP_TXN/$id") },
                )
            }
            composable(GROUP_CREATE) { CreateGroupScreen(app, onClose = { nav.popBackStack() }) }
            composable(GROUP_JOIN) { JoinGroupScreen(app, onClose = { nav.popBackStack() }) }
            composable(
                route = "$GROUP_TXN/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                GroupTxnScreen(app, entry.arguments?.getString("id") ?: "", onClose = { nav.popBackStack() })
            }
            tab(DASHBOARD) {
                DashboardScreen(
                    app,
                    identity,
                    onBudgets = { nav.navigate(BUDGETS) },
                    onRecurring = { nav.navigate(RECURRING) },
                    onGoals = { nav.navigate(GOALS) },
                )
            }
            // Profile is not a tab: it opens from Home's account menu, slides in like any other screen and has a back arrow.
            composable(PROFILE) {
                ProfileScreen(
                    app,
                    identity,
                    onWallets = { nav.navigate(WALLETS) },
                    onInbox = { nav.navigate(INBOX) },
                    onAutoLog = { nav.navigate(AUTO_LOG) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(INBOX) {
                InboxScreen(
                    app,
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("$TXN_FORM/$id") },
                    onAutoLog = { nav.navigate(AUTO_LOG) },
                )
            }
            composable(AUTO_LOG) { AutoLogScreen(app, onBack = { nav.popBackStack() }) }

            composable(
                route = "$TXN_FORM/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                TxnFormScreen(app, entry.arguments?.getString("id") ?: NEW_ID, onClose = { nav.popBackStack() })
            }
            composable(WALLETS) {
                WalletsScreen(
                    app,
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("$WALLET_FORM/$id") },
                )
            }
            composable(
                route = "$WALLET_FORM/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                WalletFormScreen(app, entry.arguments?.getString("id") ?: NEW_ID, onClose = { nav.popBackStack() })
            }
            composable(BUDGETS) {
                BudgetsScreen(
                    app,
                    identity,
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("$BUDGET_FORM/$id") },
                )
            }
            composable(
                route = "$BUDGET_FORM/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                BudgetFormScreen(app, identity, entry.arguments?.getString("id") ?: NEW_ID, onClose = { nav.popBackStack() })
            }
            composable(RECURRING) {
                RecurringScreen(
                    app,
                    identity,
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("$RECURRING_FORM/$id") },
                )
            }
            composable(
                route = "$RECURRING_FORM/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                RecurringFormScreen(app, identity, entry.arguments?.getString("id") ?: NEW_ID, onClose = { nav.popBackStack() })
            }
            composable(GOALS) {
                GoalsScreen(
                    app,
                    identity,
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("$GOAL_FORM/$id") },
                    onAddTo = { id -> nav.navigate("$GOAL_ADD/$id") },
                )
            }
            composable(
                route = "$GOAL_FORM/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                GoalFormScreen(app, entry.arguments?.getString("id") ?: NEW_ID, onClose = { nav.popBackStack() })
            }
            composable(
                route = "$GOAL_ADD/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                AddToGoalScreen(app, entry.arguments?.getString("id") ?: "", onClose = { nav.popBackStack() })
            }
        }
        }
        }   // the moving box
        }   // the swipe-watching box
        if (onTab) {
            FloatingNav(
                selected = selectedTab,
                attention = attention,
                onSelect = { tab -> slideTo(tab, swiped = false) },
                onAdd = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (showAdd) {
            AddSheet(
                onDismiss = { showAdd = false },
                onScan = { showAdd = false; scanner.open() },
                onAddTransaction = { showAdd = false; nav.navigate("$TXN_FORM/$NEW_ID") },
            )
        }
        }
    }
}

/** Switch tabs without stacking copies, and remember each tab's scroll position. */
private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
