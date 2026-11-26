package com.chimu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chimu.BuildConfig
import com.chimu.ChimuApp
import com.chimu.Identity
import com.chimu.SampleData
import com.chimu.Supa
import com.chimu.ui.theme.chimu
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Profile tab: who is signed in, how syncing stands, and the things that used to sit under More
 * (Wallets, Inbox, auto-log setup, sign out). Budgets, Recurring and Goals moved to Plan; the month's
 * categories moved to Dashboard.
 */
@Composable
fun ProfileScreen(
    app: ChimuApp,
    identity: Identity,
    onWallets: () -> Unit,
    onInbox: () -> Unit,
    onAutoLog: () -> Unit,
) {
    val inbox by app.repo.inbox.collectAsState()
    val txns by app.repo.txns.collectAsState()
    val scope = rememberCoroutineScope()
    val sync by app.sync.status.collectAsState()
    val enabledApps by app.autoLog.enabledPackages.collectAsState()
    val senders by app.autoLog.senders.collectAsState()
    val onboardingDismissed by app.autoLog.onboardingDismissed.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalNavInset.current),
    ) {
        Text(
            "Profile",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileAvatar(identity.displayName, 52.dp)
            Column(Modifier.weight(1f)) {
                identity.displayName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
                identity.email?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SyncCard(sync, onSyncNow = { app.sync.requestNow() })
            if (inbox.isNotEmpty()) InboxCard(inbox.size, onInbox)
            if (enabledApps.isEmpty() && senders.isEmpty() && !onboardingDismissed) {
                AutoLogCard(onSetUp = onAutoLog, onNotNow = { app.autoLog.dismissOnboarding() })
            }
        }
        HorizontalDivider()
        ListRow("Wallets", onWallets)
        HorizontalDivider()
        // Only the number: the badge already says these are waiting.
        ListRow(if (inbox.isEmpty()) "Inbox" else "Inbox (${inbox.size})", onInbox)
        HorizontalDivider()
        ListRow("Auto-log payments", onAutoLog)
        HorizontalDivider()
        // TEMPORARY: debug builds only. Loads or removes the made-up wallets and transactions (see SampleData.kt).
        if (SampleData.ENABLED && BuildConfig.DEBUG) {
            val sampleLoaded = txns.any { SampleData.isSample(it.id) }
            ListRow(if (sampleLoaded) "Remove sample data" else "Load sample data") {
                scope.launch { if (sampleLoaded) SampleData.remove(app) else SampleData.load(app) }
            }
            HorizontalDivider()
        }

        Column(Modifier.padding(20.dp)) {
            OutlinedButton(
                onClick = { confirmSignOut = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) {
                Text("Sign out")
            }
        }
    }

    if (confirmSignOut) SignOutDialog(app, onDismiss = { confirmSignOut = false })
}

/** The person's first letter on a dawning-coloured circle. */
@Composable
internal fun ProfileAvatar(name: String?, size: Dp) {
    val initial = name?.trim()?.firstOrNull()?.uppercase() ?: "C"
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.chimu.dawning),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** One tappable line of a list screen (Profile and Plan). */
@Composable
internal fun ListRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Asks before signing out, then signs out. Used by Profile and by the account menu on Home. If it fails the
 * reason is written inside the dialog, so the person sees it and can try again.
 */
@Composable
internal fun SignOutDialog(app: ChimuApp, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out?") },
        text = {
            Column {
                Text("The entries you logged stay on this phone. Sign in again to see them.")
                ErrorText(error, Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = null
                    scope.launch {
                        try {
                            Supa.client.auth.signOut()
                            app.account.signedOut()
                            app.repo.close()
                            app.sync.stop()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Couldn't sign out. Check your internet connection and try again."
                        }
                    }
                },
            ) { Text("Sign out") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay signed in") } },
    )
}
