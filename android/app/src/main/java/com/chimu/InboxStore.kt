package com.chimu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * [InboxLocal] over the phone's [Db]. Blocks on disk, so [PaymentIntake] runs it off the main thread
 * (the listener and receiver start their work on `Dispatchers.IO`).
 */
class InboxStore(private val db: Db) : InboxLocal {
    override fun wallets(owner: String): List<Wallet> = db.wallets(owner)

    override fun addWallet(wallet: Wallet) = db.insertWallet(wallet)

    override fun dedupCandidates(owner: String, refNo: String?, sinceMs: Long): List<Txn> =
        db.dedupCandidates(owner, refNo, sinceMs)

    override fun addInbox(txn: Txn) = db.insertTxn(txn)
}

object Connectivity {
    /** True when the phone has a network that says it reaches the internet. Needs ACCESS_NETWORK_STATE. */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
