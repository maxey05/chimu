package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ############################################################################################
 * ## EVERY MESSAGE IN THIS FILE IS SYNTHETIC.                                               ##
 * ## They are modelled on the publicly known styles of GCash, Maya and bank card alerts,    ##
 * ## with names, numbers and reference numbers invented. None was captured from a real      ##
 * ## phone, so they prove the templates do what they were written to do, NOT that the real  ##
 * ## apps word their messages this way. REPLACE OR ADD REAL CAPTURES (redacted) on the      ##
 * ## owner's phone and re-run: see rfcs/RFC-005-auto-logging.md -> Manual steps.            ##
 * ############################################################################################
 */
class ParserTest {
    private fun parse(format: SourceFormat, text: String) = Parser.parse(format, MessageText.clean(text))

    private fun assertPayment(p: ParsedPayment?, direction: Direction, amount: Long, name: String?, ref: String?) {
        assertNotNull("expected the message to be understood", p)
        assertEquals(direction, p!!.direction)
        assertEquals(amount, p.amount)
        assertEquals(name, p.counterparty)
        assertEquals(ref, p.refNo)
    }

    // ---- GCash (synthetic) ----

    @Test
    fun gcash_sentToAPerson() {
        val p = parse(
            SourceFormat.GCASH,
            "You have sent PHP 500.00 of GCash to JU** DE** CR** 0917 123 4567. Your new balance is PHP 1,234.56. Ref No. 1234 567 890123.",
        )
        assertPayment(p, Direction.EXPENSE, 50_000L, "JU** DE** CR** 0917 123 4567", "1234567890123")
    }

    @Test
    fun gcash_paidAMerchant() {
        val p = parse(SourceFormat.GCASH, "You have paid PHP 250.00 to SM Supermarket. Ref No. 9876543210123. Your new balance is PHP 750.00.")
        assertPayment(p, Direction.EXPENSE, 25_000L, "SM Supermarket", "9876543210123")
    }

    @Test
    fun gcash_billPaymentProcessed() {
        val p = parse(SourceFormat.GCASH, "Your payment of PHP 1,500.00 to MERALCO has been successfully processed. Ref No. 5551234567890.")
        assertPayment(p, Direction.EXPENSE, 150_000L, "MERALCO", "5551234567890")
    }

    @Test
    fun gcash_received() {
        val p = parse(
            SourceFormat.GCASH,
            "You have received PHP 1,000.50 of GCash from JU** DE** 0917 123 4567. Your new balance is PHP 2,000.00. Ref. No. 7778889990001",
        )
        assertPayment(p, Direction.INCOME, 100_050L, "JU** DE** 0917 123 4567", "7778889990001")
    }

    @Test
    fun gcash_cashIn() {
        val p = parse(SourceFormat.GCASH, "You have successfully cash-in PHP 2,000.00 via BPI. Ref No. 3334445556667.")
        assertPayment(p, Direction.INCOME, 200_000L, "BPI", "3334445556667")
    }

    @Test
    fun gcash_receivedViaCashInWithoutASource_getsAGenericName() {
        val p = parse(SourceFormat.GCASH, "You have received PHP 700.00 via Cash In. Ref No. 1112223334445.")
        assertPayment(p, Direction.INCOME, 70_000L, "Cash in", "1112223334445")
    }

    @Test
    fun gcash_titleAndBodyOfANotification_readAsOne() {
        val joined = MessageText.join("GCash", "You have paid PHP 99.50 to Jollibee. Ref No. 2223334445556.", null)
        assertPayment(Parser.parse(SourceFormat.GCASH, joined), Direction.EXPENSE, 9_950L, "Jollibee", "2223334445556")
    }

    // ---- Maya (synthetic) ----

    @Test
    fun maya_sent() {
        val p = parse(SourceFormat.MAYA, "You sent PHP 300.00 to 0917 123 4567. Reference ID: 8F3K2L9Q1.")
        assertPayment(p, Direction.EXPENSE, 30_000L, "0917 123 4567", "8F3K2L9Q1")
    }

    @Test
    fun maya_paid() {
        val p = parse(SourceFormat.MAYA, "You paid PHP 125.75 to Mercury Drug. Ref: AB12CD34EF")
        assertPayment(p, Direction.EXPENSE, 12_575L, "Mercury Drug", "AB12CD34EF")
    }

    @Test
    fun maya_received() {
        val p = parse(SourceFormat.MAYA, "You received PHP 1,250.00 from JU** DE**. Reference No. 4445556667778")
        assertPayment(p, Direction.INCOME, 125_000L, "JU** DE**", "4445556667778")
    }

    @Test
    fun maya_cashInAddedToWallet() {
        val p = parse(SourceFormat.MAYA, "Cash in successful. PHP 500.00 has been added to your Maya wallet. Reference ID: ZX90CV12BN")
        assertPayment(p, Direction.INCOME, 50_000L, "Cash in", "ZX90CV12BN")
    }

    // ---- card SMS (synthetic) ----

