package com.chimu

/**
 * Reads the payment messages GCash, Maya and card issuers send (RFC-005). Pure Kotlin: no Android
 * types, so the rules are checked by plain JUnit tests.
 *
 * The parser's one promise is to return null when it is not sure (RULES 18). Every format is a short
 * list of templates. A message is read only when at least one template matches, and every template
 * that matches agrees on the direction and the amount. Anything else (no template, two different
 * amounts, "sent" and "received" in one message, an amount that is not a plain peso figure) is null,
 * and the caller falls back to the `extract` function or leaves the item unread in the Inbox.
 *
 * The wording the templates expect is modelled on the public message styles of each app. It has not
 * been checked against real captures yet (see rfcs/RFC-005-auto-logging.md -> Manual steps).
 */

/** Which wording a message follows. It picks the templates, and the wallet the payment lands in. */
enum class SourceFormat(val slug: String, val label: String, val walletType: WalletType, val walletName: String) {
    GCASH("gcash", "GCash", WalletType.GCASH, "GCash"),
    MAYA("maya", "Maya", WalletType.MAYA, "Maya"),

    /** Generic bank or card alerts, and any other app the user turns on. */
    CARD("card", "Card or bank", WalletType.CARD, "Card"),
}

/** Money going out of or into the wallet the message came from. A transfer is decided later, from the wallets. */
enum class Direction { EXPENSE, INCOME }

/**
 * What a message said. [amount] is centavos and always above zero. [counterparty] is the other side as
 * the message printed it (a merchant, or a masked name), [refNo] is the normalized reference number
 * (see [Ref]), [category] is only ever set by the text reader, never by the templates.
 */
data class ParsedPayment(
    val direction: Direction,
    val amount: Long,
    val counterparty: String?,
    val refNo: String?,
    val category: String? = null,
)

object Parser {
    /** A message longer than this is not a payment alert; the regexes never see it. */
    const val MAX_TEXT = 2000

    /** Longest merchant or name kept. */
    private const val MAX_NAME = 80

    /** True when the text has a peso amount in it at all. Messages without one (codes, promos) are not payments. */
    fun hasAmount(text: String): Boolean = text.length <= MAX_TEXT && AMOUNT.containsMatchIn(text)

    /** The payment [text] describes, or null when it is not clearly one. [text] should come from [MessageText.clean]. */
    fun parse(format: SourceFormat, text: String): ParsedPayment? {
        if (text.isBlank() || text.length > MAX_TEXT) return null
        val hits = templates(format).flatMap { it.hits(text) }
        val first = hits.firstOrNull() ?: return null
        if (hits.any { it.direction != first.direction || it.amount != first.amount }) return null
        val name = hits.firstNotNullOfOrNull { it.counterparty }
        return ParsedPayment(first.direction, first.amount, name, Ref.find(text))
    }

    // --- templates ---------------------------------------------------------------

    private class Hit(val direction: Direction, val amount: Long, val counterparty: String?)

    /**
     * One message shape. Group 1 and 2 are the whole pesos and the centavos of [AMT] (so the pattern
     * must contain [AMT] before any other capture group), group 3 is the other party when there is one.
     */
    private class Template(val direction: Direction, pattern: String, val fallbackName: String? = null) {
        private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

        fun hits(text: String): Sequence<Hit> = regex.findAll(text).mapNotNull { m ->
            val figure = m.groupValues[1] + (m.groups[2]?.value?.let { ".$it" } ?: "")
            val amount = Money.parse(figure)?.takeIf { it > 0 } ?: return@mapNotNull null
            val raw = if (m.groups.size > 3) m.groups[3]?.value else null
            // "payment of PHP 5,000 to your card" is money arriving somewhere of the user's, not a purchase.
            if (raw != null && raw.trim().startsWith("your ", ignoreCase = true)) return@mapNotNull null
            Hit(direction, amount, raw?.let(::cleanName) ?: fallbackName)
        }
    }

    private fun cleanName(raw: String): String? {
        val s = raw.replace(WHITESPACE, " ").trim().trim('.', ',', ';', ':', '-', ' ')
        return if (s.any { it.isLetterOrDigit() }) s.take(MAX_NAME) else null
    }

    private fun templates(format: SourceFormat): List<Template> = when (format) {
        SourceFormat.GCASH -> GCASH
        SourceFormat.MAYA -> MAYA
        SourceFormat.CARD -> CARD
    }

    private val WHITESPACE = Regex("\\s+")

    // --- pieces ------------------------------------------------------------------

