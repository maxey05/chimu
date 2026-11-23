package com.chimu

import android.content.SharedPreferences
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
class Repo(private val db: Db, private val prefs: SharedPreferences) {
    private val _allWallets = MutableStateFlow<List<Wallet>>(emptyList())
    private val _txns = MutableStateFlow<List<Txn>>(emptyList())
    private val _openOwner = MutableStateFlow<String?>(null)

    @Volatile
    private var ownerId: String? = null

    /** Every wallet of the open user, archived ones included (so old rows can still show a name). */
    val allWallets: StateFlow<List<Wallet>> = _allWallets.asStateFlow()

    /** The open user's live (non-deleted) transactions. */
    val txns: StateFlow<List<Txn>> = _txns.asStateFlow()

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
    }

    /** Returns false, changing nothing, if this is the last active wallet (nothing could be logged without one). */
    suspend fun archiveWallet(id: String): Boolean = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val active = _allWallets.value.filter { !it.deleted }
        if (active.none { it.id == id } || active.size <= 1) return@withContext false
        db.archiveWallet(owner, id)
        reload(owner)
        true
    }

    suspend fun saveTxn(txn: Txn) = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        val exists = _txns.value.any { it.id == txn.id }
        val toSave = txn.copy(ownerId = owner)
        if (exists) db.updateTxn(toSave) else db.insertTxn(toSave)
        reload(owner)
    }

    suspend fun deleteTxn(id: String) = withContext(Dispatchers.IO) {
        val owner = requireOwner()
        db.deleteTxn(owner, id)
        reload(owner)
    }

    private fun reload(owner: String) {
        _allWallets.value = db.wallets(owner)
        _txns.value = db.txns(owner)
    }

    private fun requireOwner(): String = checkNotNull(ownerId) { "No user is open" }

    companion object {
        const val DEFAULT_WALLET_NAME = "Cash"
    }
}
