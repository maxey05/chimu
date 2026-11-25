package com.chimu.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chimu.Budget
import com.chimu.BudgetLevel
import com.chimu.BudgetNotifier
import com.chimu.BudgetRules
import com.chimu.BudgetText
import com.chimu.BudgetUsage
import com.chimu.ChimuApp
import com.chimu.GroupSnapshot
import com.chimu.Identity
import com.chimu.Ledger
import com.chimu.Money
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * More -> Budgets (RFC-009): this month's usage of every budget the user can see, grouped by scope
 * (Personal, then each group). Every row says it in words as well as with the bar ("₱4,800.00 of
 * ₱6,000.00 · 80% used", "Close to the limit"), so nothing depends on colour (RULES 21). Budgets another
 * member set for a group are shown but have no way to edit them. The numbers all come from [BudgetRules],
 * the same rules the alerts and the web dashboard use.
 */
@Composable
fun BudgetsScreen(app: ChimuApp, identity: Identity, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val me = identity.userId
    val budgets by app.repo.budgets.collectAsState()
    val txns by app.repo.txns.collectAsState()
    val snapshot by app.repo.groups.collectAsState()
    val month = remember { Ledger.monthOf(LocalDate.now().toString()) }
    val usages = remember(budgets, txns, snapshot, month, me) { BudgetRules.usageList(me, budgets, txns, snapshot, month) }
    val sections = remember(usages, snapshot) { BudgetFormRules.sections(usages, snapshot) }
    val hasBudgets = usages.isNotEmpty()
    val ownsAny = usages.any { BudgetFormRules.isMine(it.budget, me) }

    // Alerts need the notification permission on Android 13+. Ask once per visit, as soon as there is a budget
    // to warn about (that is also right after the user saves their first one). Saving never waits for it.
    val alerts = rememberAlertPermission()
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasBudgets, alerts.missing) {
        if (hasBudgets && alerts.missing && !asked) {
            asked = true
            alerts.request()
        }
    }

    Column(Modifier.fillMaxSize()) {
        FormHeader("Budgets", onBack = onBack)
        LazyColumn(Modifier.weight(1f)) {
            item(key = "intro") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(Ledger.monthLabel(month), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (hasBudgets) {
                            "Chimu warns you when a budget reaches 80% and again at 100%." +
                                if (ownsAny) " Tap one of yours to change or delete it." else ""
                        } else {
                            BudgetFormRules.EMPTY
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (hasBudgets && alerts.missing) {
                item(key = "alerts-off") { AlertsOffNote(alerts) }
            }
            sections.forEach { section ->
                item(key = "scope:${section.groupId ?: "personal"}") {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(section.usages, key = { it.budget.id }) { usage ->
                    BudgetRow(usage, snapshot, me, onOpen)
                    HorizontalDivider()
                }
            }
        }
        Button(
            onClick = { onOpen(NEW_ID) },
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = MinTouchHeight),
        ) {
            Text("+ Add budget")
        }
    }
}

/** One budget. The user's own rows open the form; someone else's are read-only and say who set them. */
@Composable
private fun BudgetRow(usage: BudgetUsage, snapshot: GroupSnapshot, me: String, onOpen: (String) -> Unit) {
    val mine = BudgetFormRules.isMine(usage.budget, me)
    val description = BudgetFormRules.rowDescription(usage, snapshot, me)
    val open = if (mine) Modifier.clickable(onClickLabel = "Change or delete this budget") { onOpen(usage.budget.id) } else Modifier
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(open)
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(BudgetText.title(usage, snapshot), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (!mine) {
            Text(BudgetFormRules.setBy(usage.budget, snapshot), style = MaterialTheme.typography.bodyMedium)
        }
        BudgetProgress(usage, Modifier.padding(top = 8.dp))
    }
}

/**
 * The bar, the amounts and the status in words, for one budget. Used on this screen and on Home. The
 * bar stops at full width however far over the limit the spending is; the text carries the true
 * percentage and the amount over. Colour only adds to the words (RULES 21).
 */
@Composable
fun BudgetProgress(usage: BudgetUsage, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fillColor = when (usage.level) {
        BudgetLevel.OK -> scheme.primary
        BudgetLevel.WARN -> scheme.tertiary
        BudgetLevel.OVER -> scheme.error
    }
    // Anything spent shows a sliver, even when it is well under 1% of the limit.
    val fraction = usage.barFraction.coerceIn(0f, 1f).let { if (usage.spent > 0) it.coerceAtLeast(0.02f) else 0f }
    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.outlineVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(fillColor),
                )
            }
        }
        Text(BudgetText.summary(usage), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        Text(
            BudgetText.status(usage),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (usage.level == BudgetLevel.OVER) scheme.error else Color.Unspecified,
        )
    }
}

/** Says alerts can't be sent and what to do about it. Never blocks anything. */
@Composable
private fun AlertsOffNote(alerts: AlertPermission) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(BudgetFormRules.ALERTS_OFF, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            // Once Android has refused the question, it won't show the dialog again: the phone's settings are the only way.
            OutlinedButton(
                onClick = if (alerts.refused) alerts.openSettings else alerts.request,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text(if (alerts.refused) "Open notification settings" else "Allow notifications")
            }
        }
    }
}

