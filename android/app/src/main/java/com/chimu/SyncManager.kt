package com.chimu

import android.content.SharedPreferences
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the Home screen shows about syncing. */
data class SyncStatus(
    val running: Boolean = false,
    /** Epoch ms of the last sync that reached the server, or null if never. */
    val lastSyncedAt: Long? = null,
    /** Rows with changes not yet sent. */
    val pending: Int = 0,
    /** What went wrong last time, in words for the user. Cleared by the next clean sync. */
    val error: String? = null,
)

enum class SyncOutcome {
    /** Everything sent and received. */
    DONE,

    /** Nothing to do: nobody signed in, no Supabase keys, or another sync already covers this request. */
    SKIPPED,

    /** The server couldn't be reached. Try again later (WorkManager asks for a retry). */
    RETRY,

    /** The sync ran but something stays unsent (refused rows, failed photos) or failed unexpectedly. */
    PROBLEM,
}

/** The words the Home screen uses. Pure, so the wording and the time maths are tested. */
object SyncText {
    fun lastSynced(at: Long?, now: Long): String {
        if (at == null) return "Not synced yet"
        val minutes = ((now - at) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "Synced just now"
            minutes < 60 -> "Synced $minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
            minutes < 24 * 60 -> (minutes / 60).let { "Synced $it ${if (it == 1L) "hour" else "hours"} ago" }
            else -> (minutes / (24 * 60)).let { "Synced $it ${if (it == 1L) "day" else "days"} ago" }
        }
    }

    fun pending(count: Int): String = when (count) {
        0 -> "Nothing waiting to sync"
        1 -> "1 change waiting to sync"
        else -> "$count changes waiting to sync"
    }
}

/**
 * Runs [SyncEngine] for the signed-in user and tells the app how it went. Triggers (DESIGN.md ->
 * Sync protocol): app start ([start]), shortly after a save ([onLocalChange], debounced), the Home
 * screen's pull-to-refresh / Sync now button ([requestNow]), and WorkManager every ~30 minutes
 * ([SyncWorker]).
 *
 * Only one sync runs at a time. A request that arrives while one is running waits for it and then
 * runs again, because the running one may have started before the change; a request that a later
 * sync already covers is dropped.
 *
 * @param onSynced called after every sync that may have changed local rows, so the screens reload.
 */
class SyncManager(
    private val store: LocalStore,
    private val prefs: SharedPreferences,
    private val account: Account,
    private val onSynced: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var started = 0L // number of syncs begun; written under [lock]
    private var lastOutcome = SyncOutcome.SKIPPED // written under [lock]
    private var debounce: Job? = null

    // Created on first use: it needs the Supabase client, which needs the keys from local.properties.
    private val engine by lazy { SyncEngine(store, SupaRemote(Supa.client)) }

    /** A user was opened (app start or sign-in): show what the phone remembers, then sync. */
    fun start(owner: String) {
        scope.launch {
            _status.value = SyncStatus(
                lastSyncedAt = prefs.getLong(lastSyncedKey(owner), 0L).takeIf { it > 0 },
                pending = store.pendingCount(owner),
                error = prefs.getString(errorKey(owner), null),
            )
            syncNow()
        }
    }

    /** Signed out: nothing to show until someone signs in. */
    fun stop() {
        synchronized(this) { debounce?.cancel() }
        _status.value = SyncStatus()
    }

    /** Called after every local write. Updates the pending count now and syncs once things go quiet. */
    fun onLocalChange() {
        val owner = account.identity.value?.userId ?: return
        synchronized(this) {
            debounce?.cancel()
            debounce = scope.launch {
                _status.update { it.copy(pending = store.pendingCount(owner)) }
                delay(DEBOUNCE_MS)
                syncNow()
            }
        }
    }

    /** Pull-to-refresh, Sync now. Returns at once; watch [status]. */
    fun requestNow() {
        scope.launch { syncNow() }
    }

    /** Runs a sync and waits for it. WorkManager uses this; screens use [requestNow]. */
    suspend fun syncNow(): SyncOutcome {
        val seen = started
        return lock.withLock {
            if (started > seen) return@withLock lastOutcome // a sync that began after this request has it covered
            started++
            lastOutcome = runLocked()
            lastOutcome
        }
    }

    /** A signed link to a receipt photo (own or a group member's). For RFC-004's viewer. */
    suspend fun photoUrl(receiptPath: String): String =
        withContext(Dispatchers.IO) { SupaRemote(Supa.client).photoUrl(receiptPath) }

    private suspend fun runLocked(): SyncOutcome {
        if (!Supa.isConfigured) return SyncOutcome.SKIPPED
        val owner = account.identity.value?.userId ?: return SyncOutcome.SKIPPED

        _status.update { it.copy(running = true) }
        var outcome = SyncOutcome.DONE
        var error: String? = null
        var reachedServer = false
        try {
            withContext(Dispatchers.IO) {
                requireSession(owner)
                val report = engine.sync(owner)
                reachedServer = true
                if (!report.clean) {
                    outcome = SyncOutcome.PROBLEM
                    error = describe(report)
                }
            }
        } catch (e: CancellationException) {
            _status.update { it.copy(running = false) }
            throw e
        } catch (e: SyncUnavailable) {
            outcome = SyncOutcome.RETRY
            error = "${e.message}. Your entries are saved on this phone and will sync later."
        } catch (e: Exception) {
            outcome = SyncOutcome.PROBLEM
            error = "Sync failed: ${e.message ?: e::class.simpleName}. Your entries are saved on this phone."
        }

        val syncedAt = if (reachedServer) System.currentTimeMillis() else null
        val editor = prefs.edit()
        if (syncedAt != null) editor.putLong(lastSyncedKey(owner), syncedAt)
        if (error == null) editor.remove(errorKey(owner)) else editor.putString(errorKey(owner), error)
        editor.apply()

        val pending = withContext(Dispatchers.IO) { store.pendingCount(owner) }
        _status.update {
            it.copy(
                running = false,
                lastSyncedAt = syncedAt ?: it.lastSyncedAt,
                pending = pending,
                error = error,
            )
        }
        try {
            onSynced()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The sync itself is done; a failed screen reload must not turn it into an error.
        }
        return outcome
    }

    /** The Supabase session restores itself at launch. Wait for that, and make sure it is [owner]'s. */
    private suspend fun requireSession(owner: String) {
        val auth = Supa.client.auth
        withTimeoutOrNull(SESSION_WAIT_MS) { auth.awaitInitialization() }
        val session = auth.currentSessionOrNull() ?: throw SyncUnavailable("You are not signed in to the server yet")
        if (session.user?.id != owner) throw SyncUnavailable("The server session belongs to a different account")
    }

    private fun describe(report: SyncReport): String {
        val parts = buildList {
            if (report.rejected > 0) add("${report.rejected} changes were refused by the server")
            if (report.photoFailures > 0) add("${report.photoFailures} receipt photos couldn't be uploaded")
        }
        return parts.joinToString(" and ") + ". They stay on this phone."
    }

    private fun lastSyncedKey(owner: String) = "sync_last_ok_$owner"

    private fun errorKey(owner: String) = "sync_last_error_$owner"

    private companion object {
        const val DEBOUNCE_MS = 3_000L
        const val SESSION_WAIT_MS = 10_000L
    }
}
