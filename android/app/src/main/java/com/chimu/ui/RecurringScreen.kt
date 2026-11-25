package com.chimu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.Identity
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.RecurringFrequency
import com.chimu.RecurringRule
import com.chimu.RecurringRules
import com.chimu.RecurringText
import com.chimu.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * More -> Recurring (RFC-010): the user's rules, soonest due first, then the paused ones. Every row says it in words (what it is, how
 * much, how often, whether it is due soon or paused, the next due date), so nothing depends on colour or an icon (RULES 21).
 * The server logs the entries; this screen only edits the rules (see `supabase/migrations/0003_recurring.sql`).
 */
@Composable
fun RecurringScreen(app: ChimuApp, identity: Identity, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val me = identity.userId
    val loaded by app.recurring.rules.collectAsState()
    // A sync that logs an entry for a rule changes this list too: read the rules again when it does.
    val txns by app.repo.txns.collectAsState()
    val wallets by app.repo.allWallets.collectAsState()
    LaunchedEffect(me, txns) { app.recurring.load(me) }

    val rules = remember(loaded, me) { RecurringRules.sorted(RecurringRules.visible(me, loaded)) }
    val today = remember { LocalDate.now() }

    // Reminders need the notification permission on Android 13+. Ask once per visit, as soon as there is a rule to remind about.
    val alerts = rememberAlertPermission()
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(rules.isNotEmpty(), alerts.missing) {
        if (rules.isNotEmpty() && alerts.missing && !asked) {
            asked = true
            alerts.request()
        }
    }

    Column(Modifier.fillMaxSize()) {
        FormHeader("Recurring", onBack = onBack)
        LazyColumn(Modifier.weight(1f)) {
            item(key = "intro") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        if (rules.isEmpty()) RecurringText.EMPTY else RecurringText.EXPLAIN,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (rules.isNotEmpty() && alerts.missing) {
                item(key = "alerts-off") {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(RecurringFormRules.ALERTS_OFF, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = if (alerts.refused) alerts.openSettings else alerts.request,
                                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                            ) {
                                Text(if (alerts.refused) "Open notification settings" else "Allow notifications")
                            }
                        }
                    }
                }
            }
            if (rules.isNotEmpty()) {
                item(key = "heading") {
                    Text(
                        "Your recurring items",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
            }
            items(rules, key = { it.id }) { rule ->
                val walletLabel = remember(wallets, rule.walletId) { app.repo.walletLabel(rule.walletId) }
                RecurringRow(rule, walletLabel, today, onOpen)
                HorizontalDivider()
            }
        }
        Button(
            onClick = { onOpen(NEW_ID) },
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = MinTouchHeight),
        ) {
            Text("+ Add recurring item")
        }
    }
}