/**
 * Where notification permission stands. [missing] is re-checked every time the app comes back to the
 * front and after each answer. [refused] is true once Android's dialog was answered "no" (or could not
 * be shown because it was refused before): from then on only the settings page can turn it on.
 */
class AlertPermission(
    val missing: Boolean,
    val refused: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
fun rememberAlertPermission(): AlertPermission {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    var answers by remember { mutableIntStateOf(0) }
    var refused by remember { mutableStateOf(false) }
    val missing = remember(resumes, answers) { BudgetNotifier.needsPermission(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        answers++
    }
    return AlertPermission(
        missing = missing,
        refused = refused,
        request = { launcher.launch(BudgetNotifier.PERMISSION) },
        openSettings = { openNotificationSettings(context) },
    )
}

/** The app's notification settings page, or its general info page on a phone that has no such page. */
private fun openNotificationSettings(context: Context) {
    val page = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(page)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

/**
 * Add (id = [NEW_ID]) or edit one of the user's own budgets. Scope and category are chosen when the
 * budget is created and then fixed: changing either would be a different budget, and the way to get
 * one is to delete this and add another. The limit is the only thing editing changes.
 */
@Composable
fun BudgetFormScreen(app: ChimuApp, identity: Identity, budgetId: String, onClose: () -> Unit) {
    val repo = app.repo
    val me = identity.userId
    val isNew = budgetId == NEW_ID
    val snapshot by repo.groups.collectAsState()
    val existing = remember(budgetId) { repo.budgets.value.firstOrNull { it.id == budgetId } }

    if (!isNew && existing == null) {
        Column(Modifier.fillMaxSize()) {
            FormHeader("Budget", onBack = onClose)
            Text("This budget no longer exists.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    if (existing != null && !BudgetFormRules.isMine(existing, me)) {
        Column(Modifier.fillMaxSize()) {
            FormHeader("Budget", onBack = onClose)
            Text(
                "${BudgetFormRules.setBy(existing, snapshot)}. ${BudgetFormRules.NOT_YOURS}",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    var groupId by rememberSaveable(budgetId) { mutableStateOf<String?>(existing?.groupId) }
    var category by rememberSaveable(budgetId) { mutableStateOf<String?>(existing?.category) }
    var limitText by rememberSaveable(budgetId) { mutableStateOf(existing?.let { Money.plain(it.monthlyLimit) } ?: "") }
    var error by rememberSaveable(budgetId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        error = null
        val draft = when (val result = BudgetFormRules.draft(me, groupId, category, limitText, existing?.id, repo.budgets.value, snapshot)) {
            is BudgetDraft.Invalid -> {
                error = result.message
                return
            }
            is BudgetDraft.Ok -> result
        }
        val budget = existing?.copy(groupId = draft.groupId, category = draft.category, monthlyLimit = draft.limit)
            ?: Budget(
                id = UUID.randomUUID().toString(),
                ownerId = me, // Repo stamps the signed-in user again
                groupId = draft.groupId,
                category = draft.category,
                monthlyLimit = draft.limit,
                updatedAt = 0, // Repo stamps the time
            )
        saving = true
        scope.launch {
            try {
                repo.saveBudget(budget)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "This budget can't be saved. Check the choices and the limit."
            } catch (e: Exception) {
                error = "Couldn't save this budget. What you typed is still here, so try again. (${e.message})"
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
        FormHeader(if (isNew) "Add budget" else "Edit budget", onBack = onClose)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = FieldSpacing,
        ) {
            if (isNew && snapshot.groups.isNotEmpty()) {
                DropdownField(
                    label = "Applies to",
                    valueText = BudgetFormRules.scopeLabel(groupId, snapshot),
                    choices = BudgetFormRules.scopeOptions(snapshot).map { Choice(it.key, it.label) },
                    onSelect = { groupId = it },
                )
            } else {
                FixedField("Applies to", BudgetFormRules.scopeLabel(groupId, snapshot))
            }
            Text(BudgetFormRules.scopeNote(groupId), style = MaterialTheme.typography.bodyMedium)

            if (isNew) {
                DropdownField(
                    label = "Category",
                    valueText = BudgetFormRules.categoryLabel(category),
                    choices = BudgetFormRules.categoryOptions().map { Choice(it.key, it.label) },
                    onSelect = { category = it },
                )
            } else {
                FixedField("Category", BudgetFormRules.categoryLabel(category))
            }

            OutlinedTextField(
                value = limitText,
                onValueChange = { limitText = it },
                label = { Text("Monthly limit") },
                prefix = { Text("₱") },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text(BudgetFormRules.LIMIT_HELP) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )
            if (!isNew) {
                Text(
                    "Who it applies to and the category can't be changed. To use others, delete this budget and add a new one.",
                    style = MaterialTheme.typography.bodyMedium,
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
                    Text("Delete this budget")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this budget?") },
            text = {
                Text(
                    "Chimu stops tracking this limit and sending alerts for it. Your entries are not changed." +
                        if (groupId != null) " Other members of the group stop seeing it once they sync." else "",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            try {
                                repo.deleteBudget(budgetId)
                                onClose()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't delete this budget. Try again. (${e.message})"
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

/** A label with a value that can't be changed here, laid out like [DropdownField] so the form reads evenly. */
@Composable
private fun FixedField(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
