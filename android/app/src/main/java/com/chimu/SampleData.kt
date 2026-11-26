package com.chimu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.util.Locale
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TEMPORARY test data: about six months of made-up wallets, transactions and budgets so every screen has something
 * to show while the app is being tried out. It exists only in debug builds.
 *
 * - It lives on this phone only. The rows are stored with `dirty = 0`, so sync never sends them to Supabase.
 *   (If you edit one of them it becomes an ordinary edit and will try to sync; remove the sample data first if you
 *   want to test sync with real rows.)
 * - Every sample id starts with [ID_PREFIX], which is how [remove] finds them again.
 * - The data is the same every time for the same user and day, so what you see is repeatable.
 *
 * To get rid of it for good: set [ENABLED] to false, delete this file, and delete the two lines that mention
 * `SampleData` in ChimuRoot.kt and ProfileScreen.kt (and the `db` line in ChimuApp.kt).
 */
object SampleData {
    /** Master switch. False means nothing here runs, and the Profile tab shows no sample-data row. */
    const val ENABLED = true

    /** Every sample row's id starts with this. A real id is a random UUID, so the two never clash. */
    const val ID_PREFIX = "5a3b1e00-"

    private const val SEED = 20261126L
    private const val DAYS_BACK = 180
    private val TABLES = listOf("transactions", "budgets", "wallets") // children first

    fun isSample(id: String): Boolean = id.startsWith(ID_PREFIX)

    class Rows(val wallets: List<Wallet>, val txns: List<Txn>, val budgets: List<Budget>)

    /** A made-up place to spend: [wallet] indexes the wallet list built in [generate]. */
    private class Spend(
        val merchant: String,
        val category: String,
        val minPesos: Int,
        val maxPesos: Int,
        val wallet: Int,
        val vat: Boolean = false,
    )

    private val spends = listOf(
        Spend("Jollibee", "food", 120, 420, 1),
        Spend("McDonald's", "food", 150, 480, 0),
        Spend("Starbucks", "food", 180, 360, 4),
        Spend("Mang Inasal", "food", 140, 300, 0),
        Spend("7-Eleven", "food", 60, 250, 1),
        Spend("Puregold", "groceries", 600, 3200, 0, vat = true),
        Spend("SM Supermarket", "groceries", 800, 3800, 4, vat = true),
        Spend("Grab", "transport", 120, 650, 1),
        Spend("Angkas", "transport", 90, 300, 2),
        Spend("Shell", "transport", 800, 2200, 4, vat = true),
        Spend("Mercury Drug", "health", 150, 1200, 0, vat = true),
        Spend("Watsons", "health", 200, 900, 2, vat = true),
        Spend("Lazada", "shopping", 300, 2500, 2),
        Spend("Uniqlo", "shopping", 790, 2990, 4, vat = true),
        Spend("National Book Store", "education", 150, 900, 0, vat = true),
        Spend("SM Cinema", "entertainment", 250, 700, 1),
        Spend("Cebu Pacific", "travel", 1800, 5200, 4),
    )

