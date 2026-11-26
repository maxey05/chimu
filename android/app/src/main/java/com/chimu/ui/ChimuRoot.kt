package com.chimu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus

/** Route id for "add a new one" on the entry and wallet forms. */
const val NEW_ID = "new"

private const val HOME = "home"
private const val TRANSACTIONS = "transactions"
private const val GROUP = "group"
private const val GROUP_CREATE = "group_create"
private const val GROUP_JOIN = "group_join"
private const val GROUP_TXN = "group_txn"
private const val MORE = "more"
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

/**
 * Decides what the person sees: setup help, the sign-in screen, or the app.
 *
 * Being signed in comes from [com.chimu.Account], which is remembered on the phone. That is what
 * lets the app open straight into the user's data with no network (it is offline-first), while
 * supabase-kt restores and refreshes the real session in the background.
 */
@Composable
fun ChimuRoot() {
    if (!Supa.isConfigured) {
        SetupNeededScreen()
        return
    }

    val app = LocalContext.current.applicationContext as ChimuApp
    val status by Supa.client.auth.sessionStatus.collectAsState()
    val identity by app.account.identity.collectAsState()

    // A fresh sign-in, sign-up or restored session: remember who it is.
    LaunchedEffect(status) {
        (status as? SessionStatus.Authenticated)?.let { app.account.signedIn(it.session) }
    }

    val signedIn = identity
    when {
        signedIn != null -> MainShell(app, signedIn)
        status is SessionStatus.Initializing -> Splash()
        else -> AuthScreen()
    }
}

@Composable
private fun MainShell(app: ChimuApp, identity: Identity) {
    val openOwner by app.repo.openOwner.collectAsState()
    LaunchedEffect(identity.userId) {
        app.repo.open(identity.userId)
        app.sync.start(identity.userId) // shows what the phone remembers, then syncs once
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
        GROUP -> NavTab.GROUP
        MORE -> NavTab.MORE
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars, modifier = Modifier.padding(bottom = if (onTab) NavInset - 24.dp else 0.dp)) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        CompositionLocalProvider(LocalNavInset provides (if (onTab) NavInset else 0.dp)) {
        NavHost(nav, startDestination = HOME) {
            composable(HOME) {
                HomeScreen(
                    app,
                    identity,
                    attention = attention,
                    onProfile = { nav.goToTab(MORE) },
                    onBalanceDetails = { nav.navigate(WALLETS) },
                    onSeeAll = { nav.goToTab(TRANSACTIONS) },
                    onOpenTxn = { id -> nav.navigate("$TXN_FORM/$id") },
                )
            }
            composable(TRANSACTIONS) {
                TransactionsScreen(
                    app,
                    onAdd = { nav.navigate("$TXN_FORM/$NEW_ID") },
                    onOpen = { id -> nav.navigate("$TXN_FORM/$id") },
                )
            }
            composable(GROUP) {
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
            composable(MORE) {
                MoreScreen(
                    app,
                    identity,
                    onWallets = { nav.navigate(WALLETS) },
                    onBudgets = { nav.navigate(BUDGETS) },
                    onRecurring = { nav.navigate(RECURRING) },
                    onGoals = { nav.navigate(GOALS) },
                    onInbox = { nav.navigate(INBOX) },
                    onAutoLog = { nav.navigate(AUTO_LOG) },
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
        if (onTab) {
            FloatingNav(
                selected = selectedTab,
                attention = attention,
                onSelect = { tab ->
                    nav.goToTab(
                        when (tab) {
                            NavTab.HOME -> HOME
                            NavTab.TRANSACTIONS -> TRANSACTIONS
                            NavTab.GROUP -> GROUP
                            NavTab.MORE -> MORE
                        },
                    )
                },
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
