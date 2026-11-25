package com.chimu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chimu.ChimuApp
import com.chimu.Goal
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.Wallet
import com.chimu.WalletType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Add (id = [NEW_ID]) or edit one of the user's own goals (RFC-011): name, target, optional deadline and the savings wallet
 * whose balance is the progress. With no savings wallet yet, the form says one is made for the goal (named like the goal
 * unless the person types another name); with some, they pick one or choose "New savings wallet". A new wallet and the
 * goal are saved in one database transaction, and the wallet's id is kept for the whole visit to the form, so a retry
 * after an error never makes a second wallet. Delete is a tombstone: the wallet and its money are not touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormScreen(app: ChimuApp, goalId: String, onClose: () -> Unit) {
    val repo = app.repo
    val wallets by repo.allWallets.collectAsState()
    val isNew = goalId == NEW_ID
    val existing = remember(goalId) { repo.goals.value.firstOrNull { it.id == goalId } }

    if (!isNew && existing == null) {
        Column(Modifier.fillMaxSize()) {
            FormHeader("Goal", onBack = onClose)
            Text("This goal no longer exists.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val today = remember { LocalDate.now().toString() }
    val newWalletId = rememberSaveable(goalId) { UUID.randomUUID().toString() }
    val hasSavings = GoalFormRules.savingsWallets(wallets).isNotEmpty()

    var name by rememberSaveable(goalId) { mutableStateOf(existing?.name ?: "") }
    var targetText by rememberSaveable(goalId) { mutableStateOf(existing?.let { Money.plain(it.target) } ?: "") }
    var deadline by rememberSaveable(goalId) { mutableStateOf<String?>(existing?.deadline) }
    var walletKey by rememberSaveable(goalId) { mutableStateOf<String?>(GoalFormRules.initialWalletKey(existing, wallets)) }
    var newWalletName by rememberSaveable(goalId) { mutableStateOf("") }
    var error by rememberSaveable(goalId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val makingNewWallet = walletKey == GoalFormRules.NEW_WALLET
    val walletGone = existing != null && wallets.none { it.id == existing.walletId && !it.deleted }

    fun save() {
        error = null
        val draft = when (val result = GoalFormRules.draft(name, targetText, deadline, walletKey, newWalletName, wallets, today, existing)) {
            is GoalDraft.Invalid -> {
                error = result.message
                return
            }
            is GoalDraft.Ok -> result
        }
        val wallet = draft.newWalletName?.let {
            Wallet(
                id = newWalletId,
                ownerId = "", // Repo stamps the signed-in user
                name = it,
                type = WalletType.SAVINGS,
                openingBalance = 0,
            )
        }
        val goal = Goal(
            id = existing?.id ?: UUID.randomUUID().toString(),
            ownerId = existing?.ownerId ?: "", // Repo stamps the signed-in user
            name = draft.name,
            target = draft.target,
            deadline = draft.deadline,
            walletId = draft.walletId ?: newWalletId,
        )
        saving = true
        scope.launch {
            try {
                repo.saveGoal(goal, wallet)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "This goal can't be saved. Check the choices and the target."
            } catch (e: Exception) {
                // Nothing was lost: the form keeps what was typed so the person can try again.
                error = "Couldn't save this goal. What you typed is still here, so try again. (${e.message})"
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
        FormHeader(if (isNew) "Add goal" else "Edit goal", onBack = onClose)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = FieldSpacing,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Goal name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it },
                label = { Text("Target amount") },
                prefix = { Text("₱") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("How much you want to have saved, like 20,000.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )

            Column {
                Text("Deadline (optional)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text(deadline?.let { Ledger.dateLabel(it) } ?: "No deadline. Tap to pick one.", modifier = Modifier.weight(1f))
                }
                if (deadline != null) {
                    TextButton(onClick = { deadline = null }, modifier = Modifier.heightIn(min = MinTouchHeight)) {
                        Text("Clear the deadline")
                    }
                }
            }

            if (walletGone) {
                Text(
                    "This goal's savings wallet was archived. Pick another one below.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (hasSavings) {
                DropdownField(
                    label = "Savings wallet",
                    valueText = GoalFormRules.walletChoices(wallets).firstOrNull { it.key == walletKey }?.label ?: "Choose a wallet",
                    choices = GoalFormRules.walletChoices(wallets).map { Choice(it.key, it.label) },
                    onSelect = { walletKey = it },
                )
                Text(GoalFormRules.WALLET_HELP, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(GoalFormRules.NO_SAVINGS_WALLET, style = MaterialTheme.typography.bodyLarge)
            }
            if (makingNewWallet) {
                OutlinedTextField(
                    value = newWalletName,
                    onValueChange = { newWalletName = it },
                    label = { Text("New wallet name (optional)") },
                    singleLine = true,
                    supportingText = { Text("Leave it empty to name the wallet like the goal.") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                )
            }

            ErrorText(error)

            Button(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
            OutlinedButton(
                onClick = onClose,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("Cancel")
            }
            if (!isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text("Delete this goal")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = goalUtcMillisOf(deadline ?: today))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { deadline = goalIsoDateOfUtcMillis(it) }
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
            title = { Text("Delete this goal?") },
            text = { Text("Chimu stops tracking it. The savings wallet and the money in it are not changed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            try {
                                repo.deleteGoal(goalId)
                                onClose()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't delete this goal. Try again. (${e.message})"
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = MinTouchHeight),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = MinTouchHeight)) { Text("Keep it") }
            },
        )
    }
}

/** Material's date picker works in UTC milliseconds at midnight, whatever the phone's time zone. */
internal fun goalUtcMillisOf(isoDate: String): Long {
    val day = runCatching { LocalDate.parse(isoDate) }.getOrDefault(LocalDate.now())
    return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

internal fun goalIsoDateOfUtcMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
