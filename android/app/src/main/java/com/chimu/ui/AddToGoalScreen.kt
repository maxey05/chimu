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
import com.chimu.GoalRules
import com.chimu.Ledger
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Add to goal" (RFC-011): moves money from another wallet into the goal's savings wallet as an ordinary `transfer`, saved
 * through the same path as the entry form ([com.chimu.Repo.saveTxn]: dirty, then synced). The transfer does not count as
 * spending or income; it raises the savings wallet's balance, which is the goal's progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToGoalScreen(app: ChimuApp, goalId: String, onClose: () -> Unit) {
    val repo = app.repo
    val wallets by repo.allWallets.collectAsState()
    val txns by repo.txns.collectAsState()
    val goal = remember(goalId) { repo.goals.value.firstOrNull { it.id == goalId } }

    if (goal == null) {
        Column(Modifier.fillMaxSize()) {
            FormHeader("Add to goal", onBack = onClose)
            Text("This goal no longer exists.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val today = remember { LocalDate.now().toString() }
    val sources = GoalFormRules.sourceWallets(goal, wallets)
    val canAdd = GoalFormRules.canAdd(goal, wallets)
    val progress = GoalRules.progressList(listOf(goal), wallets, txns, today).first()

    var fromId by rememberSaveable(goalId) { mutableStateOf<String?>(sources.firstOrNull()?.id) }
    var amountText by rememberSaveable(goalId) { mutableStateOf("") }
    var date by rememberSaveable(goalId) { mutableStateOf(today) }
    var error by rememberSaveable(goalId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        error = null
        val draft = when (val result = GoalFormRules.contribution(goal, fromId, amountText, date, wallets)) {
            is ContributionDraft.Invalid -> {
                error = result.message
                return
            }
            is ContributionDraft.Ok -> result
        }
        val txn = GoalFormRules.contributionTxn(goal, draft, UUID.randomUUID().toString())
        saving = true
        scope.launch {
            try {
                repo.saveTxn(txn)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing was lost: the form keeps what was typed so the person can try again.
                error = "Couldn't add this money. What you typed is still here, so try again. (${e.message})"
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
        FormHeader("Add to goal", onBack = onClose)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = FieldSpacing,
        ) {
            Column {
                Text(goal.name, style = MaterialTheme.typography.titleMedium)
                GoalProgressBar(progress, Modifier.padding(top = 8.dp))
            }

            if (!canAdd) {
                Text(GoalFormRules.GOAL_WALLET_GONE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            } else if (sources.isEmpty()) {
                Text(GoalFormRules.NO_SOURCE_WALLET, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    "This moves money from one of your wallets into the goal's savings wallet. It is a transfer, so it does not count as spending.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                DropdownField(
                    label = "From wallet",
                    valueText = sources.firstOrNull { it.id == fromId }?.name ?: "Choose a wallet",
                    choices = sources.map { Choice(it.id, it.name) },
                    onSelect = { fromId = it },
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount to add") },
                    prefix = { Text("₱") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                )
                Column {
                    Text("Date", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                    ) {
                        Text(Ledger.dateLabel(date), modifier = Modifier.weight(1f))
                    }
                }
            }

            ErrorText(error)

            if (canAdd && sources.isNotEmpty()) {
                Button(
                    onClick = { save() },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text(if (saving) "Saving…" else "Add to goal")
                }
            }
            OutlinedButton(
                onClick = onClose,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("Cancel")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = goalUtcMillisOf(date))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { date = goalIsoDateOfUtcMillis(it) }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
