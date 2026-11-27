package com.chimu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.ReceiptText
import com.chimu.Txn
import com.chimu.TxnType
import com.chimu.ui.theme.ChimuIcons
import com.chimu.ui.theme.chimu
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** `Today`, `Yesterday`, otherwise `24 Nov`. An unreadable date is shown as stored. */
internal fun shortDate(date: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
}

/** A scan that is waiting to be read has no amount yet. The row leaves the amount empty (no "Not read"). */
private fun isUnreadScan(txn: Txn) = txn.source == "receipt" && txn.status == "pending"

/**
 * One transaction that opens a small panel of details when tapped, on Home and on Transactions. Only the
 * parent decides which row is open ([expanded]), so one opens at a time. The panel slides open and its content
 * fades in; the chevron turns. [subtitle] is the grey line under the name.
 */
@Composable
internal fun ExpandableTxnRow(
    app: ChimuApp,
    txn: Txn,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.chimu
    val reduce = rememberReduceMotion()
    val unread = isUnreadScan(txn)
    val category = Categories.label(txn.category).ifBlank { txn.type.label }
    val title = when {
        txn.type == TxnType.TRANSFER -> "Transfer"
        else -> txn.merchant?.takeIf { it.isNotBlank() } ?: category
    }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(if (reduce) 0 else 320, easing = EaseOutExpo), label = "rowChevron")

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Details shown" else "Details hidden" }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KindIcon(txn, unread)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                // A scan says where it stands in words, so it never depends on colour (RULES 21).
                ReceiptText.listNote(txn)?.let {
                    Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!unread) {
                Text(
                    Money.formatSigned(txn.type, txn.amount),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (txn.type == TxnType.EXPENSE) colors.loss else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
            }
            Icon(
                ChimuIcons.ChevronDown,
                contentDescription = null,
                tint = colors.outlineStrong,
                modifier = Modifier.size(16.dp).rotate(turn),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(if (reduce) 0 else 380, easing = EaseOutExpo), expandFrom = Alignment.Top) +
                fadeIn(tween(if (reduce) 0 else 260, delayMillis = if (reduce) 0 else 70)),
            exit = shrinkVertically(tween(if (reduce) 0 else 260, easing = EaseOutExpo), shrinkTowards = Alignment.Top) +
                fadeOut(tween(if (reduce) 0 else 140)),
        ) {
            Details(app, txn, unread, onOpen)
        }
    }
}

/** The circle in front of a row: chevron down for money out, up for money in, arrows for a transfer, a dashed receipt for a scan. */
@Composable
private fun KindIcon(txn: Txn, unread: Boolean) {
    val colors = MaterialTheme.chimu
    if (unread) {
        val edge = colors.accent
        Box(
            Modifier
                .size(44.dp)
                .drawBehind {
                    drawCircle(
                        edge,
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ChimuIcons.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        return
    }
    val (fill, tint, icon) = when (txn.type) {
        TxnType.INCOME -> Triple(colors.gainContainer, colors.gain, ChimuIcons.ChevronUp)
        TxnType.EXPENSE -> Triple(colors.lossContainer, colors.loss, ChimuIcons.ChevronDown)
        TxnType.TRANSFER -> Triple(colors.detailWash, MaterialTheme.colorScheme.onSurfaceVariant, ChimuIcons.Transfer)
    }
    Box(Modifier.size(44.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private data class Detail(val key: String, val value: String)

/** What the panel lists: the facts a person looks for after tapping a row. */
private fun detailsFor(app: ChimuApp, txn: Txn, unread: Boolean): List<Detail> {
    val out = mutableListOf<Detail>()
    val time = txn.capturedAt?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    }
    out += Detail("Date", Ledger.dateLabel(txn.date) + (time?.let { " · $it" } ?: ""))
    if (txn.type == TxnType.TRANSFER) {
        out += Detail("From", app.repo.walletLabel(txn.walletId))
        out += Detail("To", app.repo.walletLabel(txn.toWalletId))
    } else {
        out += Detail("Wallet", app.repo.walletLabel(txn.walletId))
        Categories.label(txn.category).takeIf { it.isNotBlank() }?.let { out += Detail("Class", it) }
    }
    out += Detail(
        "Logged",
        when (txn.source) {
            "manual" -> "Typed in"
            "receipt" -> "Scanned receipt"
            "recurring" -> "Recurring"
            "autolog" -> "Auto-logged"
            else -> txn.source.replaceFirstChar { it.uppercase() }
        },
    )
    if (unread) ReceiptText.listNote(txn)?.let { out += Detail("Status", it) }
    if (txn.source == "receipt") txn.vat?.let { out += Detail("VAT", Money.format(it)) }
    if (txn.isBusiness) out += Detail("Business expense", "Yes")
    txn.note?.takeIf { it.isNotBlank() }?.let { out += Detail("Note", it) }
    return out
}

@Composable
private fun Details(app: ChimuApp, txn: Txn, unread: Boolean, onOpen: () -> Unit) {
    val rows = detailsFor(app, txn, unread)
    val hasPhoto = txn.receiptPath != null || txn.localPhoto != null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 58.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.chimu.detailWash)
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
    ) {
        rows.forEachIndexed { i, d ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(d.key, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            if (i < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text(if (unread) "Fill in by hand" else "Edit", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            if (hasPhoto) {
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("View receipt", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
