package com.chimu

import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PaymentIntake] end to end against an in-memory phone and a scripted reader. The messages are the
 * SYNTHETIC ones described in ParserTest.kt, not real captures.
 */
class IntakeTest {
    private val me = "me"
    private val manila = ZoneId.of("Asia/Manila")
    private val t0 = 1_800_000_000_000L // 2027-01-15 ... in Manila; the tests below read the date back through the same zone
    private val minute = 60_000L

    /** The phone's database as the intake sees it: the same lookups [Db] does with SQL. */
    private class FakeInbox(var wallets: MutableList<Wallet> = mutableListOf()) : InboxLocal {
        val rows = mutableListOf<Txn>()
        val addedWallets = mutableListOf<Wallet>()

        override fun wallets(owner: String) = wallets.filter { it.ownerId == owner }

        override fun addWallet(wallet: Wallet) {
            wallets += wallet
            addedWallets += wallet
        }

        override fun dedupCandidates(owner: String, refNo: String?, sinceMs: Long) =
            rows.filter { it.ownerId == owner && !it.deleted && ((it.capturedAt ?: Long.MIN_VALUE) >= sinceMs || (refNo != null && it.refNo == refNo)) }

        override fun addInbox(txn: Txn) {
            check(txn.status == "inbox") { "the intake may only add inbox rows" }
            rows += txn
        }
    }

    /** A reader that answers from a script and counts its calls. */
    private class FakeReader(var answer: () -> ExtractedReceipt) : MessageReader {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun read(text: String, source: String): ExtractedReceipt {
            calls += text to source
            return answer()
        }
    }

    private fun reading(amount: Long? = 12_345, type: TxnType = TxnType.EXPENSE, merchant: String? = "Some Shop", ref: String? = "ref-9999-ABC", category: String? = "food") =
        ExtractedReceipt(type, amount, null, merchant, category, ref, null, null, null, null, null)

    private var ids = 0
    private fun intake(local: FakeInbox, reader: MessageReader? = null, online: Boolean = true, now: Long = t0 + 60 * minute) =
        PaymentIntake(local, reader, online = { online }, clock = { now }, zone = manila, newId = { "id-${++ids}" })

    private fun handle(intake: PaymentIntake, message: IncomingMessage, timeoutMs: Long = 1_000) =
        runBlocking { intake.handle(me, message, timeoutMs) }

    private fun gcash(text: String, at: Long = t0) = IncomingMessage(Channel.NOTIFICATION, KnownApps.GCASH_PACKAGE, text, at)

    private val paid = "You have paid PHP 250.00 to SM Supermarket. Ref No. 9876543210123. Your new balance is PHP 750.00."

    // ---- a payment becomes an Inbox item ----

    @Test
    fun aGcashPayment_becomesAnInboxItem_withTheRightFields() {
        val local = FakeInbox()
        val result = handle(intake(local), gcash(paid))

        val added = result as IntakeResult.Added
        assertTrue("there was no GCash wallet, so one was made", added.createdWallet)
        val wallet = local.addedWallets.single()
        assertEquals("GCash", wallet.name)
        assertEquals(WalletType.GCASH, wallet.type)

        val txn = local.rows.single()
        assertEquals("inbox", txn.status)
        assertEquals("notification", txn.source)
        assertEquals(TxnType.EXPENSE, txn.type)
        assertEquals(25_000L, txn.amount)
        assertEquals("SM Supermarket", txn.merchant)
        assertEquals("9876543210123", txn.refNo)
        assertEquals(wallet.id, txn.walletId)
        assertNull(txn.toWalletId)
        assertEquals(me, txn.ownerId)
        assertEquals(t0, txn.capturedAt)
        assertEquals(paid, txn.rawText)
        assertEquals("2027-01-15", txn.date)
        assertFalse(txn.isBusiness)
    }

    @Test
    fun theExistingWalletIsUsed_andNoneIsCreated() {
        val gcash = Wallet("w-g", me, "GCash", WalletType.GCASH, 0)
        val local = FakeInbox(mutableListOf(Wallet("w-c", me, "Cash", WalletType.CASH, 0), gcash))
        val added = handle(intake(local), gcash(paid)) as IntakeResult.Added
        assertFalse(added.createdWallet)
        assertTrue(local.addedWallets.isEmpty())
        assertEquals("w-g", local.rows.single().walletId)
    }

    @Test
    fun anArchivedWalletIsNotUsed() {
        val old = Wallet("w-old", me, "GCash", WalletType.GCASH, 0, deleted = true)
        val local = FakeInbox(mutableListOf(old))
        handle(intake(local), gcash(paid))
        assertEquals(1, local.addedWallets.size)
        assertEquals(local.addedWallets.single().id, local.rows.single().walletId)
    }

