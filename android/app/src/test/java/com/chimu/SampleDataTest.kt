package com.chimu

import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The temporary test data: it must be well formed, repeatable, and recognisable so it can be removed. */
class SampleDataTest {
    private val today = LocalDate.of(2026, 11, 26)
    private val owner = "user-me"
    private val rows = SampleData.generate(owner, today)

    @Test
    fun every_id_is_a_unique_well_formed_uuid_with_the_sample_prefix() {
        val ids = rows.wallets.map { it.id } + rows.txns.map { it.id } + rows.budgets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id, SampleData.isSample(id))
            assertEquals(id, UUID.fromString(id).toString()) // throws if malformed
        }
    }

    @Test
    fun a_real_id_is_never_taken_for_a_sample() {
        assertFalse(SampleData.isSample(UUID.randomUUID().toString()))
    }

    @Test
    fun the_same_user_and_day_give_the_same_data() {
        val again = SampleData.generate(owner, today)
        assertEquals(rows.wallets, again.wallets)
        assertEquals(rows.txns, again.txns)
        assertEquals(rows.budgets, again.budgets)
    }

    @Test
    fun two_users_on_one_phone_never_share_an_id() {
        val other = SampleData.generate("user-ana", today)
        val mine = (rows.wallets.map { it.id } + rows.txns.map { it.id } + rows.budgets.map { it.id }).toSet()
        val theirs = (other.wallets.map { it.id } + other.txns.map { it.id } + other.budgets.map { it.id }).toSet()
        assertTrue(mine.intersect(theirs).isEmpty())
        assertTrue(other.txns.all { it.ownerId == "user-ana" })
    }

    @Test
    fun every_row_belongs_to_the_user() {
        assertTrue(rows.wallets.all { it.ownerId == owner })
        assertTrue(rows.txns.all { it.ownerId == owner })
        assertTrue(rows.budgets.all { it.ownerId == owner })
    }

    @Test
    fun transactions_only_use_wallets_that_exist() {
        val walletIds = rows.wallets.map { it.id }.toSet()
        for (t in rows.txns) {
            assertTrue(t.walletId in walletIds)
            if (t.type == TxnType.TRANSFER) {
                assertTrue(t.toWalletId in walletIds)
                assertNotEquals(t.walletId, t.toWalletId)
            } else {
                assertEquals(null, t.toWalletId)
            }
        }
    }

    @Test
    fun amounts_are_positive_centavos() {
        assertTrue(rows.txns.all { it.amount > 0 })
        assertTrue(rows.budgets.all { it.monthlyLimit > 0 })
    }

    @Test
    fun dates_run_from_about_six_months_ago_up_to_today_and_never_into_the_future() {
        val dates = rows.txns.map { LocalDate.parse(it.date) }
        assertTrue(dates.all { !it.isAfter(today) })
        assertTrue(dates.all { !it.isBefore(today.minusDays(180)) })
        assertTrue(Ledger.months(rows.txns).size >= 6)
    }

    @Test
    fun categories_match_the_transaction_type() {
        val expense = Categories.expense.map { it.slug }.toSet()
        val income = Categories.income.map { it.slug }.toSet()
        for (t in rows.txns) {
            when (t.type) {
                TxnType.EXPENSE -> assertTrue(t.category, t.category in expense)
                TxnType.INCOME -> assertTrue(t.category, t.category in income)
                TxnType.TRANSFER -> assertEquals(null, t.category)
            }
        }
        assertTrue(rows.budgets.all { it.category in expense })
    }

    @Test
    fun there_is_something_of_every_kind_to_look_at() {
        assertTrue(rows.txns.count { it.type == TxnType.EXPENSE } > 100)
        assertTrue(rows.txns.count { it.type == TxnType.INCOME } >= 10)
        assertTrue(rows.txns.count { it.type == TxnType.TRANSFER } >= 10)
        assertTrue(rows.txns.any { it.isBusiness })
        assertTrue(rows.wallets.any { it.openingBalance < 0 }) // a card that already carries a balance
        assertTrue(rows.wallets.map { it.type }.toSet().size >= 4)
    }

    @Test
    fun the_filter_menus_have_several_choices() {
        assertTrue(rows.txns.mapNotNull { it.category }.toSet().size >= 8)
        assertTrue(Ledger.months(rows.txns).size >= 6)
    }

    @Test
    fun a_receipts_vat_and_vatable_sales_add_up_to_its_total() {
        val withVat = rows.txns.filter { it.vat != null }
        assertTrue(withVat.isNotEmpty())
        for (t in withVat) assertEquals(t.amount, t.vat!! + t.vatableSales!!)
    }
}