    /** `PHP 1,234.50`, `₱1,234.5`, `P500`: groups 1 (pesos, commas allowed) and 2 (centavos). Misplaced commas or a third decimal do not match. */
    private const val AMT =
        """(?<![A-Za-z0-9])(?:(?i:php)|₱|P)\s?([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.([0-9]{1,2}))?(?![0-9])(?!(?:,|\.)[0-9])"""

    private val AMOUNT = Regex(AMT, RegexOption.IGNORE_CASE)

    /** "You have", "You've", "You", then an optional "successfully". */
    private const val YOU = """(?:you(?:'ve|\s+have|\s+had)?\s+)?(?:successfully\s+|just\s+)?"""

    /** The other party: up to 80 characters, ended by [END]. Group 3. */
    private const val WHO = """(.{1,80}?)"""

    /** Where a name stops: a joining word, a comma or bracket, a full stop that ends the sentence, or the end of the text. */
    private const val END =
        """(?=\s+(?:on|using|with|via|ref|reference|your|has|was|is|for|at|from)\b|\s*[,;(]|\.(?:\s|\z)|\s*\z)"""

    private fun t(direction: Direction, vararg parts: String, fallbackName: String? = null) =
        Template(direction, parts.joinToString(""), fallbackName)

    private val CASH_IN = """\bcash(?:ed)?[- ]?in(?:\s+transaction)?\s+(?:of\s+|for\s+)?"""
    private val OPTIONAL_FROM = """(?:\s+(?:via|from|through|using)\s+""" + WHO + END + """)?"""

    private val GCASH = listOf(
        // You have sent PHP 500.00 of GCash to JU** DE** CR** 0917 123 4567.
        t(Direction.EXPENSE, YOU, """\bsent\s+""", AMT, """(?:\s+(?:of|via|through)\s+gcash)?\s+to\s+""", WHO, END),
        // You have paid PHP 250.00 to SM Store.
        t(Direction.EXPENSE, YOU, """\bpaid\s+""", AMT, """(?:\s+(?:of|via|through|using)\s+gcash)?\s+(?:to|at)\s+""", WHO, END),
        // Your payment of PHP 1,500.00 to MERALCO has been successfully processed.
        t(
            Direction.EXPENSE, """\b(?:your\s+)?payment\s+of\s+""", AMT, """\s+(?:to|for)\s+""", WHO,
            """(?=\s+(?:has\s+been|was|is)\s+(?:successfully\s+)?(?:processed|received|completed|successful))""",
        ),
        // You have received PHP 500.00 of GCash from JU** DE** 0917 123 4567.
        t(Direction.INCOME, YOU, """\breceived\s+""", AMT, """(?:\s+(?:of|via)\s+gcash)?\s+from\s+""", WHO, END),
        // You have received PHP 1,000.00 via Cash In from BPI.
        t(
            Direction.INCOME, YOU, """\breceived\s+""", AMT, """\s+via\s+cash[- ]?in\b""", OPTIONAL_FROM,
            fallbackName = "Cash in",
        ),
        // You have successfully cash-in PHP 1,000.00 via BPI.
        t(Direction.INCOME, CASH_IN, AMT, OPTIONAL_FROM, fallbackName = "Cash in"),
    )

    private val MAYA = listOf(
        // You sent PHP 500.00 to 0917 123 4567.
        t(Direction.EXPENSE, YOU, """\bsent\s+""", AMT, """\s+to\s+""", WHO, END),
        // You paid PHP 100.00 to Merchant.
        t(Direction.EXPENSE, YOU, """\bpaid\s+""", AMT, """\s+(?:to|at)\s+""", WHO, END),
        // Purchase of PHP 450.00 at Merchant.
        t(Direction.EXPENSE, """\b(?:purchase|payment)\s+of\s+""", AMT, """\s+(?:at|to)\s+""", WHO, END),
        // You received PHP 100.00 from JU** DE**.
        t(Direction.INCOME, YOU, """\breceived\s+""", AMT, """\s+from\s+""", WHO, END),
        // Cash in successful. PHP 500.00 has been added to your Maya wallet.
        t(
            Direction.INCOME, AMT, """\s+(?:has\s+been|was)\s+(?:successfully\s+)?(?:added|credited)\s+to\s+your\s+(?:maya|paymaya)""",
            fallbackName = "Cash in",
        ),
        t(Direction.INCOME, CASH_IN, AMT, OPTIONAL_FROM, fallbackName = "Cash in"),
    )

