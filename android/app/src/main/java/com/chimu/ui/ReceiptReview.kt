package com.chimu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.PhotoResult
import com.chimu.ReceiptPhotos
import com.chimu.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The receipt photo for [txn]: the phone's own file, or (after a reinstall) the copy downloaded from
 * the server. Tap it to see it full screen and zoom. If it can't be shown, a sentence says why.
 */
@Composable
fun ReceiptPhoto(app: ChimuApp, txn: Txn, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val result by produceState<PhotoResult?>(null, txn.id, txn.localPhoto, txn.receiptPath) {
        value = withContext(Dispatchers.IO) {
            ReceiptPhotos.load(context, txn.id, txn.localPhoto, txn.receiptPath) { app.sync.photoBytes(it) }
        }
    }
    var zoomed by remember { mutableStateOf(false) }

    when (val r = result) {
        null -> Text("Loading the receipt photo...", style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        is PhotoResult.Failed -> Text(r.message, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        is PhotoResult.Loaded -> {
            val image = remember(r.bitmap) { r.bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "Receipt photo. Double tap to enlarge.",
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clickable(onClickLabel = "Enlarge the receipt photo") { zoomed = true },
            )
            if (zoomed) ZoomedPhoto(image, onClose = { zoomed = false })
        }
    }
}

/** Full-screen photo with pinch to zoom and drag to move. The Close button is large and always on screen. */
@Composable
private fun ZoomedPhoto(image: ImageBitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = image,
                contentDescription = "Receipt photo, enlarged. Pinch to zoom, drag to move.",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Button(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).heightIn(min = MinTouchHeight),
            ) { Text("Close") }
        }
    }
}

/** "This looks like a payment you already logged": offers to put the receipt on it instead of keeping a second entry. */
@Composable
fun ReceiptMatchCard(match: Txn, busy: Boolean, onAttach: () -> Unit, modifier: Modifier = Modifier) {
    val name = match.merchant?.takeIf { it.isNotBlank() } ?: Categories.label(match.category).ifBlank { "Expense" }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Already logged?", style = MaterialTheme.typography.labelLarge)
            Text(
                "$name · ${Money.format(match.amount)} · ${Ledger.dateLabel(match.date)}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "This receipt has the same amount, within 2 days. Attach it to that payment instead of saving a second entry.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(
                onClick = onAttach,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) { Text("Attach to this payment") }
        }
    }
}
