package com.chimu

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What [PaymentIntake] needs from the phone's database. [InboxStore] is the real one; tests use an in-memory one. */
interface InboxLocal {
    /** [owner]'s wallets, archived ones included (they are `deleted`). */
    fun wallets(owner: String): List<Wallet>

    /** Creates a wallet as a normal local write (`dirty = 1`, so it syncs). */
    fun addWallet(wallet: Wallet)

    /** [owner]'s live rows that may be the same payment: seen at or after [sinceMs], or with reference number [refNo]. */
    fun dedupCandidates(owner: String, refNo: String?, sinceMs: Long): List<Txn>

    /** Stores an `inbox` row. It never syncs until the user confirms it (RULES 17). */
    fun addInbox(txn: Txn)
}

/**
 * Reads one message with the `extract` Edge Function in `text` mode. A failure (offline, busy,
 * refused, signed out) is an exception, never a result. Only the single message text and the source
 * name are sent (RULES 17).
 */
interface MessageReader {
    suspend fun read(text: String, source: String): ExtractedReceipt
}

sealed interface IntakeResult {
    /** A new Inbox item. [createdWallet] is true when the source's wallet had to be created too (so sync should run). */
    data class Added(val txn: Txn, val createdWallet: Boolean) : IntakeResult

    /** The same payment is already in the Inbox or the ledger. */
    object Duplicate : IntakeResult

    /** Not a payment message: no amount in it. */
    object Ignored : IntakeResult
}

/**
 * Turns one incoming message into an Inbox item (DESIGN.md -> Auto-logging):
 * clean the text, require an amount in it, read it with [Parser], and only when the parser returns
 * null and the phone is online ask [reader]. Then pick the source's wallet (creating it if the
 * user has none of that type), decide transfer or not, drop duplicates, and store an `inbox` row.
 * A message nobody could read is still stored, unread, so the user can fill it in or dismiss it.
 *
 * Nothing here pushes anything: `inbox` rows are excluded from sync (RULES 17).
 */
class PaymentIntake(
    private val local: InboxLocal,
    /** Null when text reading is not available (no Supabase keys). */
    private val reader: MessageReader?,
    private val online: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    // The check for a duplicate and the insert must not interleave with another message's.
    private val lock = Mutex()

    /** [readTimeoutMs]: how long the text reader may take. A broadcast receiver has far less time than a listener service. */
    suspend fun handle(owner: String, message: IncomingMessage, readTimeoutMs: Long = READ_TIMEOUT_MS): IntakeResult {
        val text = MessageText.clean(message.text)
        if (!Parser.hasAmount(text)) return IntakeResult.Ignored
        val format = when (message.channel) {
            Channel.SMS -> KnownApps.formatForSender(message.origin)
            Channel.NOTIFICATION -> KnownApps.formatForPackage(message.origin)
        }
        val parsed = Parser.parse(format, text) ?: readWithModel(format, text, readTimeoutMs)
        val at = Times.sane(message.receivedAt, clock())
        return lock.withLock { store(owner, message.channel, format, text, parsed, at) }
    }

    private suspend fun readWithModel(format: SourceFormat, text: String, timeoutMs: Long): ParsedPayment? {
        val reader = reader ?: return null
        if (!online()) return null
        return try {
            withTimeoutOrNull(timeoutMs) { reader.read(text, format.slug) }?.let(::fromReading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // offline, busy, refused or signed out: the item stays unread in the Inbox
        }
    }

    private fun fromReading(receipt: ExtractedReceipt): ParsedPayment? {
        val amount = receipt.amount?.takeIf { it > 0 } ?: return null
        val direction = if (receipt.type == TxnType.INCOME) Direction.INCOME else Direction.EXPENSE
        return ParsedPayment(direction, amount, receipt.merchant, Ref.normalize(receipt.refNo), receipt.category)
    }

    private fun store(owner: String, channel: Channel, format: SourceFormat, text: String, parsed: ParsedPayment?, at: Long): IntakeResult {
        var wallets = local.wallets(owner).filter { !it.deleted }
        var source = Wallets.forFormat(format, wallets)
        var created = false
        if (source == null) {
            source = Wallet(newId(), owner, format.walletName, format.walletType, openingBalance = 0, updatedAt = clock())
            local.addWallet(source)
            wallets = wallets + source
            created = true
        }

        val placement = parsed?.let { Transfers.place(it, source, wallets) } ?: Placement(TxnType.EXPENSE, source.id, null)
        val candidate = Dedup.Candidate(parsed?.refNo, placement.type, placement.walletId, placement.toWalletId, parsed?.amount ?: 0L, at, text)
        if (Dedup.isDuplicate(candidate, local.dedupCandidates(owner, parsed?.refNo, at - Dedup.WINDOW_MS))) return IntakeResult.Duplicate

        val isTransfer = placement.type == TxnType.TRANSFER
        val txn = Txn(
            id = newId(),
            ownerId = owner,
            type = placement.type,
            walletId = placement.walletId,
            toWalletId = placement.toWalletId,
            amount = parsed?.amount ?: 0L,
            date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString(),
            category = if (isTransfer) null else parsed?.category,
            merchant = if (isTransfer) null else parsed?.counterparty,
            note = null,
            isBusiness = false,
            source = channel.slug,
            status = "inbox",
            updatedAt = clock(),
            refNo = parsed?.refNo,
            rawText = text,
            capturedAt = at,
        )
        local.addInbox(txn)
        return IntakeResult.Added(txn, created)
    }

    companion object {
        /** How long a notification may wait for the text reader. */
        const val READ_TIMEOUT_MS = 20_000L
    }
}