    @Test
    fun aCardSmsLandsInTheCardWallet() {
        val local = FakeInbox()
        val sms = IncomingMessage(Channel.SMS, "BANKX", "BANKX: Your credit card ending 1234 was used for a purchase of PHP 1,234.56 at SHOPEE on 01/15/24. Ref No. ABC12345", t0)
        handle(intake(local), sms)
        assertEquals(WalletType.CARD, local.addedWallets.single().type)
        val txn = local.rows.single()
        assertEquals("sms", txn.source)
        assertEquals(123_456L, txn.amount)
        assertEquals("SHOPEE", txn.merchant)
    }

    @Test
    fun mayaGoesToMaya_andReceivingIsIncome() {
        val local = FakeInbox()
        handle(intake(local), IncomingMessage(Channel.NOTIFICATION, KnownApps.MAYA_PACKAGE, "You received PHP 1,250.00 from JU** DE**. Reference No. 4445556667778", t0))
        assertEquals(WalletType.MAYA, local.addedWallets.single().type)
        assertEquals(TxnType.INCOME, local.rows.single().type)
    }

    @Test
    fun aPaymentToYourOwnWallet_becomesATransfer() {
        val local = FakeInbox(mutableListOf(Wallet("w-g", me, "GCash", WalletType.GCASH, 0), Wallet("w-m", me, "Maya", WalletType.MAYA, 0)))
        handle(intake(local), gcash("You have sent PHP 500.00 of GCash to Maya. Ref No. 5551112223334."))
        val txn = local.rows.single()
        assertEquals(TxnType.TRANSFER, txn.type)
        assertEquals("w-g", txn.walletId)
        assertEquals("w-m", txn.toWalletId)
        assertNull("a transfer has no merchant", txn.merchant)
        assertNull(txn.category)
    }

