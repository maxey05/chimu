package com.chimu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.Txn
import com.chimu.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Add (id = [NEW_ID]) or edit an expense, income or transfer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnFormScreen(app: ChimuApp, txnId: String, onClose: () -> Unit) {
    val repo = app.repo
    val allWallets by repo.allWallets.collectAsState()
    val isNew = txnId == NEW_ID

    // The entry as it was when the form opened. Later list updates must not overwrite what is being typed.
    val existing = remember(txnId) { repo.txns.value.firstOrNull { it.id == txnId } }
    if (!isNew && existing == null) {
        Column(Modifier.fillMaxSize()) {
            FormHeader("Entry", onBack = onClose)
            Text("This entry no longer exists.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    // A scanned receipt that the user hasn't confirmed yet opens as a check-and-save screen (RFC-004).
    val isReview = existing != null && existing.source == "receipt" && existing.status != "confirmed"
    val txns by repo.txns.collectAsState()

    val activeWallets = allWallets.filter { !it.deleted }
    var typeSlug by rememberSaveable(txnId) { mutableStateOf(existing?.type?.slug ?: TxnType.EXPENSE.slug) }
    var walletId by rememberSaveable(txnId) { mutableStateOf(existing?.walletId ?: activeWallets.firstOrNull()?.id) }
    var toWalletId by rememberSaveable(txnId) { mutableStateOf(existing?.toWalletId) }
    var category by rememberSaveable(txnId) { mutableStateOf(existing?.category ?: Categories.OTHER) }
    var date by rememberSaveable(txnId) { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var amountText by rememberSaveable(txnId) { mutableStateOf(existing?.let { if (isReview && it.amount == 0L) "" else Money.plain(it.amount) } ?: "") }
    var merchant by rememberSaveable(txnId) { mutableStateOf(existing?.merchant.orEmpty()) }
    var note by rememberSaveable(txnId) { mutableStateOf(existing?.note.orEmpty()) }
    var isBusiness by rememberSaveable(txnId) { mutableStateOf(existing?.isBusiness ?: false) }

    var error by rememberSaveable(txnId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val type = TxnType.fromSlug(typeSlug)
    val walletChoices = activeWallets.map { Choice(it.id, it.name) }

    // An existing payment this receipt may belong to. Re-checked as the amount or date are corrected.
    val match = remember(txns, amountText, date, type) {
        if (isReview && existing != null && type == TxnType.EXPENSE) {
            Money.parse(amountText)?.takeIf { it > 0L }?.let { repo.matchFor(existing.id, it, date) }
        } else {
            null
        }
    }

    fun save() {
        error = null
        val cents = Money.parse(amountText)
        if (cents == null || cents <= 0L) {
            error = "Enter an amount greater than zero, like 1,234.50."
            return
        }
        val from = walletId
        if (from == null) {
            error = if (type == TxnType.TRANSFER) "Choose the wallet the money comes from." else "Choose a wallet."
            return
        }
        val to = toWalletId
        if (type == TxnType.TRANSFER) {
            if (to == null) {
                error = "Choose the wallet the money goes to."
                return
            }
            if (to == from) {
                error = "Choose two different wallets."
                return
            }
        }

        val txn = Txn(
            id = existing?.id ?: UUID.randomUUID().toString(),
            ownerId = existing?.ownerId ?: "", // Repo stamps the signed-in user
            type = type,
            walletId = from,
            toWalletId = if (type == TxnType.TRANSFER) to else null,
            amount = cents,
            date = date,
            category = if (type == TxnType.TRANSFER) null else category,
            merchant = if (type == TxnType.TRANSFER) null else merchant.trim().ifEmpty { null },
            note = note.trim().ifEmpty { null },
            isBusiness = type == TxnType.EXPENSE && isBusiness,
            source = existing?.source ?: "manual",
            status = existing?.status ?: "confirmed",
        )
        saving = true
        scope.launch {
            try {
                if (isReview) repo.confirmTxn(txn) else repo.saveTxn(txn)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing was lost: the form keeps what was typed so the person can try again.
                error = "Couldn't save this entry. What you typed is still here, so try again. (${e.message})"
            } finally {
                saving = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        FormHeader(if (isNew) "Add entry" else if (isReview) "Check receipt" else "Edit entry", onBack = onClose)

        if (existing != null && (!existing.localPhoto.isNullOrEmpty() || !existing.receiptPath.isNullOrEmpty())) {
            ReceiptPhoto(app, existing, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (isReview && existing != null) {
            Text(
                when {
                    existing.status == "pending" && existing.extractError != null ->
                        "The receipt couldn't be read: ${existing.extractError} Fill it in yourself and save."
                    existing.status == "pending" ->
                        "Waiting to be read. It is read when the phone is online. You can fill it in yourself now."
                    else -> "Read from the receipt. Check every field, fix anything that is wrong, then save."
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (match != null) {
                ReceiptMatchCard(
                    match = match,
                    busy = saving,
                    onAttach = {
                        saving = true
                        scope.launch {
                            try {
                                if (repo.attachScan(existing.id, match.id)) {
                                    onClose()
                                } else {
                                    error = "Couldn't attach the receipt. That payment may already have a photo."
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't attach the receipt. Nothing was changed, so try again. (${e.message})"
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = FieldSpacing,
        ) {
            // Type
            Column {
                Text("Type", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    TxnType.entries.forEach { option ->
                        FilterChip(
                            selected = option == type,
                            onClick = {
                                typeSlug = option.slug
                                // Keep the category only if it exists for the new type.
                                if (Categories.forType(option).none { it.slug == category }) category = Categories.OTHER
                            },
                            label = { Text(option.label, style = MaterialTheme.typography.labelLarge) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                prefix = { Text("₱") },
                isError = isReview && amountText.isBlank(),
                supportingText = if (isReview && amountText.isBlank()) {
                    { Text("Not found on the receipt. Enter the amount.") }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )

            if (walletChoices.isEmpty() && walletId == null) {
                Text(
                    "You have no wallets. Add one under More, then Wallets.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                DropdownField(
                    label = if (type == TxnType.TRANSFER) "From wallet" else "Wallet",
                    valueText = walletId?.let { repo.walletLabel(it) } ?: "Choose a wallet",
                    choices = walletChoices,
                    onSelect = { walletId = it },
                )
            }

            if (type == TxnType.TRANSFER) {
                DropdownField(
                    label = "To wallet",
                    valueText = toWalletId?.let { repo.walletLabel(it) } ?: "Choose a wallet",
                    choices = walletChoices,
                    onSelect = { toWalletId = it },
                )
            } else {
                DropdownField(
                    label = "Category",
                    valueText = Categories.label(category).ifBlank { "Other" },
                    choices = Categories.forType(type).map { Choice(it.slug, it.label) },
                    onSelect = { category = it ?: Categories.OTHER },
                )
                if (isReview && existing?.category == null) {
                    Text("The category wasn't found on the receipt. Choose one.", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Date
            Column {
                Text("Date", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text(Ledger.dateLabel(date), modifier = Modifier.weight(1f))
                }
            }

            if (type != TxnType.TRANSFER) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(if (isReview) "Merchant" else "Merchant (optional)") },
                    isError = isReview && merchant.isBlank(),
                    supportingText = if (isReview && merchant.isBlank()) {
                        { Text("Not found on the receipt.") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            if (type == TxnType.EXPENSE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchHeight)
                        .toggleable(value = isBusiness, role = Role.Switch, onValueChange = { isBusiness = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Business expense", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (isBusiness) "Marked as a business expense" else "Marked as a personal expense",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = isBusiness, onCheckedChange = null)
                }
            }

            ErrorText(error)

            Button(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text(if (saving) "Saving…" else if (isReview) "Save as checked" else "Save")
            }
            if (!isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text("Delete this entry")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = utcMillisOf(date))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { date = isoDateOfUtcMillis(it) }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("It will no longer count toward your balances or totals.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            try {
                                repo.deleteTxn(txnId)
                                onClose()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't delete this entry. Try again. (${e.message})"
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

/** Material's date picker works in UTC milliseconds at midnight, whatever the phone's time zone. */
private fun utcMillisOf(isoDate: String): Long {
    val day = runCatching { LocalDate.parse(isoDate) }.getOrDefault(LocalDate.now())
    return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun isoDateOfUtcMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
