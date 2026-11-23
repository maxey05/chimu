package com.chimu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.GroupText
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.Txn
import com.chimu.TxnType

/**
 * A group member's entry, read-only (RFC-006). There is nothing to edit here: the server accepts a write
 * only from the person who logged it (RLS), and this screen has no way to try. The receipt photo, if there
 * is one, is fetched through a signed link when this screen opens, not before.
 */
@Composable
fun GroupTxnScreen(app: ChimuApp, txnId: String, onClose: () -> Unit) {
    val snap by app.repo.groups.collectAsState()
    val txn = snap.txns.firstOrNull { it.id == txnId }
    val who = txn?.let { GroupText.personLabel(snap.names[it.ownerId], it.ownerId == snap.me) }

    Column(Modifier.fillMaxSize()) {
        FormHeader("Entry", onBack = onClose)
        if (txn == null) {
            Text(
                "This entry isn't available any more. It may have been deleted, or you may have left the group.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                "Logged by $who. Only they can change it.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(20.dp),
            )
            HorizontalDivider()
            Detail("Type", txn.type.label)
            Detail("Amount", Money.formatSigned(txn.type, txn.amount))
            Detail("Date", Ledger.dateLabel(txn.date))
            if (txn.type == TxnType.TRANSFER) {
                Detail("From", snap.walletNames[txn.walletId] ?: "a wallet")
                Detail("To", snap.walletNames[txn.toWalletId] ?: "a wallet")
            } else {
                Detail("Wallet", snap.walletNames[txn.walletId] ?: "a wallet")
                Detail("Category", Categories.label(txn.category).ifBlank { "No category" })
                Detail("Merchant", txn.merchant)
            }
            Detail("Note", txn.note)
            Detail("Business", if (txn.isBusiness) "Yes" else "No")
            ReceiptDetails(txn)
            val path = txn.receiptPath
            if (!path.isNullOrEmpty()) {
                Text("Receipt photo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
                ReceiptPhoto(
                    app,
                    txn,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    download = { app.sync.sharedPhotoBytes(it) },
                )
            }
        }
    }
}

/** The invoice and VAT lines a receipt gave, shown only when they are there. Information, not advice (RULES 22). */
@Composable
private fun ReceiptDetails(txn: Txn) {
    Detail("Seller TIN", txn.sellerTin)
    Detail("Invoice no.", txn.invoiceNo)
    Detail("VATable sales", txn.vatableSales?.let { Money.format(it) })
    Detail("VAT", txn.vat?.let { Money.format(it) })
    Detail("VAT-exempt sales", txn.vatExempt?.let { Money.format(it) })
    Detail("Reference no.", txn.refNo)
}

/** A label above a value. Nothing is shown for a missing or blank value. */
@Composable
private fun Detail(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider()
}
