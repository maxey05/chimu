package com.chimu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The daily reminder job (RFC-010): once a day, around 9 AM, WorkManager asks [Reminders] which recurring items are coming up
 * and shows a notification for each. It reads only what is already on the phone, so it works offline. If nobody is signed in it
 * does nothing. The decisions are in `Reminders.kt` (pure and tested); this file is the Android side.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as ChimuApp
        val me = app.account.identity.value?.userId ?: return@withContext Result.success()
        try {
            val today = LocalDate.now()
            val (rules, loggedToday) = app.recurring.forReminders(me, today)
            val log = PrefsReminderLog(PrefsAlertStore(applicationContext.getSharedPreferences("chimu", Context.MODE_PRIVATE)), me)
            Reminders(log) { ReminderNotifier.post(applicationContext, it) }.run(me, rules, loggedToday, today)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry() // WorkManager backs off, and the next daily run tries again anyway
        }
    }

    companion object {
        private const val UNIQUE_NAME = "chimu-reminders"
        private const val FIRST_RUN_HOUR = 9

        /** Safe to call on every launch: an existing schedule is kept as it is, so the job stays on its morning slot. */
        fun schedule(context: Context) {
            val firstRunIn = ReminderPlanner.minutesUntilNext(FIRST_RUN_HOUR, LocalDateTime.now())
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(firstRunIn, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** [ReminderLog] on top of the app's key-value preferences: `recurring_reminder_<me>_<ruleId>` = the last reminded due date. */
class PrefsReminderLog(private val store: AlertStore, private val me: String) : ReminderLog {
    override fun lastDue(ruleId: String): String? = store.get(ReminderPlanner.keyOf(me, ruleId))

    override fun record(ruleId: String, dueDate: String) = store.put(ReminderPlanner.keyOf(me, ruleId), dueDate)
}

/** Shows a [Reminder] with the platform's notification API, the same way [BudgetNotifier] shows an alert. */
object ReminderNotifier {
    const val CHANNEL_ID = "recurring_reminders"

    /**
     * Posts [reminder]. Returns false, and posts nothing, when the permission is missing, notifications are switched off for
     * the app, or the user has turned off the "Bill reminders" channel, so the reminder is not counted as sent.
     * Tapping it opens the app.
     */
    fun post(context: Context, reminder: Reminder): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (BudgetNotifier.needsPermission(context) || !manager.areNotificationsEnabled()) return false
        ensureChannel(manager)
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, reminder.notificationId, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(Notification.BigTextStyle().bigText(reminder.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            manager.notify(reminder.notificationId, notification)
            true
        } catch (e: SecurityException) {
            false // the permission was taken away between the check and now
        }
    }

    /** Creates the channel the first time it is needed; creating it again is harmless. */
    private fun ensureChannel(manager: NotificationManager) {
        val channel = NotificationChannel(CHANNEL_ID, "Bill reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you a few days before a recurring bill, subscription or income is due."
        }
        manager.createNotificationChannel(channel)
    }
}
