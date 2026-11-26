package com.chimu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chimu.ChimuApp
import com.chimu.Identity
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.TopCategories
import java.time.YearMonth

/**
 * Dashboard tab: this month at a glance. What was spent and earned, then where the money went by category
 * (the card that used to sit under More). The web dashboard stays the place for the full charts and export.
 */
@Composable
fun DashboardScreen(app: ChimuApp, identity: Identity) {
    val txns by app.repo.txns.collectAsState()
    val month = remember { YearMonth.now().toString() }
    val totals = remember(txns, month) { Ledger.monthTotals(txns, month) }
    val topCategories = remember(txns, month, identity.userId) { TopCategories.top(txns, month, identity.userId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalNavInset.current),
    ) {
        Text(
            "Dashboard",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        )
        Text(
            Ledger.monthLabel(month),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Spent", totals.spent, Modifier.weight(1f))
                StatTile("Income", totals.income, Modifier.weight(1f))
            }
            TopCategoriesCard(topCategories, totals.spent)
        }
    }
}

/** A label over an amount, in a hairline-edged rounded box. */
@Composable
private fun StatTile(label: String, amount: Long, modifier: Modifier) {
    Column(
        modifier = modifier
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Money.format(amount), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
