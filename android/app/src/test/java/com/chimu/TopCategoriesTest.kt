package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind Home's "top categories this month" bars (RFC-008). */
class TopCategoriesTest {
    private val me = "user-me"
    private val ana = "user-ana"

    private fun txn(
        id: String,
        amount: Long,
        category: String? = "food",
        type: TxnType = TxnType.EXPENSE,
        date: String = "2026-11-10",
        owner: String = me,
        status: String = "confirmed",
        deleted: Boolean = false,
    ) = Txn(
        id = id, ownerId = owner, type = type, walletId = "w", toWalletId = if (type == TxnType.TRANSFER) "w2" else null,
        amount = amount, date = date, category = category, merchant = null, note = null, isBusiness = false,
        status = status, deleted = deleted,
    )

    private fun top(txns: List<Txn>, month: String = "2026-11", limit: Int = TopCategories.HOME_LIMIT) =
        TopCategories.top(txns, month, me, limit)

    private fun pairs(list: List<CategorySpend>) = list.map { it.slug to it.spent }

    @Test
    fun sumsEachCategory_biggestFirst() {
        val txns = listOf(
            txn("a", 10_000, "food"), txn("b", 5_000, "food"),
            txn("c", 30_000, "transport"),
            txn("d", 2_500, "health"),
        )
        assertEquals(listOf("transport" to 30_000L, "food" to 15_000L, "health" to 2_500L), pairs(top(txns)))
    }

    @Test
    fun carriesTheLabel() {
        val entry = top(listOf(txn("a", 100, "bills"))).single()
        assertEquals("Bills & Utilities", entry.label)
    }

    @Test
    fun tiesAreOrderedByLabelThenSlug_soTheOrderIsStable() {
        val txns = listOf(
            txn("a", 1_000, "transport"), txn("b", 1_000, "food"), txn("c", 1_000, "bills"), txn("d", 1_000, "health"),
        )
        // Labels: Bills & Utilities, Food & Dining, Health, Transport
        assertEquals(listOf("bills", "food", "health", "transport"), top(txns).map { it.slug })
        assertEquals(top(txns), top(txns.reversed()))
    }

    @Test
    fun onlyTheLimitIsReturned_butTheRestStillExistInTheFullList() {
        val txns = Categories.expense.mapIndexed { i, c -> txn("t$i", (i + 1) * 100L, c.slug) }
        assertEquals(5, top(txns).size)
        assertEquals(10, TopCategories.spentByCategory(txns, "2026-11", me).size)
        assertEquals(listOf("other", "travel", "entertainment", "education", "health"), top(txns).map { it.slug })
        assertEquals(3, top(txns, limit = 3).size)
    }

    @Test
    fun transfersAndIncomeNeverCount() {
        val txns = listOf(
            txn("a", 1_000, "food"),
            txn("t", 99_999, null, type = TxnType.TRANSFER),
            txn("i", 88_888, "salary", type = TxnType.INCOME),
        )
        assertEquals(listOf("food" to 1_000L), pairs(top(txns)))
    }

    @Test
    fun deletedRowsAndInboxItemsNeverCount() {
        val txns = listOf(
            txn("a", 1_000, "food"),
            txn("b", 5_000, "food", deleted = true),
            txn("c", 7_000, "transport", status = "inbox"),
        )
        assertEquals(listOf("food" to 1_000L), pairs(top(txns)))
    }

    @Test
    fun pendingAndExtractedScansCount_butAScanStillAtZeroIsLeftOut() {
        val txns = listOf(
            txn("a", 1_000, "food", status = "extracted"),
            txn("b", 2_000, "food", status = "pending"),
            txn("z", 0, "transport", status = "pending"),
        )
        assertEquals(listOf("food" to 3_000L), pairs(top(txns)))
    }

