package com.chimu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LwwTest {
    @Test
    fun aRowWeDoNotHave_isTakenWhateverItsAge() {
        assertTrue(Lww.shouldApply(localUpdatedAt = null, remoteUpdatedAt = 0))
        assertTrue(Lww.shouldApply(localUpdatedAt = null, remoteUpdatedAt = 1_795_000_000_000))
    }

    @Test
    fun aNewerServerRow_replacesTheLocalOne() {
        assertTrue(Lww.shouldApply(localUpdatedAt = 1_000, remoteUpdatedAt = 1_001))
    }

    @Test
    fun anOlderServerRow_neverReplacesTheLocalOne() {
        assertFalse(Lww.shouldApply(localUpdatedAt = 1_001, remoteUpdatedAt = 1_000))
        assertFalse(Lww.shouldApply(localUpdatedAt = 1_000, remoteUpdatedAt = 0))
    }

    @Test
    fun aTie_goesToTheServer() {
        // The server row is the one the phone pushed a moment ago: taking it refreshes rev and clears dirty.
        assertTrue(Lww.shouldApply(localUpdatedAt = 1_000, remoteUpdatedAt = 1_000))
    }

    @Test
    fun oneMillisecondIsEnoughToDecide() {
        assertFalse(Lww.shouldApply(1_795_000_000_001, 1_795_000_000_000))
        assertTrue(Lww.shouldApply(1_795_000_000_000, 1_795_000_000_001))
    }
}
