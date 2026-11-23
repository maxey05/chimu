package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerTest {
    private fun txn(
        id: String,
        type: TxnType = TxnType.EXPENSE,
        wallet: String = "cash",
        amount: Long = 1000,
        to: String? = null,
        date: String = "2026-11-10",
        category: String? = "food",
        merchant: String? = null,
        note: String? = null,
        updatedAt: Long = 1,
        deleted: Boolean = false,
    ) = Txn(
        id = id, ownerId = "o", type = type, walletId = wallet, toWalletId = to,
        amount = amount, date = date, category = category, merchant = merchant, note = note,
        isBusiness = false, updatedAt = updatedAt, deleted = deleted,
    )

    private fun ids(list: List<Txn>) = list.map { it.id }

    // --- filter --------------------------------------------------------------

    @Test
    fun emptyFilter_returnsEverythingNewestFirst() {
        val txns = listOf(
            txn("old", date = "2026-10-31"),
            txn("new", date = "2026-11-20"),
            txn("mid", date = "2026-11-02"),
        )
        assertEquals(listOf("new", "mid", "old"), ids(Ledger.filter(txns, TxnFilter())))
    }

    @Test
    fun sameDate_newestEditFirst() {
        val txns = listOf(
            txn("a", updatedAt = 5),
            txn("b", updatedAt = 9),
            txn("c", updatedAt = 7),
        )
        assertEquals(listOf("b", "c", "a"), ids(Ledger.filter(txns, TxnFilter())))
    }

    @Test
    fun deletedRowsNeverAppear() {
        val txns = listOf(txn("keep"), txn("gone", deleted = true))
        assertEquals(listOf("keep"), ids(Ledger.filter(txns, TxnFilter())))
    }

    @Test
    fun filterByMonth() {
        val txns = listOf(
            txn("nov", date = "2026-11-30"),
            txn("oct", date = "2026-10-31"),
            txn("nov2", date = "2026-11-01"),
        )
        assertEquals(listOf("nov", "nov2"), ids(Ledger.filter(txns, TxnFilter(month = "2026-11"))))
    }

    @Test
    fun filterByWallet_transferAppearsUnderBothWallets() {
        val txns = listOf(
            txn("cashExpense", wallet = "cash"),
            txn("gcashExpense", wallet = "gcash"),
            txn("move", type = TxnType.TRANSFER, wallet = "cash", to = "gcash", category = null),
        )
        assertEquals(setOf("cashExpense", "move"), ids(Ledger.filter(txns, TxnFilter(walletId = "cash"))).toSet())
        assertEquals(setOf("gcashExpense", "move"), ids(Ledger.filter(txns, TxnFilter(walletId = "gcash"))).toSet())
    }

    @Test
    fun filterByCategoryAndType() {
        val txns = listOf(
            txn("food", category = "food"),
            txn("groc", category = "groceries"),
            txn("salary", type = TxnType.INCOME, category = "salary"),
        )
        assertEquals(listOf("groc"), ids(Ledger.filter(txns, TxnFilter(category = "groceries"))))
        assertEquals(listOf("salary"), ids(Ledger.filter(txns, TxnFilter(type = TxnType.INCOME))))
    }

    @Test
    fun search_matchesMerchantAndNote_ignoringCaseAndSpaces() {
        val txns = listOf(
            txn("a", merchant = "Jollibee SM Mall"),
            txn("b", note = "lunch with JOLLY"),
            txn("c", merchant = "Mercury Drug"),
            txn("d"),
        )
        assertEquals(listOf("a"), ids(Ledger.filter(txns, TxnFilter(search = "  jollibee "))))
        assertEquals(listOf("b"), ids(Ledger.filter(txns, TxnFilter(search = "jolly"))))
        assertEquals(setOf("a", "b", "c", "d"), ids(Ledger.filter(txns, TxnFilter(search = "   "))).toSet())
    }

    @Test
    fun filtersCombine() {
        val txns = listOf(
            txn("hit", date = "2026-11-05", wallet = "cash", category = "food", merchant = "Jollibee"),
            txn("wrongMonth", date = "2026-10-05", wallet = "cash", category = "food", merchant = "Jollibee"),
            txn("wrongWallet", date = "2026-11-05", wallet = "gcash", category = "food", merchant = "Jollibee"),
            txn("wrongCategory", date = "2026-11-05", wallet = "cash", category = "travel", merchant = "Jollibee"),
        )
        val f = TxnFilter(month = "2026-11", walletId = "cash", category = "food", type = TxnType.EXPENSE, search = "jolli")
        assertEquals(listOf("hit"), ids(Ledger.filter(txns, f)))
    }

    // --- month totals --------------------------------------------------------

    @Test
    fun monthTotals_sumsExpensesAndIncomeOfThatMonthOnly() {
        val txns = listOf(
            txn("e1", amount = 10000, date = "2026-11-01"),
            txn("e2", amount = 2550, date = "2026-11-30"),
            txn("i1", type = TxnType.INCOME, amount = 500000, date = "2026-11-15", category = "salary"),
            txn("oct", amount = 99999, date = "2026-10-31"),
        )
        assertEquals(MonthTotals(spent = 12550, income = 500000), Ledger.monthTotals(txns, "2026-11"))
    }

    @Test
    fun monthTotals_ignoreTransfersAndDeletedRows() {
        val txns = listOf(
            txn("e1", amount = 10000),
            txn("move", type = TxnType.TRANSFER, to = "gcash", amount = 777777, category = null),
            txn("gone", amount = 55555, deleted = true),
            txn("goneIncome", type = TxnType.INCOME, amount = 44444, deleted = true),
        )
        assertEquals(MonthTotals(spent = 10000, income = 0), Ledger.monthTotals(txns, "2026-11"))
    }

    @Test
    fun monthTotals_emptyMonthIsZero() {
        assertEquals(MonthTotals(0, 0), Ledger.monthTotals(emptyList(), "2026-11"))
    }

    // --- months --------------------------------------------------------------

    @Test
    fun months_distinctNewestFirst_skippingDeleted() {
        val txns = listOf(
            txn("a", date = "2026-09-01"),
            txn("b", date = "2026-11-20"),
            txn("c", date = "2026-11-02"),
            txn("d", date = "2027-01-05", deleted = true),
            txn("e", date = "2026-10-15"),
        )
        assertEquals(listOf("2026-11", "2026-10", "2026-09"), Ledger.months(txns))
    }

    @Test
    fun monthOf_takesYearAndMonth() {
        assertEquals("2026-11", Ledger.monthOf("2026-11-23"))
    }

    @Test
    fun labels_areReadable_andForgivingOfBadInput() {
        assertEquals("November 2026", Ledger.monthLabel("2026-11"))
        assertEquals("Nov 3, 2026", Ledger.dateLabel("2026-11-03"))
        assertEquals("Feb 29, 2028", Ledger.dateLabel("2028-02-29"))
        assertEquals("not a month", Ledger.monthLabel("not a month"))
        assertEquals("2026-13-45", Ledger.dateLabel("2026-13-45"))
    }

    // --- categories ----------------------------------------------------------

    @Test
    fun categories_matchTheDesignLists() {
        assertEquals(10, Categories.expense.size)
        assertEquals(4, Categories.income.size)
        assertEquals("Food & Dining", Categories.label("food"))
        assertEquals("Salary", Categories.label("salary"))
        assertEquals("Other", Categories.label("other"))
        assertEquals("", Categories.label(null))
        assertEquals("mystery", Categories.label("mystery"))
        assertEquals(emptyList<Category>(), Categories.forType(TxnType.TRANSFER))
    }
}
