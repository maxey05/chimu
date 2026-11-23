package com.chimu

/**
 * Last-writer-wins, the one rule that decides which copy of a row survives (DESIGN.md -> Sync
 * protocol). It is pure on purpose so it can be tested without a phone or a server.
 *
 * The server enforces the mirror image of this in its `lww_guard` trigger: a write whose
 * `updated_at` is older than the stored row is discarded. The phone applies the same rule when it
 * receives rows (pull, and the rows a push sends back).
 */
object Lww {
    /**
     * Whether a row that came from the server should replace the local copy.
     *
     * @param localUpdatedAt the local row's `updated_at` (epoch ms), or null if there is no local row.
     * @param remoteUpdatedAt the server row's `updated_at` (epoch ms).
     *
     * A missing local row always takes the remote one. On a tie the server wins: a tie means the
     * server row is the one this phone pushed a moment ago (or an identical edit), so taking it only
     * refreshes `rev` and clears `dirty`.
     */
    fun shouldApply(localUpdatedAt: Long?, remoteUpdatedAt: Long): Boolean =
        localUpdatedAt == null || remoteUpdatedAt >= localUpdatedAt
}
