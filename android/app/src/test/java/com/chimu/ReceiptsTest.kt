package com.chimu

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptsTest {
    // ---- ReceiptReader.centavos ----

    @Test
    fun pesosBecomeCentavosExactly() {
        assertEquals(45050L, ReceiptReader.centavos(BigDecimal("450.5")))
        assertEquals(1999L, ReceiptReader.centavos(BigDecimal("19.99")))
        assertEquals(10000L, ReceiptReader.centavos("100"))
        assertEquals(0L, ReceiptReader.centavos(BigDecimal("0")))
    }

    @Test
    fun floatingPointTrapsDoNotLeakIn() {
        // 0.1 + 0.2 style values must still land on whole centavos.
        assertEquals(30L, ReceiptReader.centavos(BigDecimal("0.30000000000000004")))
        assertEquals(1L, ReceiptReader.centavos(BigDecimal("0.005")))
        assertEquals(29L, ReceiptReader.centavos(0.29))
    }

    @Test
    fun badAmountsGiveNull() {
        assertNull(ReceiptReader.centavos(null))
        assertNull(ReceiptReader.centavos(BigDecimal("-5")))
        assertNull(ReceiptReader.centavos("abc"))
        assertNull(ReceiptReader.centavos(true))
        assertNull(ReceiptReader.centavos(BigDecimal("1e15")))
    }

    // ---- ReceiptReader.parse ----

    private fun reply(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf(*pairs)

    @Test
    fun aFullReceiptIsParsed() {
        val r = ReceiptReader.parse(
            reply(
                "type" to "expense", "amount" to BigDecimal("450.50"), "date" to "2026-11-20",
                "merchant" to "  Jollibee ", "category" to "food", "ref_no" to "R-1",
                "seller_tin" to "123-456-789-000", "invoice_no" to "SI-77",
                "vatable_sales" to BigDecimal("402.23"), "vat" to BigDecimal("48.27"), "vat_exempt" to BigDecimal("0"),
            ),
        )
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(45050L, r.amount)
        assertEquals("2026-11-20", r.date)
        assertEquals("Jollibee", r.merchant)
        assertEquals("food", r.category)
        assertEquals("R-1", r.refNo)
        assertEquals(40223L, r.vatableSales)
        assertEquals(4827L, r.vat)
        assertEquals(0L, r.vatExempt)
    }

    @Test
    fun missingFieldsStayNullSoReviewCanHighlightThem() {
        val r = ReceiptReader.parse(reply("type" to "expense", "amount" to null, "date" to null, "merchant" to null))
        assertNull(r.amount)
        assertNull(r.date)
        assertNull(r.merchant)
        assertNull(r.category)
        assertNull(r.vat)
    }

    @Test
    fun aTransferFromTheModelBecomesAnExpense() {
        assertEquals(TxnType.EXPENSE, ReceiptReader.parse(reply("type" to "transfer", "amount" to BigDecimal("5"))).type)
    }

    @Test
    fun incomeKeepsItsOwnCategories() {
        val ok = ReceiptReader.parse(reply("type" to "income", "category" to "salary"))
        assertEquals(TxnType.INCOME, ok.type)
        assertEquals("salary", ok.category)
        // "food" is an expense category, so it is dropped for income.
        assertNull(ReceiptReader.parse(reply("type" to "income", "category" to "food")).category)
    }

    @Test
    fun anUnknownCategoryIsDropped() {
        assertNull(ReceiptReader.parse(reply("type" to "expense", "category" to "yachts")).category)
    }

    @Test
    fun aZeroAmountIsTreatedAsNotRead() {
        assertNull(ReceiptReader.parse(reply("type" to "expense", "amount" to BigDecimal("0"))).amount)
    }

    @Test
    fun impossibleDatesAreDropped() {
        assertNull(ReceiptReader.parse(reply("type" to "expense", "date" to "2026-02-30")).date)
        assertNull(ReceiptReader.parse(reply("type" to "expense", "date" to "11/20/2026")).date)
        assertEquals("2024-02-29", ReceiptReader.parse(reply("type" to "expense", "date" to "2024-02-29")).date)
    }

    @Test
    fun overlongTextIsCut() {
        val r = ReceiptReader.parse(reply("type" to "expense", "merchant" to "x".repeat(500)))
        assertEquals(120, r.merchant!!.length)
    }

    @Test
    fun aReplyWithNoExpectedFieldsIsAnError() {
        var failed = false
        try {
            ReceiptReader.parse(reply("error" to "nope"))
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    // ---- ReceiptMatch ----

    private fun txn(id: String, amount: Long, date: String, type: TxnType = TxnType.EXPENSE, updatedAt: Long = 1, photo: String? = null) =
        Txn(
            id = id, ownerId = "o", type = type, walletId = "w", toWalletId = null, amount = amount, date = date,
            category = null, merchant = null, note = null, isBusiness = false, updatedAt = updatedAt, localPhoto = photo,
        )

    @Test
    fun sameAmountWithinTwoDaysMatches() {
        val list = listOf(txn("a", 10000, "2026-11-18"), txn("b", 10000, "2026-11-23"))
        assertEquals("a", ReceiptMatch.find(list, "scan", 10000, "2026-11-20")!!.id)
    }

    @Test
    fun threeDaysApartDoesNotMatch() {
        assertNull(ReceiptMatch.find(listOf(txn("a", 10000, "2026-11-17")), "scan", 10000, "2026-11-20"))
    }

    @Test
    fun theClosestDayWins() {
        val list = listOf(txn("far", 10000, "2026-11-18"), txn("near", 10000, "2026-11-19"))
        assertEquals("near", ReceiptMatch.find(list, "scan", 10000, "2026-11-20")!!.id)
    }

    @Test
    fun aTieOnDaysGoesToTheMostRecentlyEdited() {
        val list = listOf(txn("old", 10000, "2026-11-19", updatedAt = 5), txn("new", 10000, "2026-11-21", updatedAt = 9))
        assertEquals("new", ReceiptMatch.find(list, "scan", 10000, "2026-11-20")!!.id)
    }

    @Test
    fun differentAmountsNeverMatch() {
        assertNull(ReceiptMatch.find(listOf(txn("a", 10001, "2026-11-20")), "scan", 10000, "2026-11-20"))
    }

    @Test
    fun skipsTheScanItselfDeletedIncomeAndRowsWithPhotos() {
        val list = listOf(
            txn("scan", 10000, "2026-11-20"),
            txn("gone", 10000, "2026-11-20").copy(deleted = true),
            txn("inc", 10000, "2026-11-20", type = TxnType.INCOME),
            txn("has", 10000, "2026-11-20", photo = "x.jpg"),
            txn("has2", 10000, "2026-11-20").copy(receiptPath = "o/has2.jpg"),
        )
        assertNull(ReceiptMatch.find(list, "scan", 10000, "2026-11-20"))
    }

    @Test
    fun unusableInputGivesNull() {
        val list = listOf(txn("a", 10000, "2026-11-20"))
        assertNull(ReceiptMatch.find(list, "scan", 0, "2026-11-20"))
        assertNull(ReceiptMatch.find(list, "scan", 10000, "not a date"))
    }

    // ---- ReceiptImage ----

    @Test
    fun fitKeepsSmallPicturesAndShrinksBigOnes() {
        assertEquals(1000 to 800, ReceiptImage.fit(1000, 800))
        assertEquals(1600 to 1200, ReceiptImage.fit(4000, 3000))
        assertEquals(1200 to 1600, ReceiptImage.fit(3000, 4000))
        assertEquals(1600 to 1, ReceiptImage.fit(100000, 1)) // never reaches zero
    }

    @Test
    fun sampleSizeLeavesAtLeastTheTargetEdge() {
        assertEquals(1, ReceiptImage.sampleSize(1600, 1200))
        assertEquals(2, ReceiptImage.sampleSize(4000, 3000))
        assertEquals(4, ReceiptImage.sampleSize(6400, 4800))
        assertTrue(4000 / ReceiptImage.sampleSize(4000, 3000) >= ReceiptImage.MAX_EDGE)
    }

    @Test
    fun exifOrientationIsMappedToRotations() {
        assertEquals(ReceiptImage.Upright(0, false), ReceiptImage.upright(1))
        assertEquals(ReceiptImage.Upright(90, false), ReceiptImage.upright(6))
        assertEquals(ReceiptImage.Upright(270, false), ReceiptImage.upright(8))
        assertEquals(ReceiptImage.Upright(180, false), ReceiptImage.upright(3))
        assertEquals(ReceiptImage.Upright(0, true), ReceiptImage.upright(2))
        assertEquals(ReceiptImage.Upright(0, false), ReceiptImage.upright(99))
    }

    // ---- ReceiptText ----

    @Test
    fun listWordsFollowTheScansProgress() {
        val scan = Txn(
            id = "s", ownerId = "o", type = TxnType.EXPENSE, walletId = "w", toWalletId = null, amount = 0, date = "2026-11-20",
            category = null, merchant = null, note = null, isBusiness = false, source = "receipt", status = "pending",
        )
        assertEquals("Waiting to be read", ReceiptText.listNote(scan))
        assertEquals("Not read", ReceiptText.listAmount(scan))
        assertEquals("Couldn't be read. Tap to fill it in.", ReceiptText.listNote(scan.copy(extractError = "bad")))
        assertEquals("Read from receipt. Tap to check.", ReceiptText.listNote(scan.copy(status = "extracted", amount = 100)))
        assertNull(ReceiptText.listNote(scan.copy(status = "confirmed")))
        assertNull(ReceiptText.listNote(scan.copy(source = "manual", status = "confirmed")))
        assertFalse(ReceiptText.listAmount(scan.copy(status = "extracted", amount = 100)) == "Not read")
    }
}