    @Test
    fun theDateIsTheDayItArrivedInManila() {
        val local = FakeInbox()
        // 2027-01-15 17:00 UTC is already 2027-01-16 in Manila.
        val lateEvening = java.time.LocalDateTime.of(2027, 1, 15, 17, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        handle(intake(local, now = lateEvening + minute), gcash(paid, at = lateEvening))
        assertEquals("2027-01-16", local.rows.single().date)
    }

    @Test
    fun anImplausibleTimestampBecomesNow() {
        val local = FakeInbox()
        val now = t0 + 5 * minute
        handle(intake(local, now = now), gcash(paid, at = 0))
        assertEquals(now, local.rows.single().capturedAt)
    }

    // ---- not a payment ----

    @Test
    fun messagesWithoutAnAmountAreIgnored_andNothingIsAsked() {
        val local = FakeInbox()
        val reader = FakeReader { reading() }
        val result = handle(intake(local, reader), gcash("Your GCash OTP is 123456. Never share it."))
        assertEquals(IntakeResult.Ignored, result)
        assertTrue(local.rows.isEmpty())
        assertTrue("an ignored message creates no wallet either", local.addedWallets.isEmpty())
        assertTrue(reader.calls.isEmpty())
    }

    // ---- text-mode fallback ----

    private val unknown = "GCash: PHP 250.00 went out of your wallet to SM Supermarket (ref 9876543210123)."

    @Test
    fun whenTheParserFailsAndThePhoneIsOnline_theReaderFillsTheItem() {
        val local = FakeInbox()
        val reader = FakeReader { reading(amount = 25_000, merchant = "SM Supermarket", ref = "9876 543 210123", category = "groceries") }
        handle(intake(local, reader), gcash(unknown))

        assertEquals("only the one message and the source name were sent", listOf(unknown to "gcash"), reader.calls)
        val txn = local.rows.single()
        assertEquals(25_000L, txn.amount)
        assertEquals("SM Supermarket", txn.merchant)
        assertEquals("groceries", txn.category)
        assertEquals("the reference is normalized like the parser's", "9876543210123", txn.refNo)
        assertEquals("inbox", txn.status)
    }

    @Test
    fun theReaderIsNeverAskedWhenTheParserWorks() {
        val local = FakeInbox()
        val reader = FakeReader { reading() }
        handle(intake(local, reader), gcash(paid))
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun offline_theItemStaysUnreadButListed() {
        val local = FakeInbox()
        val reader = FakeReader { reading() }
        val result = handle(intake(local, reader, online = false), gcash(unknown))
        assertTrue(result is IntakeResult.Added)
        assertTrue(reader.calls.isEmpty())
        val txn = local.rows.single()
        assertEquals(0L, txn.amount)
        assertEquals("inbox", txn.status)
        assertEquals("the user can still read the message", unknown, txn.rawText)
        assertTrue(InboxRules.isUnread(txn))
    }

    @Test
    fun noReaderAtAll_isTheSameAsOffline() {
        val local = FakeInbox()
        handle(intake(local, reader = null), gcash(unknown))
        assertEquals(0L, local.rows.single().amount)
    }

    @Test
    fun aReaderThatFailsLeavesTheItemUnread() {
        for (failure in listOf<Exception>(SyncUnavailable("busy"), SyncRejected(422, "refused"), IllegalStateException("boom"))) {
            val local = FakeInbox()
            handle(intake(local, FakeReader { throw failure }), gcash(unknown))
            assertEquals(failure.toString(), 0L, local.rows.single().amount)
        }
    }

    @Test
    fun aReaderThatFindsNoAmount_leavesTheItemUnread() {
        val local = FakeInbox()
        handle(intake(local, FakeReader { reading(amount = null) }), gcash(unknown))
        assertEquals(0L, local.rows.single().amount)
    }

    @Test
    fun aSlowReaderIsGivenUpOn() {
        val local = FakeInbox()
        val slow = object : MessageReader {
            override suspend fun read(text: String, source: String): ExtractedReceipt {
                delay(5_000)
                return reading()
            }
        }
        handle(intake(local, slow), gcash(unknown), timeoutMs = 50)
        assertEquals(0L, local.rows.single().amount)
    }

    @Test
    fun aReaderThatSaysIncomeMakesTheItemIncome() {
        val local = FakeInbox()
        val income = FakeReader { reading(type = TxnType.INCOME) }
        handle(intake(local, income), gcash(unknown))
        assertEquals(TxnType.INCOME, local.rows.single().type)
    }

    // ---- dedup through the whole path ----

    @Test
    fun theSamePaymentAsNotificationAndSms_isOneItem() {
        val local = FakeInbox()
        val sms = IncomingMessage(Channel.SMS, "GCash", "You have paid PHP 250.00 to SM Supermarket. Ref No. 9876 543 210123.", t0 + 2 * minute)
        val i = intake(local)
        assertTrue(handle(i, gcash(paid)) is IntakeResult.Added)
        assertEquals(IntakeResult.Duplicate, handle(i, sms))
        assertEquals(1, local.rows.size)
    }

    @Test
    fun theSamePaymentWithNoReferenceAtAll_isOneItemWithin10Minutes_butTwoAfter() {
        val local = FakeInbox()
        val i = intake(local)
        val text = "You have paid PHP 20.00 to Jeepney Fare."
        assertTrue(handle(i, gcash(text, t0)) is IntakeResult.Added)
        assertEquals(IntakeResult.Duplicate, handle(i, gcash(text, t0 + 3 * minute)))
        assertTrue(handle(i, gcash(text, t0 + 20 * minute)) is IntakeResult.Added)
        assertEquals(2, local.rows.size)
    }

    @Test
    fun twoPaymentsOfTheSameAmount_withDifferentReferences_areTwoItems() {
        val local = FakeInbox()
        val i = intake(local)
        handle(i, gcash("You have paid PHP 20.00 to Jeepney Fare. Ref No. 1111111111111."))
        val second = handle(i, gcash("You have paid PHP 20.00 to Jeepney Fare. Ref No. 2222222222222.", t0 + minute))
        assertTrue(second is IntakeResult.Added)
        assertEquals(2, local.rows.size)
    }

    @Test
    fun aPaymentAlreadyConfirmedAndSynced_isNotLoggedAgain() {
        val local = FakeInbox()
        local.rows += Txn(
            "old", me, TxnType.EXPENSE, "w", null, 25_000, "2027-01-10", "groceries", "SM", null, false, source = "notification", status = "confirmed",
            refNo = "9876543210123", capturedAt = t0 - 5 * 24 * 60 * minute,
        )
        assertEquals(IntakeResult.Duplicate, handle(intake(local), gcash(paid)))
    }

    @Test
    fun theSameUnreadMessageTwice_isOneItem() {
        val local = FakeInbox()
        val i = intake(local, reader = null)
        handle(i, gcash(unknown))
        assertEquals(IntakeResult.Duplicate, handle(i, gcash(unknown, t0 + minute)))
        assertEquals(1, local.rows.size)
    }

    @Test
    fun anotherUsersRowsAreNeverConsidered() {
        val local = FakeInbox()
        local.rows += Txn("x", "someone-else", TxnType.EXPENSE, "w", null, 25_000, "2027-01-15", null, null, null, false, status = "inbox", refNo = "9876543210123", capturedAt = t0)
        assertTrue(handle(intake(local), gcash(paid)) is IntakeResult.Added)
    }

    // ---- bounds ----

    @Test
    fun theStoredTextIsCleanedAndBounded() {
        val local = FakeInbox()
        val noisy = "You have paid PHP 20.00 to Store.\u0000​\n" + "y".repeat(5_000)
        handle(intake(local, reader = null), gcash(noisy))
        val stored = local.rows.single().rawText!!
        assertTrue(stored.length <= MessageText.MAX_CHARS)
        assertFalse(stored.contains('\u0000'))
        assertFalse(stored.contains('\n'))
    }
}
