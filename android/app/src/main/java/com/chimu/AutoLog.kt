package com.chimu

import kotlin.math.abs

/**
 * The pure rules of auto-logging (RFC-005): what counts as a message, which apps and senders are
 * trusted, how the same payment seen twice is spotted, and how Inbox items read. No Android types,
 * so JUnit checks all of it. The Android glue ([PaymentListener], [SmsReceiver], [AutoLogSettings])
 * only hands messages to this code.
 */

/** Where a message came from. The slug is stored as the transaction's `source`. */
enum class Channel(val slug: String) {
    NOTIFICATION("notification"),
    SMS("sms"),
}

/**
 * One message as the phone received it. [origin] is the app's package name for a notification, or the
 * sender's address for an SMS. [receivedAt] is when it arrived, epoch ms.
 */
data class IncomingMessage(val channel: Channel, val origin: String, val text: String, val receivedAt: Long)

/** An app whose notifications Chimu knows how to read. */
data class KnownApp(val packageName: String, val label: String, val format: SourceFormat)

object KnownApps {
    /** Expected package names. Not yet confirmed on a real phone: see rfcs/RFC-005-auto-logging.md -> Notes. */
    const val GCASH_PACKAGE = "com.globe.gcash.android"
    const val MAYA_PACKAGE = "com.paymaya"

    val all: List<KnownApp> = listOf(
        KnownApp(GCASH_PACKAGE, "GCash", SourceFormat.GCASH),
        KnownApp(MAYA_PACKAGE, "Maya", SourceFormat.MAYA),
    )

    /** Any other app the user turns on is read with the generic card and bank wording. */
    fun formatForPackage(packageName: String): SourceFormat =
        all.firstOrNull { it.packageName == packageName }?.format ?: SourceFormat.CARD

    /** An SMS from "GCash" or "Maya" reads like their app messages; every other allowed sender is read as a card or bank alert. */
    fun formatForSender(sender: String): SourceFormat {
        val s = sender.lowercase()
        return when {
            "gcash" in s -> SourceFormat.GCASH
            "maya" in s -> SourceFormat.MAYA
            else -> SourceFormat.CARD
        }
    }
}

/** Which wallet a source writes to. */
object Wallets {
    /**
     * The user's own wallet of the format's type (the one named like the default if there are several),
     * or null when they have none. The caller then creates the default one ("GCash", "Maya", "Card").
     */
    fun forFormat(format: SourceFormat, wallets: List<Wallet>): Wallet? {
        val ofType = wallets.filter { !it.deleted && it.type == format.walletType }
        return ofType.firstOrNull { it.name.equals(format.walletName, ignoreCase = true) } ?: ofType.firstOrNull()
    }
}

/** Cleans and bounds message text before anything reads it, stores it or sends it (RULES 18). */
object MessageText {
    /** Longest message kept. The `extract` function accepts 2000. */
    const val MAX_CHARS = 1000

    private val WHITESPACE = Regex("\\s+")

    /**
     * Control and invisible formatting characters become spaces, runs of white space become one
     * space, and the result is trimmed and cut to [MAX_CHARS]. Null gives "".
     */
    fun clean(raw: CharSequence?): String {
        if (raw == null) return ""
        val sb = StringBuilder(minOf(raw.length, MAX_CHARS * 2))
        for (ch in raw) {
            // \p{Cc} control, \p{Cf} format (zero-width, bidi marks), no-break and other spaces.
            sb.append(if (ch.isISOControl() || ch.category == CharCategory.FORMAT || ch.isWhitespace()) ' ' else ch)
            if (sb.length >= MAX_CHARS * 2) break
        }
        return sb.toString().replace(WHITESPACE, " ").trim().take(MAX_CHARS).trim()
    }

