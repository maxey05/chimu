package com.chimu

import android.app.Application
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app-wide objects so they survive screen rotation and are created once. */
class ChimuApp : Application() {
    lateinit var repo: Repo
        private set

    lateinit var account: Account
        private set

    lateinit var sync: SyncManager
        private set

    /** Create, join, leave and remove for tracking groups (RFC-006). */
    lateinit var groups: GroupManager
        private set

    /** Which apps and SMS senders are auto-logged (RFC-005). */
    lateinit var autoLog: AutoLogSettings
        private set

    /** The user's recurring rules: create, edit, pause, delete (RFC-010). The server logs the entries; the phone reminds. */
    lateinit var recurring: RecurringRepo
        private set

    private lateinit var intake: PaymentIntake

    /** For work that must outlive a screen or a service callback: logging an incoming notification or SMS. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("chimu", MODE_PRIVATE)
        val db = Db(this)
        account = Account(prefs)
        // The manager reloads the screens' data through `repo` after a sync; the repo asks the
        // manager to sync after a save. `repo` is only read when a sync finishes, never during setup.
        sync = SyncManager(LocalStore(db, prefs), prefs, account, onSynced = { repo.refresh() })
        // Budget alerts (RFC-009): the repo hands over fresh data after every save and every sync. The notifier is only
        // asked when a budget reaches a new level, and its answer decides whether that level is recorded as sent.
        val budgetAlerts = BudgetAlerts(PrefsAlertStore(prefs), post = { BudgetNotifier.post(this, it) })
        repo = Repo(
            db, prefs,
            onChanged = sync::onLocalChange,
            onReloaded = { me, budgets, txns, groups -> budgetAlerts.checkAll(me, budgets, txns, groups, LocalDate.now()) },
        )
        groups = GroupManager(SupaGroupApi, afterChange = sync::afterMembershipChange)
        autoLog = AutoLogSettings(prefs)
        intake = PaymentIntake(InboxStore(db), reader = SupabaseReader, online = { Connectivity.isOnline(this) })
        recurring = RecurringRepo(RecurringStore(db), onChanged = sync::onLocalChange)
        if (Supa.isConfigured) SyncWorker.schedule(this)
        if (Supa.isConfigured) ReminderWorker.schedule(this) // daily, reads only what is on the phone (RFC-010)
    }

    /**
     * Logs one notification or SMS for the signed-in user as an Inbox item (RFC-005). Nobody signed in
     * means nobody to own it, so it is dropped. Never throws: a message that can't be stored is lost
     * quietly, not allowed to crash the listener or the receiver.
     */
    suspend fun logMessage(message: IncomingMessage, readTimeoutMs: Long = PaymentIntake.READ_TIMEOUT_MS) {
        try {
            val owner = account.identity.value?.userId ?: return
            val result = intake.handle(owner, message, readTimeoutMs)
            if (result is IntakeResult.Added) {
                repo.refresh() // if a screen is open, show the new item
                if (result.createdWallet) sync.onLocalChange() // a new wallet is an ordinary row that syncs
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Intentionally quiet: see above.
        }
    }
}

/** The text reader, created only when it is used so a phone without Supabase keys never builds the client. */
private object SupabaseReader : MessageReader {
    override suspend fun read(text: String, source: String): ExtractedReceipt {
        if (!Supa.isConfigured) throw SyncUnavailable("The reader is not set up on this phone")
        return SupaRemote(Supa.client).read(text, source)
    }
}
