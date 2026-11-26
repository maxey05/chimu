package com.chimu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimu.BudgetRules
import com.chimu.ChimuApp
import com.chimu.Identity
import java.time.YearMonth

/**
 * Plan tab: looking ahead. The three fullest budgets this month, then the lists for Budgets, Recurring and Goals
 * (they used to sit under More).
 */
@Composable
fun PlanScreen(
    app: ChimuApp,
    identity: Identity,
    onBudgets: () -> Unit,
    onRecurring: () -> Unit,
    onGoals: () -> Unit,
) {
    val txns by app.repo.txns.collectAsState()
    val budgets by app.repo.budgets.collectAsState()
    val groupSnapshot by app.repo.groups.collectAsState()
    val month = remember { YearMonth.now().toString() }
    val topBudgets = remember(budgets, txns, groupSnapshot, month, identity.userId) {
        BudgetRules.top(BudgetRules.usageList(identity.userId, budgets, txns, groupSnapshot, month), HOME_BUDGETS)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalNavInset.current),
    ) {
        Text(
            "Plan",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        )
        if (topBudgets.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BudgetsCard(topBudgets, groupSnapshot, identity.userId, onBudgets)
            }
        }
        HorizontalDivider()
        ListRow("Budgets", onBudgets)
        HorizontalDivider()
        ListRow("Recurring", onRecurring)
        HorizontalDivider()
        ListRow("Goals", onGoals)
        HorizontalDivider()
    }
}
