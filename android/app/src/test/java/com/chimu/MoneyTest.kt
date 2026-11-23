package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    // --- parse ---------------------------------------------------------------

    @Test
    fun parse_groupedAmountWithCentavos() {
        assertEquals(123450L, Money.parse("1,234.50"))
    }

    @Test
    fun parse_wholePesosAndShortFractions() {
        assertEquals(123400L, Money.parse("1234"))
        assertEquals(123450L, Money.parse("1234.5"))
        assertEquals(5L, Money.parse("0.05"))
        assertEquals(50L, Money.parse(".5"))
        assertEquals(100L, Money.parse("1."))
        assertEquals(0L, Money.parse("0"))
    }

    @Test
    fun parse_ignoresSurroundingSpacesAndPesoSign() {
        assertEquals(123450L, Money.parse("  ₱1,234.50  "))
        assertEquals(500L, Money.parse("₱ 5"))
        assertEquals(250000L, Money.parse("PHP 2,500"))
        assertEquals(250000L, Money.parse("php2,500.00"))
    }

    @Test
    fun parse_largeGroupedAmount() {
        assertEquals(123456789000L, Money.parse("1,234,567,890"))
    }

    @Test
    fun parse_rejectsBadInput() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("."))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("12abc"))
        assertNull(Money.parse("1.234"))        // more than two decimals
        assertNull(Money.parse("1.2.3"))        // two dots
        assertNull(Money.parse("12,34"))        // misplaced comma
        assertNull(Money.parse("1,2345"))
        assertNull(Money.parse(",123"))
        assertNull(Money.parse("1,234,"))
        assertNull(Money.parse("1.5,0"))        // comma in the fraction
        assertNull(Money.parse("1e5"))
        assertNull(Money.parse("٣"))            // non-ASCII digit
    }

    @Test
    fun parse_rejectsAmountsTooLargeToStore() {
        assertNull(Money.parse("1000000000000"))   // 13 whole digits
        assertEquals(99999999999999L, Money.parse("999,999,999,999.99"))
    }

    @Test
    fun parse_negativeOnlyWhenAllowed() {
        assertNull(Money.parse("-5"))
        assertNull(Money.parse("-5", allowNegative = false))
        assertEquals(-500L, Money.parse("-5", allowNegative = true))
        assertEquals(-123450L, Money.parse("-₱1,234.50", allowNegative = true))
        assertNull(Money.parse("-", allowNegative = true))
        assertNull(Money.parse("--5", allowNegative = true))
    }

    // --- format --------------------------------------------------------------

    @Test
    fun format_addsPesoSignGroupingAndTwoDecimals() {
        assertEquals("₱1,234.50", Money.format(123450L))
        assertEquals("₱0.00", Money.format(0L))
        assertEquals("₱0.05", Money.format(5L))
        assertEquals("₱1.00", Money.format(100L))
        assertEquals("₱999.99", Money.format(99999L))
        assertEquals("₱1,000.00", Money.format(100000L))
        assertEquals("₱12,345,678.90", Money.format(1234567890L))
    }

    @Test
    fun format_negative() {
        assertEquals("-₱1,234.50", Money.format(-123450L))
        assertEquals("-₱0.05", Money.format(-5L))
    }

    @Test
    fun formatSigned_signCarriesTheDirection() {
        assertEquals("-₱1,234.50", Money.formatSigned(TxnType.EXPENSE, 123450L))
        assertEquals("+₱1,234.50", Money.formatSigned(TxnType.INCOME, 123450L))
        assertEquals("₱1,234.50", Money.formatSigned(TxnType.TRANSFER, 123450L))
    }

    @Test
    fun plain_hasNoSymbolOrGrouping() {
        assertEquals("1234.50", Money.plain(123450L))
        assertEquals("0.05", Money.plain(5L))
        assertEquals("0.00", Money.plain(0L))
        assertEquals("-12.30", Money.plain(-1230L))
    }

    // --- round trips ---------------------------------------------------------

    @Test
    fun roundTrip_formatThenParse() {
        for (c in listOf(0L, 1L, 99L, 100L, 123450L, 100000L, 99999999999999L)) {
            assertEquals(c, Money.parse(Money.format(c)))
            assertEquals(c, Money.parse(Money.plain(c)))
        }
        assertEquals(-123450L, Money.parse(Money.format(-123450L), allowNegative = true))
        assertEquals(-123450L, Money.parse(Money.plain(-123450L), allowNegative = true))
    }
}
