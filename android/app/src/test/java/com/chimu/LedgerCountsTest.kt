package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The per-choice counts shown in the Transactions filter menus (v5 design). */
class LedgerCountsTest {
    private fun txn(
        id: String,
        date: String,
        type: TxnType = TxnType.EXPENSE,
        category: String? = "food",
        merchant: String? = "Shop",
        deleted: Boolean = false,
    ) = Txn(
        id = id, ownerId = "me", type = type, walletId = "w1", toWalletId = null, amount = 10000,
        date = date, category = category, merchant = merchant, note = null, isBusiness = false, deleted = deleted,
    )

    private val rows = listOf(
        txn("1", "2026-11-26", category = "food"),
        txn("2", "2026-11-25", category = "groceries"),
        txn("3", "2026-11-24", TxnType.INCOME, "salary"),
        txn("4", "2026-10-30", category = "food"),
        txn("5", "2026-10-02", TxnType.TRANSFER, category = null),
        txn("6", "2026-10-01", category = "food", deleted = true), // deleted rows never count
    )

    @Test
    fun month_counts_cover_every_month_and_skip_deleted_rows() {
        val c = Ledger.optionCounts(rows, TxnFilter(), FilterPart.MONTH)
        assertEquals(5, c[null])
        assertEquals(3, c["2026-11"])
        assertEquals(2, c["2026-10"])
    }

    @Test
    fun month_counts_ignore_the_month_filter_itself() {
        val c = Ledger.optionCounts(rows, TxnFilter(month = "2026-11"), FilterPart.MONTH)
        assertEquals(5, c[null])
        assertEquals(2, c["2026-10"])
    }

    @Test
    fun month_counts_keep_the_other_filters() {
        val c = Ledger.optionCounts(rows, TxnFilter(type = TxnType.EXPENSE), FilterPart.MONTH)
        assertEquals(3, c[null])
        assertEquals(2, c["2026-11"])
        assertEquals(1, c["2026-10"])
    }

    @Test
    fun type_counts_use_the_type_slugs() {
        val c = Ledger.optionCounts(rows, TxnFilter(), FilterPart.TYPE)
        assertEquals(5, c[null])
        assertEquals(3, c[TxnType.EXPENSE.slug])
        assertEquals(1, c[TxnType.INCOME.slug])
        assertEquals(1, c[TxnType.TRANSFER.slug])
    }

    @Test
    fun type_counts_keep_the_category_filter() {
        val c = Ledger.optionCounts(rows, TxnFilter(category = "food"), FilterPart.TYPE)
        assertEquals(2, c[null])
        assertEquals(2, c[TxnType.EXPENSE.slug])
        assertFalse(c.containsKey(TxnType.INCOME.slug))
    }

    @Test
    fun category_counts_leave_out_rows_with_no_category_except_in_all() {
        val c = Ledger.optionCounts(rows, TxnFilter(), FilterPart.CATEGORY)
        assertEquals(5, c[null]) // the transfer has no category but still counts under "All"
        assertEquals(2, c["food"])
        assertEquals(1, c["groceries"])
        assertEquals(1, c["salary"])
    }

    @Test
    fun category_counts_keep_the_type_filter() {
        val c = Ledger.optionCounts(rows, TxnFilter(type = TxnType.EXPENSE), FilterPart.CATEGORY)
        assertEquals(3, c[null])
        assertEquals(2, c["food"])
        assertFalse(c.containsKey("salary"))
    }

    @Test
    fun counts_follow_the_search_text() {
        val c = Ledger.optionCounts(rows, TxnFilter(search = "nothing like this"), FilterPart.TYPE)
        assertEquals(0, c[null])
        assertFalse(c.containsKey(TxnType.EXPENSE.slug))
    }

    @Test
    fun the_counts_agree_with_what_the_list_would_show() {
        // Picking a choice shows exactly as many rows as the menu promised.
        val counts = Ledger.optionCounts(rows, TxnFilter(type = TxnType.EXPENSE), FilterPart.MONTH)
        for ((month, n) in counts) {
            assertEquals(n, Ledger.filter(rows, TxnFilter(type = TxnType.EXPENSE, month = month)).size)
        }
    }
}
