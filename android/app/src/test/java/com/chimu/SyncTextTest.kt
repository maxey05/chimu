package com.chimu

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTextTest {
    private val now = 1_795_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun neverSynced() {
        assertEquals("Not synced yet", SyncText.lastSynced(null, now))
    }

    @Test
    fun underAMinute_isJustNow() {
        assertEquals("Synced just now", SyncText.lastSynced(now - 59_999, now))
        assertEquals("Synced just now", SyncText.lastSynced(now, now))
    }

    @Test
    fun aClockThatMovedBackwards_neverShowsANegativeAge() {
        assertEquals("Synced just now", SyncText.lastSynced(now + 5 * minute, now))
    }

    @Test
    fun minutes_hoursAndDays_areCountedAndPluralised() {
        assertEquals("Synced 1 minute ago", SyncText.lastSynced(now - minute, now))
        assertEquals("Synced 59 minutes ago", SyncText.lastSynced(now - 59 * minute, now))
        assertEquals("Synced 1 hour ago", SyncText.lastSynced(now - hour, now))
        assertEquals("Synced 23 hours ago", SyncText.lastSynced(now - 23 * hour - 59 * minute, now))
        assertEquals("Synced 1 day ago", SyncText.lastSynced(now - day, now))
        assertEquals("Synced 12 days ago", SyncText.lastSynced(now - 12 * day, now))
    }

    @Test
    fun pendingChanges() {
        assertEquals("Nothing waiting to sync", SyncText.pending(0))
        assertEquals("1 change waiting to sync", SyncText.pending(1))
        assertEquals("7 changes waiting to sync", SyncText.pending(7))
    }
}