    @Test
    fun card_purchaseAlert() {
        val p = parse(
            SourceFormat.CARD,
            "BANKX: Your credit card ending 1234 was used for a purchase of PHP 1,234.56 at SHOPEE on 01/15/24 10:30 AM. Ref No. ABC12345",
        )
        assertPayment(p, Direction.EXPENSE, 123_456L, "SHOPEE", "ABC12345")
    }

    @Test
    fun card_chargedAlert() {
        val p = parse(SourceFormat.CARD, "BANKX Alert: Your card ending 5678 was charged PHP 750.00 at LAZADA PH. Not you? Call us.")
        assertPayment(p, Direction.EXPENSE, 75_000L, "LAZADA PH", null)
    }

    @Test
    fun card_spent() {
        val p = parse(SourceFormat.CARD, "You spent P300 at JOLLIBEE QC using your debit card.")
        assertPayment(p, Direction.EXPENSE, 30_000L, "JOLLIBEE QC", null)
    }

    @Test
    fun card_refundIsIncome() {
        val p = parse(SourceFormat.CARD, "A refund of PHP 100.00 from SHOPEE was posted to your card. Ref No. 99887766")
        assertPayment(p, Direction.INCOME, 10_000L, "SHOPEE", "99887766")
    }

    // ---- amounts ----

    @Test
    fun amountsInEveryWrittenForm() {
        fun cents(amount: String) = parse(SourceFormat.GCASH, "You have paid $amount to Store.")?.amount
        assertEquals(12_345_600L, cents("PHP 123,456.00"))
        assertEquals(12_345L, cents("PHP123.45"))
        assertEquals(12_350L, cents("₱123.5"))
        assertEquals(50_000L, cents("P500"))
        assertEquals(50_000L, cents("Php 500"))
        assertEquals(1L, cents("php 0.01"))
        assertEquals(100_000_000L, cents("₱1,000,000"))
    }

    @Test
    fun brokenOrAbsurdAmountsAreNull() {
        fun cents(amount: String) = parse(SourceFormat.GCASH, "You have paid $amount to Store.")
        assertNull("misplaced comma", cents("PHP 12,34"))
        assertNull("three decimals", cents("PHP 1,234.567"))
        assertNull("zero", cents("PHP 0.00"))
        assertNull("13 whole digits", cents("PHP 9,999,999,999,999.00"))
        assertNull("no figure", cents("PHP"))
        assertNull("a word is not an amount", cents("Pesos"))
    }

    @Test
    fun theBalanceIsNeverTakenForThePayment() {
        val p = parse(SourceFormat.GCASH, "Your new balance is PHP 9,999.00. You have sent PHP 10.00 to Ana. Ref No. 1010101010101.")
        assertEquals(1_000L, p!!.amount)
    }

    // ---- when unsure, null ----

    @Test
    fun unsure_isNull() {
        // No template: a promo, a code, a statement.
        assertNull(parse(SourceFormat.GCASH, "Win up to PHP 10,000 in our big promo! Tap to join."))
        assertNull(parse(SourceFormat.GCASH, "Your GCash OTP is 123456. Never share it."))
        assertNull(parse(SourceFormat.CARD, "Your statement balance is PHP 5,432.10 due on 02/01."))
        assertNull(parse(SourceFormat.GCASH, ""))
        // Two different payments in one message.
        assertNull(parse(SourceFormat.GCASH, "You have sent PHP 100.00 to Ana. You have received PHP 200.00 from Ben."))
        // "sent" and "received" for the same amount cannot both be true.
        assertNull(parse(SourceFormat.GCASH, "You have sent PHP 100.00 to Ana. You have received PHP 100.00 from Ana."))
        // Money paid into the user's own card is not a purchase.
        assertNull(parse(SourceFormat.CARD, "Payment of PHP 5,000.00 to your card was received."))
        // A name that never ends within 80 characters is not trusted.
        assertNull(parse(SourceFormat.GCASH, "You have sent PHP 100.00 to " + "X".repeat(120)))
    }

    @Test
    fun theSameMessageTwice_isNotConflicting() {
        // Two templates match (purchase of ... and used for ... purchase of ...), and they agree.
        val p = parse(SourceFormat.CARD, "Card ending 1 was used for a purchase of PHP 10.00 at STORE on 01/01.")
        assertEquals(1_000L, p!!.amount)
    }

    @Test
    fun aFormatOnlyReadsItsOwnWording() {
        // The card wording does not match the GCash templates.
        assertNull(parse(SourceFormat.GCASH, "Your card ending 1234 was charged PHP 750.00 at LAZADA."))
        assertNotNull(parse(SourceFormat.CARD, "Your card ending 1234 was charged PHP 750.00 at LAZADA."))
    }

    @Test
    fun overlongTextIsNotRead() {
        val text = "You have paid PHP 10.00 to Store. " + "x".repeat(Parser.MAX_TEXT)
        assertNull(Parser.parse(SourceFormat.GCASH, text))
        assertFalse(Parser.hasAmount(text))
    }

    @Test
    fun hasAmount_gatesOutMessagesWithoutMoney() {
        assertTrue(Parser.hasAmount("You have paid PHP 10.00 to Store."))
        assertTrue(Parser.hasAmount("Charged P99 at Shop"))
        assertFalse(Parser.hasAmount("Your OTP is 123456"))
        assertFalse(Parser.hasAmount("Hello from GCash"))
        assertFalse(Parser.hasAmount("Order APP100 shipped")) // a P glued to letters is not a peso sign
    }

