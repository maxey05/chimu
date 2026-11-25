package com.chimu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chimu.ChimuApp
import com.chimu.GoalProgress
import com.chimu.GoalRules
import com.chimu.GoalText
import com.chimu.Identity
import java.time.LocalDate

/**
 * More -> Goals (RFC-011): every goal the user owns with its progress bar, the amount saved of the target, what is left and
 * what is needed per month until the deadline. Each row says all of it in words as well as with the bar ("₱2,900.00 of
 * ₱12,000.00 · 24% saved", "₱9,100.00 left"), so nothing depends on colour (RULES 21). Progress is the linked savings wallet's
 * balance ([GoalRules]), so money moved into that wallet fills the bar. Tap a goal to change or delete it; "Add to goal" opens
 * the transfer form.
 */
@Composable
fun GoalsScreen(
    app: ChimuApp,
    identity: Identity,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAddTo: (String) -> Unit,
) {
    val me = identity.userId
    val goals by app.repo.goals.collectAsState()
    val wallets by app.repo.allWallets.collectAsState()
    val txns by app.repo.txns.collectAsState()
    val today = remember { LocalDate.now().toString() }
    val progress = remember(goals, wallets, txns, today, me) {
        GoalRules.progressList(GoalRules.visible(me, goals), wallets, txns, today)
    }

    Column(Modifier.fillMaxSize()) {
        FormHeader("Goals", onBack = onBack)
        LazyColumn(Modifier.weight(1f)) {
            item(key = "intro") {
                Text(
                    if (progress.isEmpty()) GoalFormRules.EMPTY else "Tap a goal to change or delete it.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(progress, key = { it.goal.id }) { p ->
                val walletName = wallets.firstOrNull { it.id == p.goal.walletId && !it.deleted }?.name
                GoalRow(
                    p = p,
                    walletName = walletName,
                    canAdd = GoalFormRules.canAdd(p.goal, wallets),
                    onEdit = { onOpen(p.goal.id) },
                    onAddTo = { onAddTo(p.goal.id) },
                )
                HorizontalDivider()
            }
        }
        Button(
            onClick = { onOpen(NEW_ID) },
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = MinTouchHeight),
        ) {
            Text("+ Add goal")
        }
    }
}

/** One goal: the part that opens the form, then the "Add to goal" button (disabled when the savings wallet is archived). */
@Composable
private fun GoalRow(p: GoalProgress, walletName: String?, canAdd: Boolean, onEdit: () -> Unit, onAddTo: () -> Unit) {
    val description = GoalText.describe(p, walletName)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Change or delete this goal") { onEdit() }
                .heightIn(min = 72.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Text(p.goal.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (walletName != null) {
                Text("Savings wallet: $walletName", style = MaterialTheme.typography.bodyMedium)
            }
            GoalProgressBar(p, Modifier.padding(top = 8.dp))
        }
        OutlinedButton(
            onClick = onAddTo,
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = MinTouchHeight),
        ) {
            Text("Add to goal")
        }
    }
}

/**
 * The bar, the amounts and the state in words for one goal. The bar stops at full width however far past the target the
 * wallet is; the text carries the true percent and "Goal reached". Colour only adds to the words (RULES 21).
 */
@Composable
fun GoalProgressBar(p: GoalProgress, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fillColor = if (p.reached) scheme.tertiary else scheme.primary
    // Anything saved shows a sliver, even when it is well under 1% of the target.
    val fraction = p.barFraction.coerceIn(0f, 1f).let { if (p.saved > 0) it.coerceAtLeast(0.02f) else 0f }
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
        Text(GoalText.summary(p), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        Text(GoalText.status(p), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        GoalText.plan(p)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        GoalText.deadline(p.goal)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
