package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules around auto-logging: text cleaning, trusted apps and senders, wallet mapping, Inbox wording. */
class AutoLogTest {
    // ---- message text ----

    @Test
    fun cleanText_collapsesWhitespaceAndDropsInvisibleCharacters() {
        assertEquals("You have paid PHP 10.00 to Store.", MessageText.clean("  You have paid\nPHP\t10.00​ to   Store.  "))
        assertEquals("a b", MessageText.clean("a\u0000b"))
        assertEquals("a b", MessageText.clean("a‮b")) // right-to-left override
        assertEquals("", MessageText.clean(null))
        assertEquals("", MessageText.clean("  \n "))
    }

    @Test
    fun cleanText_isBounded() {
        val long = "x".repeat(50_000)
        assertEquals(MessageText.MAX_CHARS, MessageText.clean(long).length)
        assertTrue(MessageText.clean("ab ".repeat(5_000)).length <= MessageText.MAX_CHARS)
    }

    @Test
    fun joinedNotification_prefersTheLongTextAndDoesNotRepeatTheTitle() {
        assertEquals("GCash You have paid PHP 1.00 to A.", MessageText.join("GCash", "You have paid PHP 1.00 to A.", null))
        assertEquals("Payment sent You have sent PHP 1.00 to A. Ref No. 123456", MessageText.join("Payment sent", "You have sent PHP 1.00", "You have sent PHP 1.00 to A. Ref No. 123456"))
        assertEquals("You have paid PHP 1.00 to A.", MessageText.join("You have paid", "x", "You have paid PHP 1.00 to A."))
        assertEquals("body", MessageText.join(null, "body", null))
        assertEquals("title", MessageText.join("title", null, null))
        assertEquals("", MessageText.join(null, null, null))
    }

    // ---- SMS senders ----

    @Test
    fun senderIds_areValidated() {
        assertEquals("BPI", SenderIds.normalize("  BPI "))
        assertEquals("+639171234567", SenderIds.normalize("+639171234567"))
        assertEquals("Bank-X.1", SenderIds.normalize("Bank-X.1"))
        assertNull(SenderIds.normalize("B"))
        assertNull(SenderIds.normalize(""))
        assertNull(SenderIds.normalize("has space"))
        assertNull(SenderIds.normalize("line\nbreak"))
        assertNull(SenderIds.normalize("X".repeat(17)))
        assertNull(SenderIds.normalize(null))
    }

    @Test
    fun senderMatching_isExactAndIgnoresCase() {
        val allowed = listOf("BPI", "GCash")
        assertTrue(SenderIds.matches(allowed, "bpi"))
        assertTrue(SenderIds.matches(allowed, " GCASH "))
        assertFalse("a prefix is not the sender", SenderIds.matches(allowed, "BPIX"))
        assertFalse(SenderIds.matches(allowed, "AD-BPI"))
        assertFalse(SenderIds.matches(emptyList(), "BPI"))
        assertFalse(SenderIds.matches(allowed, null))
        assertFalse(SenderIds.matches(allowed, ""))
    }

    @Test
    fun addingASender_refusesDuplicatesBadInputAndAFullList() {
        assertEquals(listOf("BPI"), SenderIds.add(emptyList(), " BPI "))
        assertNull(SenderIds.add(listOf("BPI"), "bpi"))
        assertNull(SenderIds.add(emptyList(), "no good"))
        val full = (1..SenderIds.MAX_SENDERS).map { "SENDER$it" }
        assertNull(SenderIds.add(full, "ONEMORE"))
    }

    // ---- apps seen posting notifications ----

    @Test
    fun seenApps_recordNewPackagesOnly() {
        val one = SeenApps.record(emptySet(), "com.globe.gcash.android")
        assertEquals(setOf("com.globe.gcash.android"), one)
        assertNull("already known: nothing to write", SeenApps.record(one!!, "com.globe.gcash.android"))
        assertNull(SeenApps.record(emptySet(), "not a package!"))
        assertNull(SeenApps.record(emptySet(), null))
        assertNull(SeenApps.record(emptySet(), ""))
    }

    @Test
    fun seenApps_areBounded() {
        val full = (1..SeenApps.MAX_APPS).map { "com.app$it" }.toSet()
        assertNull(SeenApps.record(full, "com.one.more"))
    }

    // ---- SMS parts ----

    @Test
    fun aLongSms_isJoinedPerSenderInOrder() {
        val parts = listOf(
            SmsPart("BPI", "You have paid PHP 5.00 ", 1_000L),
            SmsPart("MOM", "hi", 1_100L),
            SmsPart("BPI", "to Store.", 1_050L),
            SmsPart(null, "no sender", 1_200L),
        )
        val joined = SmsJoin.join(parts)
        assertEquals(listOf("BPI", "MOM"), joined.map { it.origin })
        assertEquals("You have paid PHP 5.00 to Store.", joined[0].text)
        assertEquals(1_000L, joined[0].receivedAt)
        assertTrue(joined.all { it.channel == Channel.SMS })
    }

    // ---- sources and wallets ----

    @Test
    fun knownApps_pickTheirWording_everythingElseIsCard() {
        assertEquals(SourceFormat.GCASH, KnownApps.formatForPackage("com.globe.gcash.android"))
        assertEquals(SourceFormat.MAYA, KnownApps.formatForPackage("com.paymaya"))
        assertEquals(SourceFormat.CARD, KnownApps.formatForPackage("com.some.bank"))
        assertEquals(SourceFormat.GCASH, KnownApps.formatForSender("GCash"))
        assertEquals(SourceFormat.MAYA, KnownApps.formatForSender("PayMaya"))
        assertEquals(SourceFormat.CARD, KnownApps.formatForSender("BPI"))
    }

