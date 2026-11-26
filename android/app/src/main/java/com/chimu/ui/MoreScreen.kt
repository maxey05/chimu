package com.chimu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimu.BudgetRules
import com.chimu.ChimuApp
import com.chimu.TopCategories
import java.time.YearMonth
import com.chimu.Identity
import com.chimu.Supa
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(
    app: ChimuApp,
    identity: Identity,
    onWallets: () -> Unit,
    onBudgets: () -> Unit,
    onRecurring: () -> Unit,
    onGoals: () -> Unit,
    onInbox: () -> Unit,
    onAutoLog: () -> Unit,
) {
    val inbox by app.repo.inbox.collectAsState()
    // What used to sit on Home: this month's categories and budgets, the Inbox count, sync and auto-log setup.
    val txns by app.repo.txns.collectAsState()
    val budgets by app.repo.budgets.collectAsState()
    val groupSnapshot by app.repo.groups.collectAsState()
    val sync by app.sync.status.collectAsState()
    val enabledApps by app.autoLog.enabledPackages.collectAsState()
    val senders by app.autoLog.senders.collectAsState()
    val onboardingDismissed by app.autoLog.onboardingDismissed.collectAsState()
    val month = remember { YearMonth.now().toString() }
    val totals = remember(txns, month) { com.chimu.Ledger.monthTotals(txns, month) }
    val topCategories = remember(txns, month, identity.userId) { TopCategories.top(txns, month, identity.userId) }
    val topBudgets = remember(budgets, txns, groupSnapshot, month, identity.userId) {
        BudgetRules.top(BudgetRules.usageList(identity.userId, budgets, txns, groupSnapshot, month), HOME_BUDGETS)
    }
    var confirmSignOut by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalNavInset.current),
    ) {
        Text(
            "More",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SyncCard(sync, onSyncNow = { app.sync.requestNow() })
            if (inbox.isNotEmpty()) InboxCard(inbox.size, onInbox)
            TopCategoriesCard(topCategories, totals.spent)
            if (topBudgets.isNotEmpty()) BudgetsCard(topBudgets, groupSnapshot, identity.userId, onBudgets)
            if (enabledApps.isEmpty() && senders.isEmpty() && !onboardingDismissed) {
                AutoLogCard(onSetUp = onAutoLog, onNotNow = { app.autoLog.dismissOnboarding() })
            }
        }
        HorizontalDivider()
        MoreRow("Wallets", onWallets)
        HorizontalDivider()
        MoreRow("Budgets", onBudgets)
        HorizontalDivider()
        MoreRow("Recurring", onRecurring)
        HorizontalDivider()
        MoreRow("Goals", onGoals)
        HorizontalDivider()
        MoreRow(if (inbox.isEmpty()) "Inbox" else "Inbox (${inbox.size} to check)", onInbox)
        HorizontalDivider()
        MoreRow("Auto-log payments", onAutoLog)
        HorizontalDivider()

        Column(Modifier.padding(20.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            identity.displayName?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            }
            identity.email?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmSignOut = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("Sign out")
            }
            ErrorText(error, Modifier.padding(top = 12.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("The entries you logged stay on this phone. Sign in again to see them.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        error = null
                        scope.launch {
                            try {
                                Supa.client.auth.signOut()
                                app.account.signedOut()
                                app.repo.close()
                                app.sync.stop()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't sign out. Check your internet connection and try again."
                            }
                        }
                    },
                ) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Stay signed in") } },
        )
    }
}

/** One tappable line of the More list. */
@Composable
private fun MoreRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
