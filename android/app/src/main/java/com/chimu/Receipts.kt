package com.chimu

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * What the `extract` function read from a receipt, in the phone's units: money in centavos (RULES 7).
 * Every field is null when the receipt didn't show it, so the Review screen can point at the gaps.
 * A receipt is an expense or income; a transfer needs a second wallet that a photo can't name.
 */
data class ExtractedReceipt(
    val type: TxnType,
    val amount: Long?,
    val date: String?,
    val merchant: String?,
    val category: String?,
    val refNo: String?,
    val sellerTin: String?,
    val invoiceNo: String?,
    val vatableSales: Long?,
    val vat: Long?,
    val vatExempt: Long?,
)

/**
 * Turns the `extract` reply into an [ExtractedReceipt]. Pure: [SupaRemote] hands it the reply as a
 * map whose values are `String`, `BigDecimal` (a JSON number, kept exact), or null.
 *
 * The function already cleans its answer (supabase/functions/extract/schema.ts); this checks again
 * because the phone stores what comes out. Anything that isn't what it should be becomes null, and
 * the user fills it in. Only a reply with no usable shape at all is an error.
 */
object ReceiptReader {
    /** Pesos -> centavos, exactly: `450.5` -> 45050. Negative, non-numeric or absurd values give null. */
    fun centavos(value: Any?): Long? {
        val pesos = when (value) {
            null -> return null
            is BigDecimal -> value
            is Number -> value.toString().toBigDecimalOrNull() ?: return null
            is String -> value.trim().toBigDecimalOrNull() ?: return null
            else -> return null
        }
        if (pesos.signum() < 0 || pesos.precision() - pesos.scale() > MAX_WHOLE_DIGITS) return null
        return pesos.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    }

    /** @throws IllegalArgumentException if [fields] has none of the reply's keys, so it is not a reply at all. */
    fun parse(fields: Map<String, Any?>): ExtractedReceipt {
        require(KEYS.any { it in fields }) { "The reader's reply has none of the expected fields" }

        // A transfer needs a destination wallet a photo can't give, so it is logged as an expense and the user can change it.
        val type = when (text(fields["type"], 20)?.lowercase()) {
            TxnType.INCOME.slug -> TxnType.INCOME
            else -> TxnType.EXPENSE
        }
        val category = text(fields["category"], 30)?.lowercase()?.takeIf { slug -> Categories.forType(type).any { it.slug == slug } }
        return ExtractedReceipt(
            type = type,
            amount = centavos(fields["amount"])?.takeIf { it > 0 },
            date = date(fields["date"]),
            merchant = text(fields["merchant"], MAX_TEXT),
            category = category,
            refNo = text(fields["ref_no"], 64),
            sellerTin = text(fields["seller_tin"], 32),
            invoiceNo = text(fields["invoice_no"], 64),
            vatableSales = centavos(fields["vatable_sales"]),
            vat = centavos(fields["vat"]),
            vatExempt = centavos(fields["vat_exempt"]),
        )
    }

    private fun text(value: Any?, max: Int): String? =
        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    /** A real `YYYY-MM-DD` date, or null. */
    private fun date(value: Any?): String? {
        val s = (value as? String)?.trim() ?: return null
        if (!DATE_SHAPE.matches(s)) return null
        return runCatching { LocalDate.parse(s).toString() }.getOrNull()
    }

    private val KEYS = listOf("type", "amount", "date", "merchant", "category", "ref_no")
    private val DATE_SHAPE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private const val MAX_TEXT = 120
    private const val MAX_WHOLE_DIGITS = 12
}

/** Finds the payment a scanned receipt probably belongs to (DESIGN.md -> Auto-logging -> Receipt matching). */
object ReceiptMatch {
    const val WINDOW_DAYS = 2L

    /**
     * The expense with exactly [amount] centavos closest to [date], within [WINDOW_DAYS] days either way,
     * or null. Skips the scan itself, deleted rows, rows that already have a photo, and anything that
     * isn't an expense (a receipt is proof of a purchase). On a tie in days the most recently edited wins.
     */
    fun find(txns: List<Txn>, scanId: String, amount: Long, date: String): Txn? {
        if (amount <= 0) return null
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        return txns
            .asSequence()
            .filter { it.id != scanId && !it.deleted && it.type == TxnType.EXPENSE && it.amount == amount }
            .filter { it.receiptPath.isNullOrEmpty() && it.localPhoto.isNullOrEmpty() }
            .mapNotNull { txn ->
                val other = runCatching { LocalDate.parse(txn.date) }.getOrNull() ?: return@mapNotNull null
                val apart = abs(ChronoUnit.DAYS.between(day, other))
                if (apart <= WINDOW_DAYS) txn to apart else null
            }
            .sortedWith(compareBy<Pair<Txn, Long>> { it.second }.thenByDescending { it.first.updatedAt })
            .firstOrNull()
            ?.first
    }
}

/** The arithmetic behind shrinking a photo, kept free of Android types so it can be checked. */
object ReceiptImage {
    /** The longest edge a stored receipt photo may have (RFC-004). */
    const val MAX_EDGE = 1600
    const val JPEG_QUALITY = 80

    /** [width] x [height] scaled down to fit [maxEdge] on the long side, keeping the shape. Never scales up. */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "The picture has no size" }
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return max(1, Math.round(width * scale).toInt()) to max(1, Math.round(height * scale).toInt())
    }

    /**
     * `BitmapFactory.Options.inSampleSize` for a first, cheap decode: the biggest power of two that
     * still leaves the long side at or above [maxEdge], so the exact resize afterwards loses nothing.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** What to do to a decoded picture to make it upright: rotate [degrees] clockwise, then mirror left-right if [mirror]. */
    data class Upright(val degrees: Int, val mirror: Boolean)

    /** EXIF orientation values 1 to 8. Anything else is treated as already upright. */
    fun upright(exifOrientation: Int): Upright = when (exifOrientation) {
        2 -> Upright(0, true)
        3 -> Upright(180, false)
        4 -> Upright(180, true)
        5 -> Upright(90, true)
        6 -> Upright(90, false)
        7 -> Upright(270, true)
        8 -> Upright(270, false)
        else -> Upright(0, false)
    }
}

/** Words the lists and the review screen use about a scan's progress. Pure, so the wording is tested. */
object ReceiptText {
    /** One line for a list row, or null for an ordinary entry. */
    fun listNote(txn: Txn): String? = when {
        txn.source != "receipt" -> null
        txn.status == "pending" && txn.extractError != null -> "Couldn't be read. Tap to fill it in."
        txn.status == "pending" -> "Waiting to be read"
        txn.status == "extracted" -> "Read from receipt. Tap to check."
        else -> null
    }

    /** The amount for a list row. A scan that hasn't been read has no amount yet. */
    fun listAmount(txn: Txn): String =
        if (txn.status == "pending" && txn.source == "receipt") "Not read" else Money.formatSigned(txn.type, txn.amount)
}
