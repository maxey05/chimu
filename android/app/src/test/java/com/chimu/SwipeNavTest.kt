package com.chimu

import com.chimu.ui.NavTab
import com.chimu.ui.SwipeNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The swipe-between-tabs rules (v5 design). */
class SwipeNavTest {
    @Test
    fun neighbours_follow_the_bar_order_and_stop_at_both_ends() {
        assertEquals(NavTab.TRANSACTIONS, SwipeNav.neighbour(NavTab.HOME, 1))
        assertEquals(NavTab.HOME, SwipeNav.neighbour(NavTab.TRANSACTIONS, -1))
        assertEquals(NavTab.PROFILE, SwipeNav.neighbour(NavTab.GROUP, 1))
        assertNull(SwipeNav.neighbour(NavTab.HOME, -1))
        assertNull(SwipeNav.neighbour(NavTab.PROFILE, 1))
    }

    @Test
    fun a_long_drag_left_goes_to_the_next_tab() {
        assertEquals(NavTab.PLAN, SwipeNav.target(NavTab.TRANSACTIONS, -80f, 0f))
    }

    @Test
    fun a_long_drag_right_goes_to_the_previous_tab() {
        assertEquals(NavTab.TRANSACTIONS, SwipeNav.target(NavTab.PLAN, 80f, 0f))
    }

    @Test
    fun a_short_slow_drag_stays_put() {
        assertNull(SwipeNav.target(NavTab.PLAN, -40f, -100f))
        assertNull(SwipeNav.target(NavTab.PLAN, 71.9f, 0f))
    }

    @Test
    fun the_distance_threshold_is_inclusive() {
        assertEquals(NavTab.DASHBOARD, SwipeNav.target(NavTab.PLAN, -SwipeNav.COMMIT_DP, 0f))
    }

    @Test
    fun a_quick_flick_switches_even_when_short() {
        assertEquals(NavTab.DASHBOARD, SwipeNav.target(NavTab.PLAN, -30f, -900f))
        assertEquals(NavTab.TRANSACTIONS, SwipeNav.target(NavTab.PLAN, 30f, 900f))
    }

    @Test
    fun a_twitch_does_not_count_as_a_flick() {
        assertNull(SwipeNav.target(NavTab.PLAN, -10f, -2000f))
    }

    @Test
    fun a_flick_back_the_other_way_does_not_switch() {
        // Dragged left 30 dp, then flicked right on release: the finger is going back, so stay.
        assertNull(SwipeNav.target(NavTab.PLAN, -30f, 900f))
    }

    @Test
    fun no_drag_stays_put() {
        assertNull(SwipeNav.target(NavTab.PLAN, 0f, 5000f))
    }

    @Test
    fun there_is_nowhere_to_go_past_either_end() {
        assertNull(SwipeNav.target(NavTab.HOME, 200f, 0f))
        assertNull(SwipeNav.target(NavTab.PROFILE, -200f, 0f))
    }

    @Test
    fun the_screen_follows_the_finger_and_stretches_less_at_the_ends() {
        assertEquals(-90f, SwipeNav.shown(NavTab.PLAN, -100f), 0.001f)
        assertEquals(90f, SwipeNav.shown(NavTab.PLAN, 100f), 0.001f)
        assertEquals(28f, SwipeNav.shown(NavTab.HOME, 100f), 0.001f)
        assertEquals(-28f, SwipeNav.shown(NavTab.PROFILE, -100f), 0.001f)
        // Home can still be dragged left (towards Transactions) at full follow.
        assertEquals(-90f, SwipeNav.shown(NavTab.HOME, -100f), 0.001f)
        assertEquals(0f, SwipeNav.shown(NavTab.HOME, 0f), 0.001f)
    }
}