    // ---- reference numbers ----

    @Test
    fun refNumbers_areNormalized() {
        assertEquals("1234567890123", Ref.find("Ref No. 1234 567 890123."))
        assertEquals("1234567890123", Ref.find("Ref. No: 1234567890123"))
        assertEquals("AB12CD34", Ref.find("Reference ID: ab12-cd34"))
        assertEquals("998877", Ref.find("ref # 998877"))
        assertNull("no digit, not a reference", Ref.find("Ref: Your balance"))
        assertNull("too short", Ref.find("Ref No. 123"))
        assertNull("a refund is not a reference", Ref.find("A refund of PHP 5"))
    }

    @Test
    fun twoDifferentRefNumbers_meanNoRef() {
        assertNull(Ref.find("Ref No. 1111111111 and Ref No. 2222222222"))
        assertEquals("1111111111", Ref.find("Ref No. 1111111111 (Ref No. 1111 111111)"))
    }

    @Test
    fun refNormalize() {
        assertEquals("A1B2C3", Ref.normalize(" a1-b2 c3 "))
        assertNull(Ref.normalize(null))
        assertNull(Ref.normalize("ABCDEFG")) // no digit
        assertNull(Ref.normalize("12345")) // too short
        assertNull(Ref.normalize("1".repeat(33))) // too long
    }

    // ---- transfers: the other side is one of the user's own wallets ----

    private fun wallet(id: String, name: String, type: WalletType, deleted: Boolean = false) =
        Wallet(id, "me", name, type, 0, deleted = deleted)

    private val gcash = wallet("g", "GCash", WalletType.GCASH)
    private val maya = wallet("m", "Maya", WalletType.MAYA)
    private val cash = wallet("c", "Cash", WalletType.CASH)
    private val bpi = wallet("b", "BPI", WalletType.BANK)

    private fun payment(direction: Direction, who: String?) = ParsedPayment(direction, 10_000L, who, null)

    @Test
    fun sendingToYourOwnMaya_isATransferOutOfGcash() {
        val placement = Transfers.place(payment(Direction.EXPENSE, "Maya"), gcash, listOf(gcash, maya, cash))
        assertEquals(Placement(TxnType.TRANSFER, "g", "m"), placement)
    }

    @Test
    fun receivingFromYourOwnGcash_isATransferIntoMaya() {
        val placement = Transfers.place(payment(Direction.INCOME, "GCash Wallet"), maya, listOf(gcash, maya))
        assertEquals(Placement(TxnType.TRANSFER, "g", "m"), placement)
    }

    @Test
    fun aWalletIsMatchedByItsOwnName() {
        val placement = Transfers.place(payment(Direction.INCOME, "Cash in from BPI"), gcash, listOf(gcash, bpi))
        assertEquals(Placement(TxnType.TRANSFER, "b", "g"), placement)
    }

    @Test
    fun aStrangersNameIsNotATransfer() {
        assertEquals(Placement(TxnType.EXPENSE, "g", null), Transfers.place(payment(Direction.EXPENSE, "SM Supermarket"), gcash, listOf(gcash, maya, bpi)))
        assertEquals(Placement(TxnType.INCOME, "g", null), Transfers.place(payment(Direction.INCOME, null), gcash, listOf(gcash, maya)))
    }

    @Test
    fun cashInIsNotAWalletCalledCash() {
        assertEquals(Placement(TxnType.INCOME, "g", null), Transfers.place(payment(Direction.INCOME, "Cash in"), gcash, listOf(gcash, cash)))
    }

    @Test
    fun theSourceWalletItselfIsNeverTheOtherSide() {
        assertEquals(Placement(TxnType.EXPENSE, "g", null), Transfers.place(payment(Direction.EXPENSE, "GCash Store"), gcash, listOf(gcash, cash)))
    }

    @Test
    fun anArchivedWalletIsNotMatched() {
        val oldMaya = wallet("m", "Maya", WalletType.MAYA, deleted = true)
        assertEquals(Placement(TxnType.EXPENSE, "g", null), Transfers.place(payment(Direction.EXPENSE, "Maya"), gcash, listOf(gcash, oldMaya)))
    }

    @Test
    fun twoWalletsThatMatch_meanNotSure() {
        val maya2 = wallet("m2", "Maya Savings", WalletType.MAYA)
        assertEquals(Placement(TxnType.EXPENSE, "g", null), Transfers.place(payment(Direction.EXPENSE, "Maya"), gcash, listOf(gcash, maya, maya2)))
    }

    @Test
    fun aNameWithOnlyPartOfAWalletsNameInsideAWordDoesNotMatch() {
        val dollar = wallet("d", "Dol", WalletType.SAVINGS)
        assertEquals(Placement(TxnType.EXPENSE, "g", null), Transfers.place(payment(Direction.EXPENSE, "Dollarama"), gcash, listOf(gcash, dollar)))
    }
}
