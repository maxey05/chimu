package com.chimu.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chimu.ChimuApp
import com.chimu.Identity
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.ReceiptPhotos
import com.chimu.SyncStatus
import com.chimu.SyncText
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: ChimuApp, identity: Identity, onAdd: () -> Unit) {
    val txns by app.repo.txns.collectAsState()
    val sync by app.sync.status.collectAsState()
    val month = remember { YearMonth.now().toString() } // `2026-11`, the same shape as a stored date's first 7 characters
    val totals = remember(txns, month) { Ledger.monthTotals(txns, month) }
    val scanner = rememberReceiptScanner(app)

    // Pull down to sync. The Sync now button below does the same, for anyone who doesn't find the gesture.
    PullToRefreshBox(
        isRefreshing = sync.running,
        onRefresh = { app.sync.requestNow() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            val name = identity.displayName?.takeIf { it.isNotBlank() }
            Text(
                if (name != null) "Hi, $name" else "Hi",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                Ledger.monthLabel(month),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Spent this month", style = MaterialTheme.typography.labelLarge)
                    Text(Money.format(totals.spent), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("Income this month", style = MaterialTheme.typography.labelLarge)
                    Text(Money.format(totals.income), style = MaterialTheme.typography.headlineMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("+ Add")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = scanner.open,
                enabled = !scanner.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text(if (scanner.busy) "Saving the photo..." else "Scan receipt")
            }
            scanner.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            ErrorText(scanner.error, Modifier.padding(top = 8.dp))

            Spacer(Modifier.height(24.dp))
            SyncCard(sync, onSyncNow = { app.sync.requestNow() })
        }
    }
}

/**
 * Where syncing stands: when it last worked, what is still waiting, and what went wrong. A problem
 * is written out in words ("Sync problem: ...") so it never depends on colour alone (RULES 21).
 */
@Composable
private fun SyncCard(status: SyncStatus, onSyncNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Sync", style = MaterialTheme.typography.labelLarge)
            Text(
                if (status.running) "Syncing..." else SyncText.lastSynced(status.lastSyncedAt, System.currentTimeMillis()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(SyncText.pending(status.pending), style = MaterialTheme.typography.bodyLarge)
            ErrorText("Sync problem: ${status.error}".takeIf { status.error != null }, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSyncNow,
                enabled = !status.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("Sync now")
            }
        }
    }
}

/** The Group tab is a placeholder until RFC-006. */
@Composable
fun GroupScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text("Group", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tracking groups are coming in a later update. For now everything you log is just yours.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** What the Home screen needs from [rememberReceiptScanner]. */
private class ReceiptScanner(
    val open: () -> Unit,
    val busy: Boolean,
    /** Good news in words ("Saved. It will be read..."), or null. */
    val message: String?,
    /** What went wrong, in words, or null. */
    val error: String?,
)

/**
 * "Scan receipt" (RFC-004): asks whether to take a photo or pick one, shrinks the picture, and logs a
 * `pending` entry that sync uploads and reads once the phone is online. No camera permission is
 * needed: the camera app takes the picture and hands it back through a FileProvider URI.
 */
@Composable
private fun rememberReceiptScanner(app: ChimuApp): ReceiptScanner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Kept across rotation and process death: the camera app can outlive this screen.
    var captureId by rememberSaveable { mutableStateOf<String?>(null) }

    /** Shrinks [source] into the phone's own photo for [id], then logs the pending entry. */
    fun keep(id: String, source: android.net.Uri) {
        busy = true
        message = null
        error = null
        scope.launch {
            try {
                val dest = ReceiptPhotos.photoFile(context, id)
                withContext(Dispatchers.IO) { ReceiptPhotos.saveResized(context, source, dest) }
                try {
                    app.repo.createScan(id, dest.path)
                } catch (e: Exception) {
                    dest.delete() // no entry points at this file, so don't leave it behind
                    throw e
                }
                message = "Receipt saved. It will be read as soon as the phone is online. Find it under Transactions."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't save the receipt. ${e.message}"
            } finally {
                withContext(Dispatchers.IO) { captureId?.let { ReceiptPhotos.captureFile(context, it).delete() } }
                captureId = null
                busy = false
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val id = captureId
        if (id == null) return@rememberLauncherForActivityResult
        if (taken) {
            keep(id, ReceiptPhotos.captureUri(context, id))
        } else {
            captureId = null // cancelled: nothing to keep
        }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) keep(UUID.randomUUID().toString(), uri)
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Scan a receipt") },
            text = { Text("Take a photo of the receipt, or choose one you already have.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        choosing = false
                        val id = UUID.randomUUID().toString()
                        captureId = id
                        try {
                            takePicture.launch(ReceiptPhotos.captureUri(context, id))
                        } catch (e: ActivityNotFoundException) {
                            captureId = null
                            error = "This phone has no camera app. Choose a photo from the gallery instead."
                        }
                    },
                    modifier = Modifier.heightIn(min = MinTouchHeight),
                ) { Text("Take photo") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        choosing = false
                        pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.heightIn(min = MinTouchHeight),
                ) { Text("Choose from gallery") }
            },
        )
    }

    return ReceiptScanner(open = { choosing = true }, busy = busy, message = message, error = error)
}
