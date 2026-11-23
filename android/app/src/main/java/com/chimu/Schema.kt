package com.chimu

/**
 * Local SQLite schema. It mirrors every table in DESIGN.md (the Supabase columns, same names),
 * plus the phone-only columns: `dirty` on every table, and `status` and `local_photo` on
 * `transactions`. Kept free of Android types so the SQL can be checked on its own.
 *
 * Conventions: ids are client-made UUID text, money is integer centavos, times are epoch
 * milliseconds, booleans are 0/1, dates are `YYYY-MM-DD` text.
 *
 * No foreign keys on the phone: it also caches other group members' rows (RFC-006), pulled in
 * table order, and the server already enforces the references.
 *
 * Schema changes after release need a new version here, an `onUpgrade` step in `Db`, and the
 * matching Supabase migration (RULES 11).
 */
object Schema {
    const val VERSION = 1

    val CREATE_STATEMENTS: List<String> = listOf(
        // Not synced as syncable rows: written by trigger / RPC on the server, cached read-only here.
        """
        CREATE TABLE profiles (
            id           TEXT PRIMARY KEY,
            display_name TEXT NOT NULL DEFAULT '',
            dirty        INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE groups (
            id          TEXT PRIMARY KEY,
            name        TEXT NOT NULL,
            invite_code TEXT NOT NULL,
            owner_id    TEXT NOT NULL,
            created_at  TEXT,
            dirty       INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE group_members (
            group_id  TEXT NOT NULL,
            user_id   TEXT NOT NULL,
            role      TEXT NOT NULL,
            joined_at TEXT,
            dirty     INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (group_id, user_id)
        )
        """,

        // Syncable tables: id, owner_id, updated_at (ms), deleted (tombstone), rev (server cursor), dirty.
        """
        CREATE TABLE wallets (
            id              TEXT PRIMARY KEY,
            owner_id        TEXT NOT NULL,
            updated_at      INTEGER NOT NULL,
            deleted         INTEGER NOT NULL DEFAULT 0,
            rev             INTEGER NOT NULL DEFAULT 0,
            dirty           INTEGER NOT NULL DEFAULT 1,
            name            TEXT NOT NULL,
            type            TEXT NOT NULL,
            opening_balance INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE transactions (
            id            TEXT PRIMARY KEY,
            owner_id      TEXT NOT NULL,
            updated_at    INTEGER NOT NULL,
            deleted       INTEGER NOT NULL DEFAULT 0,
            rev           INTEGER NOT NULL DEFAULT 0,
            dirty         INTEGER NOT NULL DEFAULT 1,
            type          TEXT NOT NULL,
            wallet_id     TEXT NOT NULL,
            to_wallet_id  TEXT,
            amount        INTEGER NOT NULL,
            date          TEXT NOT NULL,
            category      TEXT,
            merchant      TEXT,
            note          TEXT,
            is_business   INTEGER NOT NULL DEFAULT 0,
            source        TEXT NOT NULL DEFAULT 'manual',
            ref_no        TEXT,
            recurring_id  TEXT,
            receipt_path  TEXT,
            seller_tin    TEXT,
            invoice_no    TEXT,
            vatable_sales INTEGER,
            vat           INTEGER,
            vat_exempt    INTEGER,
            status        TEXT NOT NULL DEFAULT 'confirmed',
            local_photo   TEXT
        )
        """,
        """
        CREATE TABLE budgets (
            id            TEXT PRIMARY KEY,
            owner_id      TEXT NOT NULL,
            updated_at    INTEGER NOT NULL,
            deleted       INTEGER NOT NULL DEFAULT 0,
            rev           INTEGER NOT NULL DEFAULT 0,
            dirty         INTEGER NOT NULL DEFAULT 1,
            group_id      TEXT,
            category      TEXT,
            monthly_limit INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE recurring (
            id                 TEXT PRIMARY KEY,
            owner_id           TEXT NOT NULL,
            updated_at         INTEGER NOT NULL,
            deleted            INTEGER NOT NULL DEFAULT 0,
            rev                INTEGER NOT NULL DEFAULT 0,
            dirty              INTEGER NOT NULL DEFAULT 1,
            type               TEXT NOT NULL,
            wallet_id          TEXT NOT NULL,
            amount             INTEGER NOT NULL,
            category           TEXT,
            merchant           TEXT,
            note               TEXT,
            frequency          TEXT NOT NULL,
            next_date          TEXT NOT NULL,
            remind_days_before INTEGER NOT NULL DEFAULT 1,
            active             INTEGER NOT NULL DEFAULT 1
        )
        """,
        """
        CREATE TABLE goals (
            id         TEXT PRIMARY KEY,
            owner_id   TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted    INTEGER NOT NULL DEFAULT 0,
            rev        INTEGER NOT NULL DEFAULT 0,
            dirty      INTEGER NOT NULL DEFAULT 1,
            name       TEXT NOT NULL,
            target     INTEGER NOT NULL,
            deadline   TEXT,
            wallet_id  TEXT NOT NULL
        )
        """,

        "CREATE INDEX wallets_owner_idx ON wallets (owner_id)",
        "CREATE INDEX transactions_owner_date_idx ON transactions (owner_id, date)",
    ).map { it.trimIndent().trim() }
}
