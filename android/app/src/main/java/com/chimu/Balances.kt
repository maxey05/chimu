package com.chimu

/**
 * Wallet balance math from DESIGN.md. A balance is derived and never stored:
 *
 *     opening_balance + income - expense - transfers out + transfers in
 *
 * over non-deleted rows. Tombstoned transactions are ignored, and tombstoned
 * ("archived") wallets get no balance.
 */
object Balances {
    /** Balance per wallet id for every non-deleted wallet. */
    fun forWallets(wallets: List<Wallet>, txns: List<Txn>): Map<String, Long> {
        val balances = HashMap<String, Long>()
        for (wallet in wallets) {
            if (!wallet.deleted) balances[wallet.id] = wallet.openingBalance
        }

        for (txn in txns) {
            if (txn.deleted) continue
            when (txn.type) {
                TxnType.EXPENSE -> adjust(balances, txn.walletId, -txn.amount)
                TxnType.INCOME -> adjust(balances, txn.walletId, txn.amount)
                TxnType.TRANSFER -> {
                    adjust(balances, txn.walletId, -txn.amount)
                    txn.toWalletId?.let { adjust(balances, it, txn.amount) }
                }
            }
        }
        return balances
    }

    /** Balance of one wallet, or 0 if the wallet is deleted. */
    fun forWallet(wallet: Wallet, txns: List<Txn>): Long =
        forWallets(listOf(wallet), txns)[wallet.id] ?: 0L

    /** A row that points at a wallet we have no balance for (deleted, or someone else's) changes nothing. */
    private fun adjust(balances: MutableMap<String, Long>, walletId: String, delta: Long) {
        val current = balances[walletId] ?: return
        balances[walletId] = current + delta
    }
}