    /** Builds the whole set for [owner], with dates counted back from [today]. Pure: touches no database. */
    fun generate(owner: String, today: LocalDate = LocalDate.now()): Rows {
        val rnd = Random(SEED)

        fun wallet(n: Int, name: String, type: WalletType, openingCentavos: Long) =
            Wallet(id = id(owner, 1, n), ownerId = owner, name = name, type = type, openingBalance = openingCentavos)

        val wallets = listOf(
            wallet(1, "Cash on hand", WalletType.CASH, 250_000),
            wallet(2, "GCash", WalletType.GCASH, 500_000),
            wallet(3, "Maya", WalletType.MAYA, 120_000),
            wallet(4, "BPI Savings", WalletType.SAVINGS, 4_000_000),
            wallet(5, "BPI Credit Card", WalletType.CARD, -320_000), // a card that already carries a balance
        )
        val cash = wallets[0]
        val gcash = wallets[1]
        val maya = wallets[2]
        val bpi = wallets[3]
        val card = wallets[4]

        val txns = ArrayList<Txn>()
        var n = 0
        fun add(
            day: LocalDate,
            type: TxnType,
            from: Wallet,
            centavos: Long,
            category: String?,
            merchant: String?,
            to: Wallet? = null,
            note: String? = null,
            business: Boolean = false,
            vatIncluded: Boolean = false,
        ) {
            // A receipt's VAT is 12/112 of the total when prices already include it.
            val vat = if (vatIncluded) centavos * 12 / 112 else null
            txns += Txn(
                id = id(owner, 2, ++n),
                ownerId = owner,
                type = type,
                walletId = from.id,
                toWalletId = to?.id,
                amount = centavos,
                date = day.toString(),
                category = category,
                merchant = merchant,
                note = note,
                isBusiness = business,
                vatableSales = vat?.let { centavos - it },
                vat = vat,
            )
        }

        for (back in DAYS_BACK downTo 0) {
            val day = today.minusDays(back.toLong())
            val dom = day.dayOfMonth

            // The everyday spending: none to three small purchases a day.
            val roll = rnd.nextInt(100)
            val count = when {
                roll < 22 -> 0
                roll < 62 -> 1
                roll < 87 -> 2
                else -> 3
            }
            repeat(count) {
                val s = spends[rnd.nextInt(spends.size)]
                val pesos = s.minPesos + rnd.nextInt(s.maxPesos - s.minPesos + 1)
                val centavos = pesos * 100L + rnd.nextInt(4) * 25L
                add(day, TxnType.EXPENSE, wallets[s.wallet], centavos, s.category, s.merchant, vatIncluded = s.vat)
            }

            // Things that repeat every month.
            when (dom) {
                3 -> add(day, TxnType.TRANSFER, bpi, 300_000, null, null, to = card, note = "Card payment")
                5 -> add(day, TxnType.EXPENSE, maya, 16_900, "entertainment", "Spotify", note = "Monthly")
                8 -> add(day, TxnType.INCOME, bpi, (8_000 + rnd.nextInt(4_000)) * 100L, "business", "Freelance project", business = true)
                10 -> add(day, TxnType.EXPENSE, card, (2_500 + rnd.nextInt(1_300)) * 100L, "bills", "Meralco", note = "Electricity")
                12 -> add(day, TxnType.EXPENSE, bpi, 169_900, "bills", "PLDT Home", note = "Internet")
                16 -> add(day, TxnType.TRANSFER, bpi, 300_000, null, null, to = gcash, note = "Top up GCash")
                22 -> add(day, TxnType.EXPENSE, maya, 54_900, "entertainment", "Netflix", note = "Monthly")
            }
            if (dom == 15 || dom == day.lengthOfMonth()) {
                add(day, TxnType.INCOME, bpi, 2_250_000, "salary", "Salary", note = "Payroll")
            }
        }
        // A birthday gift now and then, so Income has more than one kind.
        add(today.minusDays(40), TxnType.INCOME, cash, 200_000, "gift", "Tita Cora", note = "Birthday")

        fun budget(n: Int, category: String, pesos: Long) =
            Budget(id = id(owner, 3, n), ownerId = owner, groupId = null, category = category, monthlyLimit = pesos * 100)

        val budgets = listOf(
            budget(1, "food", 6_000),
            budget(2, "groceries", 8_000),
            budget(3, "transport", 3_000),
            budget(4, "entertainment", 1_500),
            budget(5, "shopping", 4_000),
        )
        return Rows(wallets, txns, budgets)
    }

    /** `5a3b1e00-<kind>-4000-8000-<owner hash><n>`: a well-formed UUID, different for every owner, so two accounts never clash. */
    private fun id(owner: String, kind: Int, n: Int): String =
        String.format(Locale.ROOT, "%s%04x-4000-8000-%08x%04x", ID_PREFIX, kind, owner.hashCode(), n and 0xFFFF)

    /** True while any sample row is on this phone for the open user. */
    fun isLoaded(app: ChimuApp): Boolean = app.repo.txns.value.any { isSample(it.id) }

    /**
     * Stores the sample set for the signed-in user (replacing an earlier copy), marked clean so it never syncs, and
     * refreshes the screens.
     */
    suspend fun load(app: ChimuApp, today: LocalDate = LocalDate.now()) {
        val owner = app.account.identity.value?.userId ?: return
        withContext(Dispatchers.IO) {
            val rows = generate(owner, today)
            val db = app.db
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                deleteSample(w)
                rows.wallets.forEach { db.insertWallet(it) }
                rows.txns.forEach { db.insertTxn(it) }
                rows.budgets.forEach { db.insertBudget(it) }
                for (table in TABLES) w.execSQL("UPDATE $table SET dirty = 0 WHERE id LIKE ?", arrayOf<Any>("$ID_PREFIX%"))
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        app.repo.refresh()
    }

    /** Deletes every sample row from this phone and refreshes the screens. Real rows are never touched. */
    suspend fun remove(app: ChimuApp) {
        withContext(Dispatchers.IO) {
            val w = app.db.writableDatabase
            w.beginTransaction()
            try {
                deleteSample(w)
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        app.repo.refresh()
    }

    /** Loads the sample set the first time each user opens a debug build, and never again (so removing it sticks). */
    suspend fun loadOnceAfterSignIn(app: ChimuApp) {
        if (!ENABLED || !BuildConfig.DEBUG) return
        val owner = app.account.identity.value?.userId ?: return
        val prefs = app.getSharedPreferences("chimu", Context.MODE_PRIVATE)
        val key = "sample_data_offered_$owner"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        load(app)
    }

    private fun deleteSample(w: SQLiteDatabase) {
        for (table in TABLES) w.execSQL("DELETE FROM $table WHERE id LIKE ?", arrayOf<Any>("$ID_PREFIX%"))
    }
}