    private val CARD = listOf(
        // A purchase of PHP 1,234.56 at SHOPEE on 01/15/24.
        t(Direction.EXPENSE, """\b(?:purchase|transaction|payment|charge)\s+(?:of|for)\s+""", AMT, """\s+(?:was\s+made\s+)?(?:at|to)\s+""", WHO, END),
        // Your card ending 1234 was used for PHP 1,234.56 at SHOPEE. / was charged PHP 750.00 at LAZADA.
        t(
            Direction.EXPENSE, """\b(?:was\s+|were\s+)?(?:used|charged|swiped|debited)\s+(?:for\s+)?(?:(?:a\s+)?(?:purchase|transaction)\s+of\s+)?""",
            AMT, """\s+(?:at|to)\s+""", WHO, END,
        ),
        // You spent PHP 300.00 at JOLLIBEE.
        t(Direction.EXPENSE, """\bspent\s+""", AMT, """\s+(?:at|on)\s+""", WHO, END),
        // A refund of PHP 100.00 from SHOPEE.
        t(
            Direction.INCOME, """\brefund\s+of\s+""", AMT, """(?:\s+(?:from|by|at)\s+""", WHO, END, """)?""",
            fallbackName = "Refund",
        ),
    )
}

/** Reference numbers ("Ref No. 1234 567 890123"), normalized so the same payment reads the same in a notification and an SMS. */
object Ref {
    private const val MIN_LENGTH = 6
    private const val MAX_LENGTH = 32

    // "Ref No.", "Ref. No:", "Reference ID", "Ref #", "Reference Number". Digits in groups ("1234 567 890123") or one token with at least one digit.
    private val REF = Regex(
        """\bref(?:erence)?\b\.?\s*(?:no\b\.?|number\b|num\b\.?|id\b|#)?\s*[:#]?\s*((?:[0-9]{3,5}(?: [0-9]{3,6}){1,3})|(?=[A-Za-z0-9-]*[0-9])[A-Za-z0-9-]{6,32})""",
        RegexOption.IGNORE_CASE,
    )

    /** Letters and digits only, upper case, 6 to 32 of them and at least one digit. Anything else is null. */
    fun normalize(raw: String?): String? {
        val s = raw?.filter { it.isLetterOrDigit() }?.uppercase() ?: return null
        return s.takeIf { it.length in MIN_LENGTH..MAX_LENGTH && it.any { c -> c.isDigit() } }
    }

    /** The message's reference number, or null if there is none or the message names two different ones. */
    fun find(text: String): String? {
        val found = REF.findAll(text).mapNotNull { normalize(it.groupValues[1]) }.toSet()
        return found.singleOrNull()
    }
}

/** Where a payment went: its type and wallets. A payment to or from another of the user's own wallets is a transfer. */
data class Placement(val type: TxnType, val walletId: String, val toWalletId: String?)

object Transfers {
    /**
     * [source] is the wallet the message came from. When the other party is clearly one of the user's
     * other [wallets] (named in the message, or "GCash" / "Maya" for a wallet of that type), money
     * out becomes a transfer from [source] to that wallet and money in a transfer from it to [source].
     * Cash wallets never match ("Cash in" is not a wallet), and two matching wallets mean not sure,
     * so it stays a plain expense or income.
     */
    fun place(parsed: ParsedPayment, source: Wallet, wallets: List<Wallet>): Placement {
        val other = ownWalletNamed(parsed.counterparty, source, wallets)
        return when {
            other == null -> Placement(if (parsed.direction == Direction.INCOME) TxnType.INCOME else TxnType.EXPENSE, source.id, null)
            parsed.direction == Direction.EXPENSE -> Placement(TxnType.TRANSFER, source.id, other.id)
            else -> Placement(TxnType.TRANSFER, other.id, source.id)
        }
    }

    private fun ownWalletNamed(counterparty: String?, source: Wallet, wallets: List<Wallet>): Wallet? {
        val words = counterparty?.let(::words)?.takeIf { it.isNotBlank() } ?: return null
        val padded = " $words "
        val matches = wallets.filter { it.id != source.id && !it.deleted && it.type != WalletType.CASH }.filter { wallet ->
            val name = words(wallet.name)
            val keywords = when (wallet.type) {
                WalletType.GCASH -> listOf("gcash")
                WalletType.MAYA -> listOf("maya", "paymaya")
                else -> emptyList()
            }
            (name.length >= 3 && padded.contains(" $name ")) || keywords.any { padded.contains(" $it ") }
        }
        return matches.singleOrNull()
    }

    /** Lower case letters and digits separated by single spaces. */
    private fun words(s: String): String =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex(" +"), " ")
}