    /** A notification's title and body as one text. The long text is preferred over the short one, and the title is not repeated. */
    fun join(title: CharSequence?, text: CharSequence?, bigText: CharSequence?): String {
        val body = clean(bigText).ifEmpty { clean(text) }
        val head = clean(title)
        return when {
            head.isEmpty() -> body
            body.isEmpty() -> head
            body.startsWith(head) -> body
            else -> clean("$head $body")
        }
    }
}

/** The user's list of SMS senders that may be read. */
object SenderIds {
    const val MAX_SENDERS = 20
    private val VALID = Regex("^[A-Za-z0-9+._-]{2,16}$")

    /** A sender as the user typed it: trimmed, 2 to 16 letters, digits or `+ . _ -`. Anything else is null. */
    fun normalize(input: String?): String? = input?.trim()?.takeIf { VALID.matches(it) }

    /** True when [address] is one of the [allowed] senders (same letters, case ignored). */
    fun matches(allowed: Collection<String>, address: String?): Boolean {
        val a = address?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return allowed.any { it.equals(a, ignoreCase = true) }
    }

    /** The list with [input] added, or null if it is not a valid sender, is already there, or the list is full. */
    fun add(current: List<String>, input: String?): List<String>? {
        val sender = normalize(input) ?: return null
        if (current.size >= MAX_SENDERS || current.any { it.equals(sender, ignoreCase = true) }) return null
        return current + sender
    }
}

/** Apps that have posted a notification since Chimu was given access, so the user can pick from them. Only the package name is kept. */
object SeenApps {
    const val MAX_APPS = 100
    private val PACKAGE = Regex("^[A-Za-z0-9_.]{1,200}$")

    /** The set with [packageName] added, or null when nothing changes (not a package name, already known, list full). */
    fun record(seen: Set<String>, packageName: String?): Set<String>? {
        if (packageName == null || !PACKAGE.matches(packageName) || packageName in seen || seen.size >= MAX_APPS) return null
        return seen + packageName
    }
}

/** A SMS as the phone's PDUs give it: one part per message segment. */
data class SmsPart(val address: String?, val body: String?, val timestampMs: Long)

object SmsJoin {
    /** A long SMS arrives as several parts in one broadcast. Joins the parts of each sender, in order. Parts with no sender are dropped. */
    fun join(parts: List<SmsPart>): List<IncomingMessage> {
        val bySender = LinkedHashMap<String, MutableList<SmsPart>>()
        for (part in parts) {
            val address = part.address?.trim().orEmpty()
            if (address.isNotEmpty()) bySender.getOrPut(address) { mutableListOf() }.add(part)
        }
        return bySender.map { (address, list) ->
            IncomingMessage(Channel.SMS, address, list.joinToString("") { it.body.orEmpty() }, list.first().timestampMs)
        }
    }
}

object Times {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A message's own timestamp when it is plausible (positive, not more than a day ahead), else [now]. */
    fun sane(at: Long, now: Long): Long = if (at <= 0L || at > now + DAY_MS) now else at
}

/** Finds a payment the phone has already seen (DESIGN.md -> Auto-logging -> Dedup). */
object Dedup {
    /** The same payment arriving as a notification and an SMS shows up within this long of each other. */
    const val WINDOW_MS = 10 * 60 * 1000L

    /** A message waiting to be logged, in the shape the rules compare. [amount] is 0 for an unread message. */
    data class Candidate(
        val refNo: String?,
        val type: TxnType,
        val walletId: String,
        val toWalletId: String?,
        val amount: Long,
        val at: Long,
        val text: String,
    )

