package com.chimu.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chimu.BudgetRules
import com.chimu.BudgetText
import com.chimu.BudgetUsage
import com.chimu.ChimuApp
import com.chimu.GroupSnapshot
import com.chimu.Identity
import com.chimu.InboxRules
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.CategorySpend
import com.chimu.ReceiptPhotos
import com.chimu.SyncStatus
import com.chimu.SyncText
import com.chimu.TopCategories
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where this month's money went (RFC-008): the biggest spending categories as plain bars. Every bar
 * has its name and amount written beside it, so the length is never the only way to read it
 * (RULES 21), and each row is read out as one sentence by a screen reader.
 */
@Composable
internal fun TopCategoriesCard(entries: List<CategorySpend>, totalSpent: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Top categories this month", style = MaterialTheme.typography.labelLarge)
            if (entries.isEmpty()) {
                Text(
                    "No spending logged this month yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val biggest = entries.firstOrNull()?.spent ?: 1L
            for (entry in entries) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .semantics(mergeDescendants = true) { contentDescription = TopCategories.describe(entry, totalSpent) },
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(Money.format(entry.spent), style = MaterialTheme.typography.titleMedium)
                    }
                    // The track is the full width; the bar is this category's share of the biggest one.
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((entry.spent.toFloat() / biggest).coerceIn(0.02f, 1f))
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

/** How many budgets Home lists; the rest are one tap away under See all. */
internal const val HOME_BUDGETS = 3

/**
 * "Budgets this month" (RFC-009): the budgets nearest their limit, each with its bar, the amounts and a
 * status in words (never colour alone, RULES 21). Each row is read out as one sentence by a screen
 * reader. Only drawn when there is at least one budget to show.
 */
@Composable
internal fun BudgetsCard(usages: List<BudgetUsage>, snapshot: GroupSnapshot, me: String, onSeeAll: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Budgets this month", style = MaterialTheme.typography.labelLarge)
            for (usage in usages) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .semantics(mergeDescendants = true) { contentDescription = BudgetFormRules.rowDescription(usage, snapshot, me) },
                ) {
                    Text(
                        "${BudgetText.title(usage, snapshot)} · ${BudgetText.scope(usage.budget, snapshot)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    BudgetProgress(usage, Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onSeeAll, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                Text("See all budgets")
            }
        }
    }
}

/** The Inbox badge (RFC-005): how many auto-logged payments wait for a check. The count is written out, not just a dot. */
@Composable
internal fun InboxCard(count: Int, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Inbox", style = MaterialTheme.typography.labelLarge)
            Text(InboxRules.badge(count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Read from your notifications and SMS. They stay on this phone until you confirm them.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                Text("Open Inbox")
            }
        }
    }
}

/** The onboarding card (RFC-005): says what auto-logging reads and that unconfirmed items stay on the phone. Shown until the user sets it up or says not now. */
@Composable
internal fun AutoLogCard(onSetUp: () -> Unit, onNotNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Log payments automatically", style = MaterialTheme.typography.labelLarge)
            Text(AUTO_LOG_HOME_TEXT, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                Text("Set up")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                Text("Not now")
            }
        }
    }
}

/**
 * Where syncing stands: when it last worked, what is still waiting, and what went wrong. A problem
 * is written out in words ("Sync problem: ...") so it never depends on colour alone (RULES 21).
 */
@Composable
internal fun SyncCard(status: SyncStatus, onSyncNow: () -> Unit) {
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
