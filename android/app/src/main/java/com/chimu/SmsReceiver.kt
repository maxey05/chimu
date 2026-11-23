package com.chimu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.launch

/**
 * Receives incoming SMS (needs the RECEIVE_SMS permission, which the user grants from More ->
 * Auto-log payments) and keeps only the messages from senders on the user's allowlist (RFC-005).
 * Messages from anyone else are dropped on the spot: not stored, not parsed, not sent anywhere.
 *
 * Thin on purpose: the tested logic is [SmsJoin], [SenderIds] and [ChimuApp.logMessage].
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? ChimuApp ?: return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            null
        } ?: return
        val parts = messages.map { SmsPart(it.originatingAddress, it.messageBody, it.timestampMillis) }
        val allowed = app.autoLog.senders.value
        val wanted = SmsJoin.join(parts).filter { SenderIds.matches(allowed, it.origin) }
        if (wanted.isEmpty()) return

        // A receiver has about ten seconds. goAsync() keeps the process alive for the database write, and
        // the reader gets less time than a notification would, so the item is stored in time either way.
        val pending = goAsync()
        app.appScope.launch {
            try {
                wanted.forEach { app.logMessage(it, SMS_READ_TIMEOUT_MS) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val SMS_READ_TIMEOUT_MS = 6_000L
    }
}
