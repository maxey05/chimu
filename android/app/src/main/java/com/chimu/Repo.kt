package com.chimu

import android.content.SharedPreferences
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * What the screens read and write. It wraps [Db] for the signed-in user, runs the disk work off
 * the main thread, and republishes the lists after every write so the UI updates.
 *
 * Every query is scoped to the open user's `owner_id`, so signing out and in as someone else on
 * the same phone never shows (or edits) the first person's rows. Failures are thrown, never
 * swallowed: the caller shows them and the form keeps what the user typed (RULES 20).
 */
class Repo(
    private val db: Db,
    private val prefs: SharedPreferences,
    /** Called after every local write, so the sync engine can send it soon (RFC-003). */
    private val onChanged: () -> Unit = {},
) {
    private val _allWallets = MutableStateFlow<List<Wallet>>(emptyList())
    private val _txns = MutableStateFlow<List<Txn>>(emptyList())
    private val _inbox = MutableStateFlow<List<Txn>>(emptyList())
    private val _groups = MutableStateFlow(GroupSnapshot())
    private val _openOwner = MutableStateFlow<String?>(null)

    @Volatile
    private var ownerId: String? = null

    /** Every wallet of the open user, archived ones included (so old rows can still show a name). */
    val allWallets: StateFlow<List<Wallet>> = _allWallets.asStateFlow()

    /** The open user's live (non-deleted) transactions. Unconfirmed Inbox items are not in here: see [inbox]. */
    val txns: StateFlow<List<Txn>> = _txns.asStateFlow()

    /** Auto-logged payments waiting for the user to confirm, edit or dismiss them (RFC-005). They live on this phone only. */
    val inbox: StateFlow<List<Txn>> = _inbox.asStateFlow()

    /**
     * What the Group tab shows (RFC-006): the user's groups, their members and those members' cached
     * transactions. Read-only, filled by sync; empty when the user is in no group.
     */
    val groups: StateFlow<GroupSnapshot> = _groups.asStateFlow()

    /** The user id whose data is loaded, or null while nothing is open. */
    val openOwner: StateFlow<String?> = _openOwner.asStateFlow()

    /**
     * Loads [owner]'s data. The first time a user ever opens the app on this phone they get a
     * default "Cash" wallet, so they can log something straight away.
     */
    suspend fun open(owner: String) = withContext(Dispatchers.IO) {
        ownerId = owner
        val seededKey = "seeded_$owner"
        if (!prefs.getBoolean(seededKey, false)) {
            if (db.wallets(owner).isEmpty()) {
                db.insertWallet(
                    Wallet(
                        id = UUID.randomUUID().toString(),
                        ownerId = owner,
                        name = DEFAULT_WALLET_NAME,
                        type = WalletType.CASH,
                        openingBalance = 0,
                    ),
                )
            }
            prefs.edit().putBoolean(seededKey, true).apply()
        }
        reload(owner)
        _openOwner.value = owner
    }

    /** Clears what is on screen. The rows stay in the database for next time. */
    fun close() {
        ownerId = null
        _openOwner.value = null
        _allWallets.value = emptyList()
        _txns.value = emptyList()
        _inbox.value = emptyList()
        _groups.value = GroupSnapshot()
    }

    /** Wallets that can be used for new entries. */
    fun activeWallets(): List<Wallet> = _allWallets.value.filter { !it.deleted }

    /** Name for display, with a marker when the wallet has been archived. */
    fun walletLabel(id: String?): String {
        val wallet = _allWallets.value.firstOrNull { it.id == id } ?: return "Unknown wallet"
        return if (wallet.deleted) "${wallet.name} (archived)" else wallet.name
    }

    suspend fun saveWallet(wallet: Wallet) = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val exists = _allWallets.value.any { it.id == wallet.id }
        val toSave = wallet.copy(ownerId = owner)
        if (exists) db.updateWallet(toSave) else db.insertWallet(toSave)
        reload(owner)
        onChanged()
    }

    /** Returns false, changing nothing, if this is the last active wallet (nothing could be logged without one). */
    suspend fun archiveWallet(id: String): Boolean = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val active = _allWallets.value.filter { !it.deleted }
        if (active.none { it.id == id } || active.size <= 1) return@withContext false
        db.archiveWallet(owner, id)
        reload(owner)
        onChanged()
        true
    }

    suspend fun saveTxn(txn: Txn) = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val exists = _txns.value.any { it.id == txn.id }
        val toSave = txn.copy(ownerId = owner)
        if (exists) db.updateTxn(toSave) else db.insertTxn(toSave)
        reload(owner)
        onChanged()
    }

    /**
     * Logs a scanned receipt (RFC-004): a `pending` row on the first active wallet with amount 0 and
     * today's date as placeholders, pointing at the shrunk photo at [photoPath]. Sync uploads the
     * photo and has it read (see [SyncEngine]); until then the row waits, offline or not. The row's
     * id is the photo's file name, so the two always belong together.
     */
    suspend fun createScan(id: String, photoPath: String) = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val wallet = _allWallets.value.firstOrNull { !it.deleted }
            ?: throw IllegalStateException("You have no wallets. Add one under More, then Wallets.")
        db.insertTxn(
            Txn(
                id = id,
                ownerId = owner,
                type = TxnType.EXPENSE,
                walletId = wallet.id,
                toWalletId = null,
                amount = 0,
                date = LocalDate.now().toString(),
                category = null,
                merchant = null,
                note = null,
                isBusiness = false,
                source = "receipt",
                status = "pending",
                localPhoto = photoPath,
            ),
        )
        reload(owner)
        onChanged()
    }

    /**
     * Saves the Review screen or an Inbox item: the entry becomes `confirmed` and counts as checked by
     * the user. An Inbox item starts to sync from here (`dirty = 1`, then [onChanged]).
     */
    suspend fun confirmTxn(txn: Txn) = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        db.confirmTxn(txn.copy(ownerId = owner))
        reload(owner)
        onChanged()
    }

    /** A transaction in the user's groups (theirs or another member's) by id, for the read-only detail screen. Null if the cache no longer has it. */
    fun groupTxn(id: String): Txn? = _groups.value.txns.firstOrNull { it.id == id }

    /** One of the open user's entries by id, an Inbox item too. Null if there is none. */
    fun find(id: String): Txn? = _txns.value.firstOrNull { it.id == id } ?: _inbox.value.firstOrNull { it.id == id }

    /** The payment a scanned receipt probably belongs to, or null (same amount, within 2 days, no photo yet). Inbox items count too. */
    fun matchFor(scanId: String, amount: Long, date: String): Txn? =
        ReceiptMatch.find(_txns.value + _inbox.value, scanId, amount, date)

    /**
     * Dismisses an Inbox item: it is deleted from this phone for good, which is allowed because it never
     * synced (RULES 9). Returns false if the item is gone or is no longer in the Inbox.
     */
    suspend fun dismissInbox(id: String): Boolean = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val done = db.dismissInbox(owner, id)
        reload(owner)
        done
    }

    /** Gives [paymentId] the scan's photo and receipt details and removes the scan entry. False if nothing changed. */
    suspend fun attachScan(scanId: String, paymentId: String): Boolean = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val done = db.attachScan(owner, scanId, paymentId)
        reload(owner)
        if (done) onChanged()
        done
    }

    suspend fun deleteTxn(id: String) = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        db.deleteTxn(owner, id)
        reload(owner)
        onChanged()
    }

    /** Re-reads the open user's rows, for after a sync changed the database underneath the screens. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        ownerId?.let { reload(it) }
    }

    private fun reload(owner: String) {
        _allWallets.value = db.wallets(owner)
        _txns.value = db.txns(owner)
        _inbox.value = db.inboxTxns(owner)
        _groups.value = loadGroups(owner)
    }

    /** Skips the big read when the user is in no group; [GroupRules.snapshot] drops anything the user may not see. */
    private fun loadGroups(owner: String): GroupSnapshot {
        val memberships = db.cachedMemberships()
        if (memberships.none { it.userId == owner }) return GroupSnapshot(me = owner)
        return GroupRules.snapshot(owner, db.cachedGroups(), memberships, db.cachedNames(), db.cachedTxns(), db.cachedWallets())
    }

    private fun requireOwner(): String = checkNotNull(ownerId) { "No user is open" }

    companion object {
        const val DEFAULT_WALLET_NAME = "Cash"
    }
}
