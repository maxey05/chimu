package com.chimu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chimu.ChimuApp
import com.chimu.InboxRules
import com.chimu.Ledger
import com.chimu.Txn
import com.chimu.TxnType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Inbox (RFC-005): payments the phone read from a notification or SMS, waiting for the user.
 * Confirm saves one as an ordinary entry (and only then does it sync), Edit opens it in the entry
 * form first, Dismiss deletes it from this phone for good. Nothing here has been sent anywhere.
 */
@Composable
fun InboxScreen(app: ChimuApp, onBack: () -> Unit, onOpen: (String) -> Unit, onAutoLog: () -> Unit) {
    val context = LocalContext.current
    val inboxItems by app.repo.inbox.collectAsState()
    val enabled by app.autoLog.enabledPackages.collectAsState()
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var dismissing by remember { mutableStateOf<Txn?>(null) }

    // The user can switch notification access off in Android's settings. Say so, and how to fix it, instead of staying quiet.
    val resumes = rememberResumeCount()
    val accessOn = remember(resumes) { notificationAccessOn(context) }

    Column(Modifier.fillMaxSize()) {
        FormHeader("Inbox", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "Payments Chimu read from notifications and SMS. They stay on this phone until you confirm them.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (enabled.isNotEmpty() && !accessOn) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        NotificationAccessHelp(Modifier.padding(16.dp))
                    }
                }
            }
            item { ErrorText(error, Modifier.padding(horizontal = 20.dp)) }

            if (inboxItems.isEmpty()) {
                item {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Nothing to check. New payments from the apps and senders you choose show up here.", style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = onAutoLog, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                            Text("Choose apps and senders")
                        }
                    }
                }
            } else {
                items(inboxItems, key = { it.id }) { txn ->
                    InboxRow(
                        app = app,
                        txn = txn,
                        busy = busyId == txn.id,
                        onOpen = { onOpen(txn.id) },
                        onConfirm = {
                            busyId = txn.id
                            error = null
                            scope.launch {
                                try {
                                    app.repo.confirmTxn(InboxRules.forConfirm(txn))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = "Couldn't confirm that payment. Nothing was changed, so try again. (${e.message})"
                                } finally {
                                    busyId = null
                                }
                            }
                        },
                        onDismiss = { dismissing = txn },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    dismissing?.let { target ->
        AlertDialog(
            onDismissRequest = { dismissing = null },
            title = { Text("Dismiss this payment?") },
            text = { Text("It is removed from this phone for good. It was never sent anywhere, so it can't be brought back.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissing = null
                        error = null
                        scope.launch {
                            try {
                                if (!app.repo.dismissInbox(target.id)) error = "That payment is already gone from the Inbox."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't dismiss that payment. Try again. (${e.message})"
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = MinTouchHeight),
                ) { Text("Dismiss") }
            },
            dismissButton = {
                TextButton(onClick = { dismissing = null }, modifier = Modifier.heightIn(min = MinTouchHeight)) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun InboxRow(app: ChimuApp, txn: Txn, busy: Boolean, onOpen: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val ready = InboxRules.canConfirm(txn)
    val where = when (txn.type) {
        TxnType.TRANSFER -> "${app.repo.walletLabel(txn.walletId)} to ${app.repo.walletLabel(txn.toWalletId)}"
        else -> "${txn.type.label} · ${app.repo.walletLabel(txn.walletId)}"
    }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Edit this payment", onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(InboxRules.title(txn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(where, style = MaterialTheme.typography.bodyMedium)
                    Text("${Ledger.dateLabel(txn.date)} · ${InboxRules.sourceLabel(txn)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    InboxRules.amountText(txn),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (!ready) {
                txn.rawText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Message: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Text(
                InboxRules.note(txn),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready) {
                    Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = MinTouchHeight)) {
                        Text(if (busy) "Saving…" else "Confirm")
                    }
                    OutlinedButton(onClick = onOpen, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = MinTouchHeight)) {
                        Text("Edit")
                    }
                } else {
                    Button(onClick = onOpen, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = MinTouchHeight)) {
                        Text("Fill in")
                    }
                }
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = MinTouchHeight)) {
                    Text("Dismiss")
                }
            }
        }
    }
}