    @Test
    fun monthEdges_firstAndLastDayCount_theDaysAroundDoNot() {
        val txns = listOf(
            txn("oct31", 1, "food", date = "2026-10-31"),
            txn("nov1", 10, "food", date = "2026-11-01"),
            txn("nov30", 100, "food", date = "2026-11-30"),
            txn("dec1", 1_000, "food", date = "2026-12-01"),
        )
        assertEquals(listOf("food" to 110L), pairs(top(txns)))
        assertEquals(listOf("food" to 1L), pairs(top(txns, month = "2026-10")))
        assertEquals(listOf("food" to 1_000L), pairs(top(txns, month = "2026-12")))
    }

    @Test
    fun leapDayBelongsToFebruary_andAYearEndRowToDecember() {
        val txns = listOf(
            txn("leap", 5, "food", date = "2028-02-29"),
            txn("mar", 50, "food", date = "2028-03-01"),
            txn("dec", 500, "food", date = "2027-12-31"),
            txn("jan", 5_000, "food", date = "2028-01-01"),
        )
        assertEquals(listOf("food" to 5L), pairs(top(txns, month = "2028-02")))
        assertEquals(listOf("food" to 500L), pairs(top(txns, month = "2027-12")))
        assertEquals(listOf("food" to 5_000L), pairs(top(txns, month = "2028-01")))
    }

    @Test
    fun anEmptyMonthGivesAnEmptyList() {
        assertTrue(top(emptyList()).isEmpty())
        assertTrue(top(listOf(txn("a", 1_000, date = "2026-10-05"))).isEmpty())
    }

    @Test
    fun aMissingOrUnknownCategoryCountsAsOther() {
        val txns = listOf(
            txn("a", 100, null),
            txn("b", 200, "no-such-slug"),
            txn("c", 400, "other"),
            txn("d", 800, "salary"), // an income slug on an expense row is not an expense category
        )
        val entry = top(txns).single()
        assertEquals("other", entry.slug)
        assertEquals("Other", entry.label)
        assertEquals(1_500L, entry.spent)
    }

    @Test
    fun onlyTheGivenOwnersRowsCount() {
        val txns = listOf(txn("mine", 1_000, "food"), txn("theirs", 9_000, "food", owner = ana))
        assertEquals(listOf("food" to 1_000L), pairs(top(txns)))
        assertEquals(listOf("food" to 9_000L), pairs(TopCategories.top(txns, "2026-11", ana)))
    }

    @Test
    fun theCategoriesAddUpToLedgerMonthSpent() {
        val txns = listOf(
            txn("a", 12_345, "food"), txn("b", 55, null), txn("c", 700_000, "bills"),
            txn("d", 1_000, "groceries", date = "2026-11-30"), txn("e", 99, "food", date = "2026-10-31"),
            txn("f", 4_000, "x-unknown"), txn("g", 5_000, "food", deleted = true),
            txn("h", 6_000, null, type = TxnType.INCOME), txn("i", 7_000, null, type = TxnType.TRANSFER),
            txn("j", 0, "travel", status = "pending"),
        )
        val all = TopCategories.spentByCategory(txns, "2026-11", me)
        assertEquals(Ledger.monthTotals(txns, "2026-11").spent, all.sumOf { it.spent })
        assertEquals(12_345L + 55 + 700_000 + 1_000 + 4_000, all.sumOf { it.spent })
    }

    @Test
    fun percentIsAWholeNumber_roundedHalfUp_andSafeWithNoTotal() {
        assertEquals(50, TopCategories.percent(1, 2))
        assertEquals(33, TopCategories.percent(1, 3))
        assertEquals(67, TopCategories.percent(2, 3))
        assertEquals(100, TopCategories.percent(5, 5))
        assertEquals(0, TopCategories.percent(0, 5))
        assertEquals(0, TopCategories.percent(5, 0))
    }

    @Test
    fun theSpokenDescriptionWritesTheAmountAndShareOutInWords() {
        val txns = listOf(txn("a", 123_450, "food"), txn("b", 123_450, "bills"))
        val total = 246_900L
        assertEquals(
            "Bills & Utilities, ₱1,234.50, 50 percent of this month's spending",
            TopCategories.describe(top(txns).first(), total),
        )
    }
}
