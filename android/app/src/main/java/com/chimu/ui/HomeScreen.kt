package com.chimu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimu.ChimuApp
import com.chimu.Identity
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.SyncStatus
import com.chimu.SyncText
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: ChimuApp, identity: Identity, onAdd: () -> Unit) {
    val txns by app.repo.txns.collectAsState()
    val sync by app.sync.status.collectAsState()
    val month = remember { YearMonth.now().toString() } // `2026-11`, the same shape as a stored date's first 7 characters
    val totals = remember(txns, month) { Ledger.monthTotals(txns, month) }

    // Pull down to sync. The Sync now button below does the same, for anyone who doesn't find the gesture.
    PullToRefreshBox(
        isRefreshing = sync.running,
        onRefresh = { app.sync.requestNow() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            val name = identity.displayName?.takeIf { it.isNotBlank() }
            Text(
                if (name != null) "Hi, $name" else "Hi",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                Ledger.monthLabel(month),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Spent this month", style = MaterialTheme.typography.labelLarge)
                    Text(Money.format(totals.spent), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("Income this month", style = MaterialTheme.typography.labelLarge)
                    Text(Money.format(totals.income), style = MaterialTheme.typography.headlineMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("+ Add")
            }
            Spacer(Modifier.height(12.dp))
            // Receipt scanning arrives in RFC-004. Disabled and labelled, so it is clear it isn't broken.
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("Scan receipt (coming soon)")
            }

            Spacer(Modifier.height(24.dp))
            SyncCard(sync, onSyncNow = { app.sync.requestNow() })
        }
    }
}

/**
 * Where syncing stands: when it last worked, what is still waiting, and what went wrong. A problem
 * is written out in words ("Sync problem: ...") so it never depends on colour alone (RULES 21).
 */
@Composable
private fun SyncCard(status: SyncStatus, onSyncNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Sync", style = MaterialTheme.typography.labelLarge)
            Text(
                if (status.running) "Syncing..." else SyncText.lastSynced(status.lastSyncedAt, System.currentTimeMillis()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(SyncText.pending(status.pending), style = MaterialTheme.typography.bodyLarge)
            ErrorText("Sync problem: ${status.error}".takeIf { status.error != null }, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSyncNow,
                enabled = !status.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("Sync now")
            }
        }
    }
}

/** The Group tab is a placeholder until RFC-006. */
@Composable
fun GroupScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text("Group", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tracking groups are coming in a later update. For now everything you log is just yours.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
