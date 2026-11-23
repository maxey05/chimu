package com.chimu.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.chimu.ChimuApp
import com.chimu.KnownApps
import com.chimu.SenderIds

/**
 * More -> Auto-log payments (RFC-005): says what is read and where it goes, lets the user pick the
 * apps whose notifications are read and the SMS senders that are allowed, and walks them through the
 * two system permissions. The choices live in [com.chimu.AutoLogSettings] on this phone.
 */
@Composable
fun AutoLogScreen(app: ChimuApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = app.autoLog
    val enabled by settings.enabledPackages.collectAsState()
    val seen by settings.seenPackages.collectAsState()
    val senders by settings.senders.collectAsState()

    // The two system permissions can be changed outside the app, so they are checked again every time the app comes back.
    val resumes = rememberResumeCount()
    val accessOn = remember(resumes) { notificationAccessOn(context) }
    var smsAllowed by remember(resumes) { mutableStateOf(smsPermissionGranted(context)) }
    val askForSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> smsAllowed = granted }

    var newSender by rememberSaveable { mutableStateOf("") }
    var senderError by rememberSaveable { mutableStateOf<String?>(null) }

    // GCash and Maya are always offered. Every other app appears once it has shown a notification.
    val others = (seen + enabled).filter { pkg -> KnownApps.all.none { it.packageName == pkg } }.sortedBy { appLabel(context, it).lowercase() }

    Column(Modifier.fillMaxSize()) {
        FormHeader("Auto-log payments", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What this does", style = MaterialTheme.typography.titleMedium)
                    Text(AUTO_LOG_EXPLANATION_1, style = MaterialTheme.typography.bodyLarge)
                    Text(AUTO_LOG_EXPLANATION_2, style = MaterialTheme.typography.bodyLarge)
                    Text(AUTO_LOG_EXPLANATION_3, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // --- notifications ---
            Text("Apps to read", style = MaterialTheme.typography.titleMedium)
            Text(
                if (accessOn) "Notification access is on." else "Notification access is off.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!accessOn) NotificationAccessHelp()

            KnownApps.all.forEach { known ->
                AppRow(known.label, known.packageName, enabled = known.packageName in enabled) { settings.setEnabled(known.packageName, it) }
            }
            others.forEach { pkg ->
                AppRow(appLabel(context, pkg), pkg, enabled = pkg in enabled) { settings.setEnabled(pkg, it) }
            }
            Text(
                "Other apps show up here after they show a notification. If the app you want is missing, wait for its next notification and come back.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            // --- SMS ---
            Text("SMS senders", style = MaterialTheme.typography.titleMedium)
            Text(
                if (smsAllowed) "Reading SMS is allowed." else "Reading SMS is not allowed yet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!smsAllowed) {
                Text(
                    "Only messages from the senders you add below are read. Tap the button and choose Allow. If nothing happens, open the app's settings, then Permissions, then SMS. " +
                        "If Android says this is a restricted setting, open the app's info page, tap the three dots at the top right, and choose Allow restricted settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { askForSms.launch(Manifest.permission.RECEIVE_SMS) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) { Text("Allow reading SMS") }
                OutlinedButton(
                    onClick = { context.startActivity(appInfoIntent(context)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) { Text("Open this app's settings") }
            }

            if (senders.isEmpty()) {
                Text("No senders yet. Add the name your bank or card shows on its texts, for example BPI.", style = MaterialTheme.typography.bodyLarge)
            }
            senders.forEach { sender ->
                Row(Modifier.fillMaxWidth().heightIn(min = MinTouchHeight), verticalAlignment = Alignment.CenterVertically) {
                    Text(sender, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { settings.removeSender(sender) }, modifier = Modifier.size(MinTouchHeight)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove sender $sender")
                    }
                }
            }
            OutlinedTextField(
                value = newSender,
                onValueChange = {
                    newSender = it
                    senderError = null
                },
                label = { Text("Sender name or number") },
                singleLine = true,
                isError = senderError != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )
            ErrorText(senderError)
            Button(
                onClick = {
                    if (settings.addSender(newSender)) {
                        newSender = ""
                        senderError = null
                    } else {
                        senderError = "That can't be added. Use 2 to 16 letters, digits or + . _ - , and it must not be in the list already (the list holds at most ${SenderIds.MAX_SENDERS})."
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            ) { Text("Add sender") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One app with a switch. The state is also written out ("On" / "Off") so it never depends on the switch's colour (RULES 21). */
@Composable
private fun AppRow(label: String, packageName: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchHeight)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(packageName, style = MaterialTheme.typography.bodySmall)
            Text(if (enabled) "On: its notifications are read" else "Off", style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * How to turn notification access on (or back on, if it was taken away). Shown on this screen and on
 * the Inbox. Android keeps the permission in its own settings, so the button opens that page.
 */
@Composable
fun NotificationAccessHelp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Chimu can't see payment notifications right now. To turn it on: tap the button, find Chimu in the list, and switch it on. " +
                "If Android says this is a restricted setting, open the app's info page, tap the three dots at the top right, choose Allow restricted settings, then try again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
        ) { Text("Open notification access settings") }
    }
}

/** Counts how many times the app has come back to the foreground, so a screen can check a permission again when it does. */
@Composable
fun rememberResumeCount(): Int {
    val context = LocalContext.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val activity = context.findComponentActivity()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) count++ }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    return count
}

/** True while the user has Chimu switched on in Android's notification access list. */
fun notificationAccessOn(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun smsPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

private fun appInfoIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** The app's own name if Android lets us see it, otherwise the package name itself. */
private fun appLabel(context: Context, packageName: String): String {
    KnownApps.all.firstOrNull { it.packageName == packageName }?.let { return it.label }
    return try {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }
}

private const val AUTO_LOG_EXPLANATION_1 =
    "Chimu can add GCash, Maya and card payments to your Inbox by reading notifications and text messages on this phone, so you don't have to type them."
private const val AUTO_LOG_EXPLANATION_2 =
    "What is read: only the notifications of the apps you switch on below, and only SMS from the senders you add. Nothing else is looked at."
private const val AUTO_LOG_EXPLANATION_3 =
    "Where it goes: payments wait in your Inbox on this phone until you confirm them. Unconfirmed items are never sent to the server or shown to your group. " +
        "If the app can't understand a message and the phone is online, that one message, and nothing else, is sent to the receipt reader to be understood."

/** Shared with the Home card, so both say the same thing. */
const val AUTO_LOG_HOME_TEXT =
    "Chimu can add your GCash, Maya and card payments to an Inbox by reading their notifications and SMS. " +
        "Only the apps and senders you choose are read, and nothing leaves this phone until you confirm it."