/** One rule: name, amount and how often, when it is due (or Paused), the next date and the wallet. The whole row opens it. */
@Composable
private fun RecurringRow(rule: RecurringRule, walletLabel: String, today: LocalDate, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Change, pause or delete this recurring item") { onOpen(rule.id) }
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = RecurringText.describe(rule, walletLabel, today) },
    ) {
        Text(RecurringText.title(rule), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(RecurringText.summary(rule), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        Text(RecurringText.status(rule, today), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Text(RecurringText.nextLine(rule), style = MaterialTheme.typography.bodyMedium)
        Text("Wallet: $walletLabel", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Add (id = [NEW_ID]) or edit one of the user's own recurring items; an edited one can also be paused, resumed or deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringFormScreen(app: ChimuApp, identity: Identity, ruleId: String, onClose: () -> Unit) {
    val repo = app.repo
    val me = identity.userId
    val isNew = ruleId == NEW_ID
    val existing = remember(ruleId) { app.recurring.rules.value.firstOrNull { it.id == ruleId && it.ownerId == me } }

    if (!isNew && existing == null) {
        Column(Modifier.fillMaxSize()) {
            FormHeader("Recurring item", onBack = onClose)
            Text("This item no longer exists.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val allWallets by repo.allWallets.collectAsState()
    val activeWallets = remember(allWallets) { repo.activeWallets() }
    val today = remember { LocalDate.now() }

    var type by rememberSaveable(ruleId) { mutableStateOf(existing?.type?.slug ?: "expense") }
    var name by rememberSaveable(ruleId) { mutableStateOf(existing?.merchant ?: "") }
    var amountText by rememberSaveable(ruleId) { mutableStateOf(existing?.let { Money.plain(it.amount) } ?: "") }
    var category by rememberSaveable(ruleId) { mutableStateOf(existing?.category) }
    var walletId by rememberSaveable(ruleId) { mutableStateOf(existing?.walletId ?: activeWallets.firstOrNull()?.id) }
    var frequency by rememberSaveable(ruleId) { mutableStateOf(existing?.frequency?.slug ?: "monthly") }
    var date by rememberSaveable(ruleId) { mutableStateOf(existing?.nextDate ?: today.plusDays(1).toString()) }
    var remind by rememberSaveable(ruleId) { mutableStateOf(existing?.remindDaysBefore ?: 1) }
    var note by rememberSaveable(ruleId) { mutableStateOf(existing?.note ?: "") }
    var error by rememberSaveable(ruleId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val txnType = if (type == "income") TxnType.INCOME else TxnType.EXPENSE
    val pastNote = runCatching {
        RecurringFormRules.pastNote(RecurringFrequency.fromSlug(frequency) ?: RecurringFrequency.MONTHLY, LocalDate.parse(date), today)
    }.getOrNull()

    fun save() {
        error = null
        val draft = when (
            val result = RecurringFormRules.draft(
                type, name, amountText, category, walletId, activeWallets.map { it.id }.toSet(), frequency, date, remind, note,
            )
        ) {
            is RecurringDraft.Invalid -> {
                error = result.message
                return
            }
            is RecurringDraft.Ok -> result
        }
        val rule = RecurringFormRules.toRule(draft, existing, UUID.randomUUID().toString(), me)
        saving = true
        scope.launch {
            try {
                app.recurring.save(me, rule)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "This item can't be saved. Check the choices and the amount."
            } catch (e: Exception) {
                error = "Couldn't save this item. What you typed is still here, so try again. (${e.message})"
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
        FormHeader(if (isNew) "Add recurring item" else "Edit recurring item", onBack = onClose)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = FieldSpacing,
        ) {
            if (existing != null && !existing.active) {
                Text(
                    "Paused. Chimu is not logging this item. ${RecurringFormRules.RESUME_NOTE}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            DropdownField(
                label = "Kind",
                valueText = RecurringFormRules.typeLabel(txnType),
                choices = RecurringFormRules.typeOptions.map { Choice(it.key, it.label) },
                onSelect = { chosen ->
                    type = chosen ?: type
                    val valid = Categories.forType(if (type == "income") TxnType.INCOME else TxnType.EXPENSE)
                    if (valid.none { it.slug == category }) category = null
                },
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                supportingText = { Text(RecurringFormRules.NAME_HELP) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                prefix = { Text("₱") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text(RecurringFormRules.AMOUNT_HELP) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )

            DropdownField(
                label = "Category",
                valueText = RecurringFormRules.categoryLabel(category),
                choices = RecurringFormRules.categoryOptions(txnType).map { Choice(it.key, it.label) },
                onSelect = { category = it },
            )

            if (activeWallets.isEmpty()) {
                Text("You have no wallets. Add one under More, then Wallets.", style = MaterialTheme.typography.bodyLarge)
            } else {
                DropdownField(
                    label = if (txnType == TxnType.INCOME) "Paid into wallet" else "Paid from wallet",
                    valueText = walletId?.let { repo.walletLabel(it) } ?: "Choose a wallet",
                    choices = activeWallets.map { Choice(it.id, it.name) },
                    onSelect = { walletId = it },
                )
            }

            DropdownField(
                label = "Repeats",
                valueText = RecurringFrequency.fromSlug(frequency)?.label ?: "Every month",
                choices = RecurringFormRules.frequencyOptions.map { Choice(it.key, it.label) },
                onSelect = { frequency = it ?: frequency },
            )

            Column {
                Text("Next due date", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text(Ledger.dateLabel(date), modifier = Modifier.weight(1f))
                }
                if (pastNote != null) {
                    Text(pastNote, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }

            DropdownField(
                label = "Remind me",
                valueText = RecurringText.remindLabel(remind),
                choices = RecurringFormRules.remindOptions(remind).map { Choice(it.key, it.label) },
                onSelect = { remind = it?.toIntOrNull() ?: remind },
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isNew) Text(RecurringFormRules.NOT_SYNCED_NOTE, style = MaterialTheme.typography.bodyMedium)

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
            if (existing != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                if (app.recurring.setActive(me, existing.id, !existing.active, LocalDate.now())) onClose()
                                else error = "This item no longer exists."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't ${if (existing.active) "pause" else "resume"} this item. Try again. (${e.message})"
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text(if (existing.active) "Pause this item" else "Resume this item")
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text("Delete this item")
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
            title = { Text("Delete this recurring item?") },
            text = { Text("Chimu stops logging it and reminding you. Entries it already logged stay in your list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            try {
                                app.recurring.delete(me, ruleId)
                                onClose()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't delete this item. Try again. (${e.message})"
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
private fun utcMillisOf(isoDate: String): Long {
    val day = runCatching { LocalDate.parse(isoDate) }.getOrDefault(LocalDate.now())
    return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun isoDateOfUtcMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
