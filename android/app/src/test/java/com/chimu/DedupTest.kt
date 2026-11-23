package com.chimu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN.md -> Auto-logging -> Dedup: the same ref no, or the same amount and wallet within 10 minutes. */
class DedupTest {
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L

    private fun row(
        ref: String? = null,
        amount: Long = 50_000,
        wallet: String = "g",
        to: String? = null,
        type: TxnType = TxnType.EXPENSE,
        at: Long? = t0,
        raw: String? = null,
        deleted: Boolean = false,
        status: String = "inbox",
        source: String = "notification",
    ) = Txn(
        id = "row", ownerId = "me", type = type, walletId = wallet, toWalletId = to, amount = amount, date = "2026-11-23",
        category = null, merchant = null, note = null, isBusiness = false, source = source, status = status, deleted = deleted,
        refNo = ref, rawText = raw, capturedAt = at,
    )

    private fun candidate(
        ref: String? = null,
        amount: Long = 50_000,
        wallet: String = "g",
        to: String? = null,
        type: TxnType = TxnType.EXPENSE,
        at: Long = t0,
        text: String = "message",
    ) = Dedup.Candidate(ref, type, wallet, to, amount, at, text)

    @Test
    fun theSameReferenceNumberIsTheSamePayment_noMatterHowLongAgo() {
        val old = row(ref = "1234567890123", at = t0 - 30 * 24 * 60 * minute)
        assertTrue(Dedup.isDuplicate(candidate(ref = "1234567890123", amount = 99), listOf(old)))
    }

    @Test
    fun theSameReferenceMatchesAConfirmedRowThatNeverCameFromTheInbox() {
        val synced = row(ref = "ABC12345", at = null, status = "confirmed", source = "receipt")
        assertTrue(Dedup.isDuplicate(candidate(ref = "ABC12345"), listOf(synced)))
    }

    @Test
    fun referenceNumbersAreComparedNormalized() {
        assertTrue(Dedup.isDuplicate(candidate(ref = "AB12CD34"), listOf(row(ref = "ab-12 cd34"))))
    }

    @Test
    fun twoPaymentsOfTheSameAmountWithDifferentReferences_areTwoPayments() {
        val first = row(ref = "1111111111111")
        assertFalse(Dedup.isDuplicate(candidate(ref = "2222222222222"), listOf(first)))
    }

    @Test
    fun sameAmountAndWalletWithin10Minutes_isTheSamePayment() {
        assertTrue(Dedup.isDuplicate(candidate(at = t0 + 4 * minute), listOf(row())))
        assertTrue("an SMS can arrive before the notification", Dedup.isDuplicate(candidate(at = t0 - 9 * minute), listOf(row())))
        assertTrue("exactly 10 minutes still counts", Dedup.isDuplicate(candidate(at = t0 + 10 * minute), listOf(row())))
    }

    @Test
    fun aReferenceOnOneSideOnly_fallsBackToTheTimeRule() {
        // The notification had no reference, the SMS for the same payment does.
        assertTrue(Dedup.isDuplicate(candidate(ref = "1234567890123", at = t0 + minute), listOf(row(ref = null))))
        assertTrue(Dedup.isDuplicate(candidate(ref = null, at = t0 + minute), listOf(row(ref = "1234567890123"))))
    }

    @Test
    fun morethan10MinutesApart_isANewPayment() {
        assertFalse(Dedup.isDuplicate(candidate(at = t0 + 10 * minute + 1), listOf(row())))
        assertFalse(Dedup.isDuplicate(candidate(at = t0 - 11 * minute), listOf(row())))
    }

    @Test
    fun aDifferentAmountWalletOrTypeIsADifferentPayment() {
        assertFalse(Dedup.isDuplicate(candidate(amount = 50_001), listOf(row())))
        assertFalse(Dedup.isDuplicate(candidate(wallet = "m"), listOf(row())))
        assertFalse("money in is not the same as money out", Dedup.isDuplicate(candidate(type = TxnType.INCOME), listOf(row())))
    }

    @Test
    fun aTransferSeenFromBothSidesIsOneTransfer_butToAnotherWalletIsNot() {
        val out = row(type = TxnType.TRANSFER, wallet = "g", to = "m")
        assertTrue(Dedup.isDuplicate(candidate(type = TxnType.TRANSFER, wallet = "g", to = "m", at = t0 + minute), listOf(out)))
        assertFalse(Dedup.isDuplicate(candidate(type = TxnType.TRANSFER, wallet = "g", to = "b", at = t0 + minute), listOf(out)))
    }

    @Test
    fun deletedRowsAndRowsWithoutACaptureTimeAreNotMatchedByTime() {
        assertFalse(Dedup.isDuplicate(candidate(), listOf(row(deleted = true))))
        assertFalse("a hand-entered expense of the same amount is not the same payment", Dedup.isDuplicate(candidate(), listOf(row(at = null, status = "confirmed", source = "manual"))))
        assertFalse(Dedup.isDuplicate(candidate(ref = "1234567890123"), listOf(row(ref = "1234567890123", deleted = true))))
    }

    @Test
    fun anUnreadMessageSeenTwice_isOneItem() {
        val unread = row(amount = 0, raw = "weird text PHP 5")
        assertTrue(Dedup.isDuplicate(candidate(amount = 0, text = "weird text PHP 5", at = t0 + minute), listOf(unread)))
        assertFalse(Dedup.isDuplicate(candidate(amount = 0, text = "another text", at = t0 + minute), listOf(unread)))
        assertFalse("a read item with the same text is not an unread duplicate", Dedup.isDuplicate(candidate(amount = 0, text = "x"), listOf(row(raw = "x"))))
    }

    @Test
    fun nothingExisting_isNotADuplicate() {
        assertFalse(Dedup.isDuplicate(candidate(ref = "1234567890123"), emptyList()))
    }
}
