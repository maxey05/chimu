package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancesTest {
    private val owner = "owner-1"

    private fun wallet(id: String, opening: Long = 0, deleted: Boolean = false) =
        Wallet(id, owner, "Wallet $id", WalletType.CASH, opening, updatedAt = 1, deleted = deleted)

    private fun txn(
        id: String,
        type: TxnType,
        wallet: String,
        amount: Long,
        to: String? = null,
        deleted: Boolean = false,
    ) = Txn(
        id = id, ownerId = owner, type = type, walletId = wallet, toWalletId = to,
        amount = amount, date = "2026-11-01", category = null, merchant = null, note = null,
        isBusiness = false, updatedAt = 1, deleted = deleted,
    )

    @Test
    fun noTransactions_balanceIsTheOpeningBalance() {
        val balances = Balances.forWallets(listOf(wallet("a", opening = 50000)), emptyList())
        assertEquals(50000L, balances["a"])
    }

    @Test
    fun expenseSubtracts() {
        val balances = Balances.forWallets(
            listOf(wallet("a", opening = 100000)),
            listOf(txn("t1", TxnType.EXPENSE, "a", 25050)),
        )
        assertEquals(74950L, balances["a"])
    }

    @Test
    fun incomeAdds() {
        val balances = Balances.forWallets(
            listOf(wallet("a", opening = 100000)),
            listOf(txn("t1", TxnType.INCOME, "a", 25050)),
        )
        assertEquals(125050L, balances["a"])
    }

    @Test
    fun transferMovesMoneyOutOfOneWalletAndIntoTheOther() {
        val balances = Balances.forWallets(
            listOf(wallet("a", opening = 100000), wallet("b", opening = 5000)),
            listOf(txn("t1", TxnType.TRANSFER, "a", 30000, to = "b")),
        )
        assertEquals(70000L, balances["a"])
        assertEquals(35000L, balances["b"])
        // Money only moved: the total is unchanged.
        assertEquals(105000L, balances.values.sum())
    }

    @Test
    fun allKindsTogether() {
        val wallets = listOf(wallet("cash", opening = 100000), wallet("gcash", opening = 0))
        val txns = listOf(
            txn("1", TxnType.INCOME, "cash", 500000),                 // salary
            txn("2", TxnType.EXPENSE, "cash", 12345),                 // lunch
            txn("3", TxnType.TRANSFER, "cash", 200000, to = "gcash"), // cash-in
            txn("4", TxnType.EXPENSE, "gcash", 4950),                 // load
            txn("5", TxnType.TRANSFER, "gcash", 10000, to = "cash"),  // back out
        )
        val balances = Balances.forWallets(wallets, txns)
        // cash: 100000 + 500000 - 12345 - 200000 + 10000
        assertEquals(397655L, balances["cash"])
        // gcash: 0 - 4950 + 200000 - 10000
        assertEquals(185050L, balances["gcash"])
    }

    @Test
    fun deletedTransactionsAreIgnored() {
        val balances = Balances.forWallets(
            listOf(wallet("a", opening = 100000), wallet("b")),
            listOf(
                txn("t1", TxnType.EXPENSE, "a", 40000, deleted = true),
                txn("t2", TxnType.INCOME, "a", 90000, deleted = true),
                txn("t3", TxnType.TRANSFER, "a", 30000, to = "b", deleted = true),
            ),
        )
        assertEquals(100000L, balances["a"])
        assertEquals(0L, balances["b"])
    }

    @Test
    fun deletingATransactionRestoresTheBalance() {
        val wallets = listOf(wallet("a", opening = 100000))
        val live = txn("t1", TxnType.EXPENSE, "a", 40000)
        assertEquals(60000L, Balances.forWallets(wallets, listOf(live))["a"])
        assertEquals(100000L, Balances.forWallets(wallets, listOf(live.copy(deleted = true)))["a"])
    }

    @Test
    fun deletedWalletsGetNoBalance() {
        val balances = Balances.forWallets(
            listOf(wallet("a", opening = 100000), wallet("old", opening = 777, deleted = true)),
            emptyList(),
        )
        assertTrue(balances.containsKey("a"))
        assertFalse(balances.containsKey("old"))
    }

    @Test
    fun transferIntoAnArchivedWalletStillTakesMoneyOutOfTheSource() {
        val balances = Balances.forWallets(
            listOf(wallet("a", opening = 100000), wallet("old", deleted = true)),
            listOf(txn("t1", TxnType.TRANSFER, "a", 30000, to = "old")),
        )
        assertEquals(70000L, balances["a"])
        assertFalse(balances.containsKey("old"))
    }

    @Test
    fun transactionsOnUnknownWalletsChangeNothing() {
        val balances = Balances.forWallets(
            listOf(wallet("a", opening = 100000)),
            listOf(txn("t1", TxnType.EXPENSE, "someone-elses", 99999)),
        )
        assertEquals(mapOf("a" to 100000L), balances)
    }

    @Test
    fun openingBalanceMayBeNegative() {
        val balances = Balances.forWallets(
            listOf(wallet("card", opening = -250000)),
            listOf(txn("t1", TxnType.EXPENSE, "card", 10000)),
        )
        assertEquals(-260000L, balances["card"])
    }

    @Test
    fun forWallet_matchesForWallets_andIsZeroForADeletedWallet() {
        val a = wallet("a", opening = 100000)
        val txns = listOf(txn("t1", TxnType.EXPENSE, "a", 1))
        assertEquals(99999L, Balances.forWallet(a, txns))
        assertEquals(0L, Balances.forWallet(a.copy(deleted = true), txns))
    }
}
