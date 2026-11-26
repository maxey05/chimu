package com.chimu.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chimu.BudgetRules
import com.chimu.BudgetText
import com.chimu.BudgetUsage
import com.chimu.ChimuApp
import com.chimu.GroupSnapshot
import com.chimu.Identity
import com.chimu.InboxRules
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.CategorySpend
import com.chimu.ReceiptPhotos
import com.chimu.SyncStatus
import com.chimu.SyncText
import com.chimu.TopCategories
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the add flow needs from [rememberReceiptScanner]. */
class ReceiptScanner(
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
fun rememberReceiptScanner(app: ChimuApp): ReceiptScanner {
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
