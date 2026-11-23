package com.chimu.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
private const val MORE = "more"
private const val WALLETS = "wallets"
private const val TXN_FORM = "txn"
private const val WALLET_FORM = "wallet"

private class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HOME, "Home", Icons.Filled.Home),
    Tab(TRANSACTIONS, "Transactions", Icons.AutoMirrored.Filled.List),
    Tab(GROUP, "Group", Icons.Filled.AccountCircle),
    Tab(MORE, "More", Icons.Filled.Menu),
)

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
    LaunchedEffect(identity.userId) { app.repo.open(identity.userId) }
    if (openOwner != identity.userId) {
        Splash()
        return
    }

    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val onTab = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.goToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HOME, modifier = Modifier.padding(padding)) {
            composable(HOME) {
                HomeScreen(app, identity, onAdd = { nav.navigate("$TXN_FORM/$NEW_ID") })
            }
            composable(TRANSACTIONS) {
                TransactionsScreen(
                    app,
                    onAdd = { nav.navigate("$TXN_FORM/$NEW_ID") },
                    onOpen = { id -> nav.navigate("$TXN_FORM/$id") },
                )
            }
            composable(GROUP) { GroupScreen() }
            composable(MORE) { MoreScreen(app, identity, onWallets = { nav.navigate(WALLETS) }) }

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