    /**
     * True when [candidate] is the payment [existing] already holds:
     * 1. Both have a reference number: they are the same payment exactly when the numbers are the
     *    same, however long ago. Two payments of the same amount with different numbers are two payments.
     * 2. Otherwise, a row seen on this phone within [WINDOW_MS] with the same type, wallets and amount.
     * 3. An unread message (amount 0) repeats when the same text came in within [WINDOW_MS].
     * [existing] must be the same owner's rows. Deleted rows and rows without a capture time (entered by hand, or
     * synced from elsewhere) are never matched by rules 2 and 3.
     */
    fun isDuplicate(candidate: Candidate, existing: List<Txn>): Boolean {
        for (row in existing) {
            if (row.deleted) continue
            val rowRef = Ref.normalize(row.refNo)
            if (candidate.refNo != null && rowRef != null) {
                if (rowRef == candidate.refNo) return true
                continue
            }
            val seenAt = row.capturedAt ?: continue
            if (abs(seenAt - candidate.at) > WINDOW_MS) continue
            if (candidate.amount > 0) {
                if (row.amount == candidate.amount && row.type == candidate.type && row.walletId == candidate.walletId &&
                    row.toWalletId == candidate.toWalletId
                ) return true
            } else if (row.amount == 0L && row.rawText == candidate.text) {
                return true
            }
        }
        return false
    }
}

/** What the Inbox and its Home badge say, and when an item can be confirmed in one tap. Pure, so the wording is tested. */
object InboxRules {
    fun isInbox(txn: Txn) = txn.status == "inbox"

    /** An item whose amount the parser and the text reader could not find. The user types it in. */
    fun isUnread(txn: Txn) = txn.amount <= 0L

    /** One tap is enough when there is an amount and the wallets are valid. */
    fun canConfirm(txn: Txn): Boolean =
        !isUnread(txn) && txn.walletId.isNotEmpty() &&
            (txn.type != TxnType.TRANSFER || (txn.toWalletId != null && txn.toWalletId != txn.walletId))

    /** The item as it is saved by the one-tap Confirm: an expense or income with no category gets "Other". */
    fun forConfirm(txn: Txn): Txn =
        if (txn.type != TxnType.TRANSFER && txn.category == null) txn.copy(category = Categories.OTHER) else txn

    fun title(txn: Txn): String = when {
        txn.type == TxnType.TRANSFER -> "Transfer"
        isUnread(txn) -> "Payment message"
        else -> txn.merchant?.takeIf { it.isNotBlank() } ?: (if (txn.type == TxnType.INCOME) "Money received" else "Payment")
    }

    fun sourceLabel(txn: Txn): String = when (txn.source) {
        Channel.SMS.slug -> "From an SMS"
        Channel.NOTIFICATION.slug -> "From a notification"
        else -> "Auto-logged"
    }

    /** The amount for a row: "Not read" when there is none, the signed amount otherwise. */
    fun amountText(txn: Txn): String = if (isUnread(txn)) "Not read" else Money.formatSigned(txn.type, txn.amount)

    /** The line that tells the user what to do with the item, in words. */
    fun note(txn: Txn): String =
        if (isUnread(txn)) "Couldn't read this message. Tap to fill it in." else "Check it, then confirm."

    fun badge(count: Int): String = when (count) {
        0 -> "Nothing to check"
        1 -> "1 payment to check"
        else -> "$count payments to check"
    }
}

/** What Inbox rows look like in SQL, kept as plain strings so they can be checked against a real SQLite without Android. */
object InboxSql {
    /** The transactions the lists, totals and balances use: never `inbox` rows. Args: owner id. */
    const val LIVE_WHERE = "owner_id = ? AND deleted = 0 AND status <> 'inbox'"

    /** The Inbox: unconfirmed auto-logged items. Args: owner id. */
    const val INBOX_WHERE = "owner_id = ? AND deleted = 0 AND status = 'inbox'"

    /** Dismissing deletes the row for good, and only an `inbox` row (it never synced). Args: id, owner id. */
    const val DISMISS_WHERE = "id = ? AND owner_id = ? AND status = 'inbox'"

    /** Rows that may be the same payment as a new message: seen at or after the time, or (when a reference number is known) with that number. Args: owner id, time, [ref no]. */
    fun dedupWhere(hasRef: Boolean): String =
        "owner_id = ? AND deleted = 0 AND (captured_at >= ?" + if (hasRef) " OR ref_no = ?)" else ")"
}
