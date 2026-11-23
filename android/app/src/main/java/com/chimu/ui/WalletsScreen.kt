package com.chimu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chimu.Balances
import com.chimu.ChimuApp
import com.chimu.Money
import com.chimu.Wallet
import com.chimu.WalletType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** More -> Wallets: every active wallet with its balance. */
@Composable
fun WalletsScreen(app: ChimuApp, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val wallets by app.repo.allWallets.collectAsState()
    val txns by app.repo.txns.collectAsState()
    val active = wallets.filter { !it.deleted }
    val balances = remember(wallets, txns) { Balances.forWallets(wallets, txns) }

    Column(Modifier.fillMaxSize()) {
        FormHeader("Wallets", onBack = onBack)
        LazyColumn(Modifier.weight(1f)) {
            items(active, key = { it.id }) { wallet ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(wallet.id) }
                        .heightIn(min = 72.dp)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(wallet.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(wallet.type.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        Money.format(balances[wallet.id] ?: 0L),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                HorizontalDivider()
            }
        }
        Button(
            onClick = { onOpen(NEW_ID) },
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = MinTouchHeight),
        ) {
            Text("+ Add wallet")
        }
    }
}

/** Add (id = [NEW_ID]) or edit a wallet. Editing also offers to archive it. */
@Composable
fun WalletFormScreen(app: ChimuApp, walletId: String, onClose: () -> Unit) {
    val repo = app.repo
    val isNew = walletId == NEW_ID
    val existing = remember(walletId) { repo.allWallets.value.firstOrNull { it.id == walletId && !it.deleted } }
    if (!isNew && existing == null) {
        Column(Modifier.fillMaxSize()) {
            FormHeader("Wallet", onBack = onClose)
            Text("This wallet no longer exists.", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    var name by rememberSaveable(walletId) { mutableStateOf(existing?.name ?: "") }
    var typeSlug by rememberSaveable(walletId) { mutableStateOf(existing?.type?.slug ?: WalletType.CASH.slug) }
    var openingText by rememberSaveable(walletId) { mutableStateOf(existing?.let { Money.plain(it.openingBalance) } ?: "") }
    var error by rememberSaveable(walletId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        error = null
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
            error = "Give the wallet a name of 1 to $MAX_NAME_LENGTH characters."
            return
        }
        // Blank means zero. A card that already carries a balance owed can start below zero.
        val opening = if (openingText.isBlank()) 0L else Money.parse(openingText, allowNegative = true)
        if (opening == null) {
            error = "Enter the starting balance like 1,234.50, or leave it blank."
            return
        }

        val wallet = Wallet(
            id = existing?.id ?: UUID.randomUUID().toString(),
            ownerId = existing?.ownerId ?: "", // Repo stamps the signed-in user
            name = trimmed,
            type = WalletType.fromSlug(typeSlug),
            openingBalance = opening,
        )
        saving = true
        scope.launch {
            try {
                repo.saveWallet(wallet)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't save this wallet. What you typed is still here, so try again. (${e.message})"
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
        FormHeader(if (isNew) "Add wallet" else "Edit wallet", onBack = onClose)
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = FieldSpacing,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )
            DropdownField(
                label = "Type",
                valueText = WalletType.fromSlug(typeSlug).label,
                choices = WalletType.entries.map { Choice(it.slug, it.label) },
                onSelect = { slug -> if (slug != null) typeSlug = slug },
            )
            OutlinedTextField(
                value = openingText,
                onValueChange = { openingText = it },
                label = { Text("Starting balance (optional)") },
                prefix = { Text("₱") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                supportingText = { Text("Use a minus sign if the wallet starts below zero.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )

            ErrorText(error)

            Button(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
            if (!isNew) {
                OutlinedButton(
                    onClick = { confirmArchive = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) {
                    Text("Archive this wallet")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive this wallet?") },
            text = {
                Text(
                    "It leaves your wallet list and can't be picked for new entries. " +
                        "Entries already logged in it stay in your history.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmArchive = false
                        scope.launch {
                            try {
                                if (repo.archiveWallet(walletId)) {
                                    onClose()
                                } else {
                                    error = "Keep at least one wallet. Add another one first, then archive this one."
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Couldn't archive this wallet. Try again. (${e.message})"
                            }
                        }
                    },
                ) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Keep it") } },
        )
    }
}

private const val MAX_NAME_LENGTH = 60
