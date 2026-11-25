package com.chimu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate

/**
 * Budget alerts (RFC-009): one local notification the first time a budget reaches 80% and again the first time it
 * reaches 100% in a month. The rules live in [BudgetAlerts] and are pure; [BudgetNotifier] is the only Android part.
 */

/** Where the last alerted level of each budget is kept. A tiny key-value view so the rules can be tested without a phone. */
interface AlertStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** The app's `chimu` preferences as an [AlertStore]. Written with `commit()`: it runs on a background thread, and a lost record would alert twice. */
class PrefsAlertStore(private val prefs: SharedPreferences) : AlertStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }
}

/** What to tell the user. [level] is [BudgetLevel.WARN] or [BudgetLevel.OVER]. [notificationId] is stable per budget and level. */
data class BudgetAlert(
    val budgetId: String,
    val level: BudgetLevel,
    val title: String,
    val text: String,
    val notificationId: Int,
)

/**
 * Decides which alerts to post and remembers what was already posted.
 *
 * Per signed-in user and budget, the store holds `budget_alert_<meId>_<budgetId>` = `<YYYY-MM>:<level 0|80|100>:<limit>`.
 * A level is posted only when it is higher than the one on record for the same month and limit, so crossing 80% and then
 * 100% gives one notification each, a jump straight to 100% gives only the 100% one, and the level never goes down within
 * a month (deleting an expense and adding it back does not alert again). A new month starts fresh, and changing the budget's limit
 * resets the record. The record is written only when [post] says the notification really went out, so an alert that was
 * blocked (permission denied, notifications off) is still owed once that changes.
 *
 * Safe to call from several threads: a reload can run after a save and after a sync at the same moment.
 */
class BudgetAlerts(private val store: AlertStore, private val post: (BudgetAlert) -> Boolean) {
    private val lock = Any()

    /**
     * The after-reload entry point: works out [today]'s month usage for every budget [me] can see and checks it.
     * [txns] are [me]'s own transactions; group budgets read the members' transactions from [snapshot].
     */
    fun checkAll(me: String, budgets: List<Budget>, txns: List<Txn>, snapshot: GroupSnapshot, today: LocalDate): List<BudgetAlert> {
        val month = Ledger.monthOf(today.toString())
        return check(me, BudgetRules.usageList(me, budgets, txns, snapshot, month), month, snapshot)
    }

    /** Posts what is new for [usages] in [month] (`YYYY-MM`) and returns the alerts that went out. [snapshot] only supplies group names for the text. */
    fun check(me: String, usages: List<BudgetUsage>, month: String, snapshot: GroupSnapshot = GroupSnapshot()): List<BudgetAlert> =
        synchronized(lock) {
            val posted = mutableListOf<BudgetAlert>()
            for (usage in usages) {
                val budget = usage.budget
                if (budget.deleted || budget.monthlyLimit <= 0) continue // an unset limit never alerts
                val key = keyOf(me, budget.id)
                val record = parse(store.get(key))
                if (record != null && record.month == month && record.limit != budget.monthlyLimit) {
                    store.put(key, format(month, 0, budget.monthlyLimit)) // the limit changed: start this month over
                }
                val alerted = if (record != null && record.month == month && record.limit == budget.monthlyLimit) record.level else 0
                val now = codeOf(usage.level)
                if (now <= alerted) continue
                val alert = BudgetAlert(
                    budgetId = budget.id,
                    level = usage.level,
                    title = BudgetText.alertTitle(usage, snapshot),
                    text = BudgetText.alertBody(usage, month),
                    notificationId = notificationId(budget.id, usage.level),
                )
                val sent = try {
                    post(alert)
                } catch (e: Exception) {
                    false // one budget that can't be announced must not stop the others
                }
                if (sent) {
                    store.put(key, format(month, now, budget.monthlyLimit))
                    posted += alert
                }
            }
            posted
        }

    private class Record(val month: String, val level: Int, val limit: Long)

    private fun parse(raw: String?): Record? {
        val parts = raw?.split(":", limit = 3) ?: return null
        if (parts.size != 3) return null
        val level = parts[1].toIntOrNull() ?: return null
        val limit = parts[2].toLongOrNull() ?: return null
        return Record(parts[0], level, limit)
    }

    companion object {
        /** `budget_alert_<meId>_<budgetId>`. */
        fun keyOf(me: String, budgetId: String) = "budget_alert_${me}_$budgetId"

        /** `2026-11:80:600000`. */
        fun format(month: String, level: Int, limit: Long) = "$month:$level:$limit"

        fun codeOf(level: BudgetLevel): Int = when (level) {
            BudgetLevel.OK -> 0
            BudgetLevel.WARN -> BudgetRules.WARN_PERCENT
            BudgetLevel.OVER -> BudgetRules.OVER_PERCENT
        }

        /** The same budget and level always get the same id, so a repeat replaces the earlier notification instead of stacking. */
        fun notificationId(budgetId: String, level: BudgetLevel): Int = "$budgetId:${codeOf(level)}".hashCode()
    }
}

/** Shows a [BudgetAlert] with the platform's notification API. Needs nothing beyond the Android SDK. */
object BudgetNotifier {
    /** Asked for at run time on Android 13 and up. */
    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"
    const val CHANNEL_ID = "budget_alerts"

    private const val ANDROID_13 = 33

    /** True on Android 13+ when the user has not allowed notifications yet: the screen that offers alerts asks for [PERMISSION] then. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_13 &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED

    /**
     * Posts [alert]. Returns false, and posts nothing, when the permission is missing, notifications are switched off
     * for the app, or the user has turned off the "Budget alerts" channel, so the alert is not counted as sent.
     * Tapping it opens the app.
     */
    fun post(context: Context, alert: BudgetAlert): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (needsPermission(context) || !manager.areNotificationsEnabled()) return false
        ensureChannel(manager)
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, alert.notificationId, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            manager.notify(alert.notificationId, notification)
            true
        } catch (e: SecurityException) {
            false // the permission was taken away between the check and now
        }
    }

    /** Creates the channel the first time it is needed; creating it again is harmless. */
    private fun ensureChannel(manager: NotificationManager) {
        val channel = NotificationChannel(CHANNEL_ID, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when a budget reaches 80% and 100% of its limit for the month."
        }
        manager.createNotificationChannel(channel)
    }
}
