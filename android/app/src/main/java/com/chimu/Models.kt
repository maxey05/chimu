package com.chimu

/** Row types that mirror the DESIGN.md tables. Money is always integer centavos (RULES 7). */

enum class TxnType(val slug: String, val label: String) {
    EXPENSE("expense", "Expense"),
    INCOME("income", "Income"),
    TRANSFER("transfer", "Transfer");

    companion object {
        fun fromSlug(slug: String): TxnType = entries.first { it.slug == slug }
    }
}

enum class WalletType(val slug: String, val label: String) {
    CASH("cash", "Cash"),
    GCASH("gcash", "GCash"),
    MAYA("maya", "Maya"),
    CARD("card", "Card"),
    BANK("bank", "Bank"),
    SAVINGS("savings", "Savings");

    companion object {
        fun fromSlug(slug: String): WalletType = entries.first { it.slug == slug }
    }
}

data class Category(val slug: String, val label: String)

/** Fixed category lists from DESIGN.md. Stored as lowercase slugs; custom categories come later. */
object Categories {
    const val OTHER = "other"

    val expense = listOf(
        Category("food", "Food & Dining"),
        Category("groceries", "Groceries"),
        Category("transport", "Transport"),
        Category("bills", "Bills & Utilities"),
        Category("shopping", "Shopping"),
        Category("health", "Health"),
        Category("education", "Education"),
        Category("entertainment", "Entertainment"),
        Category("travel", "Travel"),
        Category(OTHER, "Other"),
    )

    val income = listOf(
        Category("salary", "Salary"),
        Category("business", "Business"),
        Category("gift", "Gift"),
        Category(OTHER, "Other"),
    )

    /** Transfers have no category. */
    fun forType(type: TxnType): List<Category> = when (type) {
        TxnType.EXPENSE -> expense
        TxnType.INCOME -> income
        TxnType.TRANSFER -> emptyList()
    }

    /** Every slug that appears in either list, for filtering across types. */
    val all: List<Category> = (expense + income).distinctBy { it.slug }

    fun label(slug: String?): String {
        if (slug == null) return ""
        return all.firstOrNull { it.slug == slug }?.label ?: slug
    }
}

data class Wallet(
    val id: String,
    val ownerId: String,
    val name: String,
    val type: WalletType,
    /** Centavos. May be negative (a card that already carries a balance owed). */
    val openingBalance: Long,
    val updatedAt: Long = 0,
    /** Tombstone. "Archived" wallets are tombstoned wallets: the schema has no archived column. */
    val deleted: Boolean = false,
)

data class Txn(
    val id: String,
    val ownerId: String,
    val type: TxnType,
    val walletId: String,
    /** Transfers only; null for expense and income. */
    val toWalletId: String?,
    /** Centavos, always positive. The type decides the direction. */
    val amount: Long,
    /** `YYYY-MM-DD`. */
    val date: String,
    val category: String?,
    val merchant: String?,
    val note: String?,
    val isBusiness: Boolean,
    val source: String = "manual",
    /** `inbox`, `pending`, `extracted` or `confirmed`. Manual entries are `confirmed`. */
    val status: String = "confirmed",
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)
