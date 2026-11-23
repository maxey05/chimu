package com.chimu

/**
 * Money is integer centavos everywhere except at the edges (RULES 7).
 * This object is the edge: typed text in, display text out.
 */
object Money {
    /** 12 whole-peso digits keeps `pesos * 100` far inside Long. */
    private const val MAX_WHOLE_DIGITS = 12

    /** Thousands grouping as people type it: `1,234` or `12,345,678`, never `12,34`. */
    private val GROUPED = Regex("^[0-9]{1,3}(,[0-9]{3})+$")

    /**
     * Parses what a person types into centavos: `1,234.50` -> 123450, `₱5` -> 500, `.5` -> 50.
     * Returns null for anything that is not a plain amount (letters, more than two decimals,
     * misplaced commas, more than one dot, blank). A leading `-` is accepted only when
     * [allowNegative] is true, for opening balances.
     */
    fun parse(input: String, allowNegative: Boolean = false): Long? {
        var s = input.trim()

        var negative = false
        if (s.startsWith("-")) {
            if (!allowNegative) return null
            negative = true
            s = s.substring(1).trim()
        }

        if (s.startsWith("₱")) {
            s = s.substring(1).trim()
        } else if (s.startsWith("php", ignoreCase = true)) {
            s = s.substring(3).trim()
        }

        val dot = s.indexOf('.')
        val whole = if (dot < 0) s else s.substring(0, dot)
        val fraction = if (dot < 0) "" else s.substring(dot + 1)

        if (fraction.length > 2 || !fraction.all { it in '0'..'9' }) return null
        if (whole.isEmpty() && fraction.isEmpty()) return null
        if (whole.contains(',')) {
            if (!GROUPED.matches(whole)) return null
        } else if (!whole.all { it in '0'..'9' }) {
            return null
        }

        val wholeDigits = whole.replace(",", "")
        if (wholeDigits.length > MAX_WHOLE_DIGITS) return null

        val pesos = if (wholeDigits.isEmpty()) 0L else wholeDigits.toLong()
        val centavos = if (fraction.isEmpty()) 0L else fraction.padEnd(2, '0').toLong()
        val total = pesos * 100 + centavos
        return if (negative) -total else total
    }

    /** `123450` -> `₱1,234.50`. Negative amounts read `-₱1,234.50`. */
    fun format(centavos: Long): String {
        val sign = if (centavos < 0) "-" else ""
        return "$sign₱${grouped(centavos)}"
    }

    /** Plain `1234.50` with no symbol or grouping, for filling an edit field. */
    fun plain(centavos: Long): String {
        val sign = if (centavos < 0) "-" else ""
        val abs = if (centavos < 0) -centavos else centavos
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    /**
     * List-row amount: expenses read `-₱…`, income `+₱…`, transfers have no sign.
     * The sign (and the row's type label) carries the meaning, not colour (RULES 21).
     */
    fun formatSigned(type: TxnType, amount: Long): String = when (type) {
        TxnType.EXPENSE -> "-${format(amount)}"
        TxnType.INCOME -> "+${format(amount)}"
        TxnType.TRANSFER -> format(amount)
    }

    private fun grouped(centavos: Long): String {
        val abs = if (centavos < 0) -centavos else centavos
        val pesos = (abs / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return "$pesos.${(abs % 100).toString().padStart(2, '0')}"
    }
}