    @Test
    fun theSourceWalletIsTheUsersOwnOfThatType() {
        fun w(id: String, name: String, type: WalletType, deleted: Boolean = false) = Wallet(id, "me", name, type, 0, deleted = deleted)
        val wallets = listOf(w("c", "Cash", WalletType.CASH), w("g1", "My other GCash", WalletType.GCASH), w("g2", "GCash", WalletType.GCASH))
        assertEquals("the one named like the default wins", "g2", Wallets.forFormat(SourceFormat.GCASH, wallets)!!.id)
        assertEquals("g1", Wallets.forFormat(SourceFormat.GCASH, wallets.filter { it.id != "g2" })!!.id)
        assertNull("none of that type: the caller creates one", Wallets.forFormat(SourceFormat.MAYA, wallets))
        assertNull("an archived wallet is not used", Wallets.forFormat(SourceFormat.GCASH, listOf(w("g", "GCash", WalletType.GCASH, deleted = true))))
        assertEquals(WalletType.CARD, SourceFormat.CARD.walletType)
        assertEquals("Card", SourceFormat.CARD.walletName)
    }

    @Test
    fun aMessagesOwnTimeIsUsedOnlyWhenPlausible() {
        val now = 1_800_000_000_000L
        assertEquals(now - 5_000, Times.sane(now - 5_000, now))
        assertEquals(now, Times.sane(0, now))
        assertEquals(now, Times.sane(-1, now))
        assertEquals(now, Times.sane(now + 3 * 24 * 60 * 60 * 1000L, now))
    }

    // ---- Inbox wording and rules ----

    private fun item(amount: Long = 12_345, type: TxnType = TxnType.EXPENSE, merchant: String? = "Jollibee", category: String? = null, to: String? = null) = Txn(
        id = "i1", ownerId = "me", type = type, walletId = "w1", toWalletId = to, amount = amount, date = "2026-11-23",
        category = category, merchant = merchant, note = null, isBusiness = false, source = "notification", status = "inbox",
    )

    @Test
    fun oneTapConfirm_needsAnAmountAndValidWallets() {
        assertTrue(InboxRules.canConfirm(item()))
        assertFalse("unread", InboxRules.canConfirm(item(amount = 0)))
        assertFalse("transfer without a destination", InboxRules.canConfirm(item(type = TxnType.TRANSFER)))
        assertFalse("transfer to itself", InboxRules.canConfirm(item(type = TxnType.TRANSFER, to = "w1")))
        assertTrue(InboxRules.canConfirm(item(type = TxnType.TRANSFER, to = "w2")))
    }

    @Test
    fun confirmingFillsInOtherAsTheCategory_butNeverOverwritesOne() {
        assertEquals("other", InboxRules.forConfirm(item()).category)
        assertEquals("food", InboxRules.forConfirm(item(category = "food")).category)
        assertNull("transfers have no category", InboxRules.forConfirm(item(type = TxnType.TRANSFER, to = "w2")).category)
    }

    @Test
    fun inboxRowsReadInWords() {
        assertEquals("Jollibee", InboxRules.title(item()))
        assertEquals("Payment", InboxRules.title(item(merchant = null)))
        assertEquals("Money received", InboxRules.title(item(merchant = " ", type = TxnType.INCOME)))
        assertEquals("Payment message", InboxRules.title(item(amount = 0)))
        assertEquals("Transfer", InboxRules.title(item(type = TxnType.TRANSFER, to = "w2")))
        assertEquals("-₱123.45", InboxRules.amountText(item()))
        assertEquals("+₱123.45", InboxRules.amountText(item(type = TxnType.INCOME)))
        assertEquals("Not read", InboxRules.amountText(item(amount = 0)))
        assertEquals("From a notification", InboxRules.sourceLabel(item()))
        assertEquals("From an SMS", InboxRules.sourceLabel(item().copy(source = "sms")))
        assertTrue(InboxRules.note(item(amount = 0)).contains("fill it in"))
        assertEquals("Check it, then confirm.", InboxRules.note(item()))
        assertEquals("Nothing to check", InboxRules.badge(0))
        assertEquals("1 payment to check", InboxRules.badge(1))
        assertEquals("7 payments to check", InboxRules.badge(7))
    }

    @Test
    fun theWordsNeverClaimATaxEffect() {
        val all = listOf(InboxRules.note(item()), InboxRules.note(item(amount = 0)), InboxRules.badge(2), InboxRules.title(item(merchant = null)))
        assertTrue(all.none { it.contains("deductible", ignoreCase = true) })
    }

    @Test
    fun anInboxItemIsRecognisedByItsStatusOnly() {
        assertTrue(InboxRules.isInbox(item()))
        assertFalse(InboxRules.isInbox(item().copy(status = "confirmed")))
        assertFalse(InboxRules.isInbox(item().copy(status = "pending")))
    }

    // ---- the Inbox SQL the database uses ----

    @Test
    fun inboxSql_keepsInboxOutOfLiveListsAndOnlyDismissesInboxRows() {
        assertTrue(InboxSql.LIVE_WHERE.contains("status <> 'inbox'"))
        assertTrue(InboxSql.INBOX_WHERE.contains("status = 'inbox'"))
        assertTrue("dismiss can never delete a confirmed row", InboxSql.DISMISS_WHERE.contains("status = 'inbox'"))
        assertEquals("owner_id = ? AND deleted = 0 AND (captured_at >= ?)", InboxSql.dedupWhere(false))
        assertEquals("owner_id = ? AND deleted = 0 AND (captured_at >= ? OR ref_no = ?)", InboxSql.dedupWhere(true))
    }
}
