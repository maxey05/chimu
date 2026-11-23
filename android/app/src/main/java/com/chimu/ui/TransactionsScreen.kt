package com.chimu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.Txn
import com.chimu.TxnFilter
import com.chimu.TxnType

@Composable
fun TransactionsScreen(app: ChimuApp, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val txns by app.repo.txns.collectAsState()
    val wallets by app.repo.allWallets.collectAsState()

    // Filters survive rotation. Null / blank means "no filter".
    var month by rememberSaveable { mutableStateOf<String?>(null) }
    var walletId by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var typeSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }

    val filter = TxnFilter(
        month = month,
        walletId = walletId,
        category = category,
        type = typeSlug?.let { TxnType.fromSlug(it) },
        search = search,
    )
    val rows = remember(txns, filter) { Ledger.filter(txns, filter) }
    val filtering = filter != TxnFilter()

    val monthChoices = remember(txns) {
        listOf(Choice(null, "All")) + Ledger.months(txns).map { Choice(it, Ledger.monthLabel(it)) }
    }
    val walletChoices = remember(wallets) {
        listOf(Choice(null, "All")) + wallets.map { Choice(it.id, if (it.deleted) "${it.name} (archived)" else it.name) }
    }
    val categoryChoices = remember { listOf(Choice(null, "All")) + Categories.all.map { Choice(it.slug, it.label) } }
    val typeChoices = remember { listOf(Choice(null, "All")) + TxnType.entries.map { Choice(it.slug, it.label) } }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Transactions",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Add") }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search merchant or note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterMenu(
                    title = "Month",
                    valueText = month?.let { Ledger.monthLabel(it) } ?: "All",
                    choices = monthChoices,
                    onSelect = { month = it },
                )
                Spacer(Modifier.width(8.dp))
                FilterMenu(
                    title = "Wallet",
                    valueText = walletId?.let { app.repo.walletLabel(it) } ?: "All",
                    choices = walletChoices,
                    onSelect = { walletId = it },
                )
                Spacer(Modifier.width(8.dp))
                FilterMenu(
                    title = "Type",
                    valueText = typeSlug?.let { TxnType.fromSlug(it).label } ?: "All",
                    choices = typeChoices,
                    onSelect = { typeSlug = it },
                )
                Spacer(Modifier.width(8.dp))
                FilterMenu(
                    title = "Category",
                    valueText = category?.let { Categories.label(it) } ?: "All",
                    choices = categoryChoices,
                    onSelect = { category = it },
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    if (filtering) "Nothing matches these filters." else "No entries yet. Tap + Add to log your first one.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(20.dp),
                )
            }
        } else {
            items(rows, key = { it.id }) { txn ->
                TxnRow(app, txn, onClick = { onOpen(txn.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TxnRow(app: ChimuApp, txn: Txn, onClick: () -> Unit) {
    val title = when (txn.type) {
        TxnType.TRANSFER -> "Transfer"
        else -> txn.merchant?.takeIf { it.isNotBlank() } ?: Categories.label(txn.category).ifBlank { txn.type.label }
    }
    val detail = when (txn.type) {
        TxnType.TRANSFER -> "${app.repo.walletLabel(txn.walletId)} to ${app.repo.walletLabel(txn.toWalletId)}"
        else -> "${txn.type.label} · ${Categories.label(txn.category).ifBlank { "No category" }} · ${app.repo.walletLabel(txn.walletId)}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Text(Ledger.dateLabel(txn.date), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            Money.formatSigned(txn.type, txn.amount),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
