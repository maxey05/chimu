package com.chimu

import com.chimu.ui.SplashTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loading screen's timing (v6 design) and when it hands over to the app. */
class SplashTimelineTest {
    private val tol = 0.001f

    @Test
    fun the_tile_starts_hidden_and_small_and_ends_full_size() {
        assertEquals(0f, SplashTimeline.tileAlpha(0f), tol)
        assertEquals(0.84f, SplashTimeline.tileScale(0f), tol)
        assertEquals(1f, SplashTimeline.tileAlpha(600f), tol)
        assertEquals(1f, SplashTimeline.tileScale(600f), tol)
    }

    @Test
    fun chimu_starts_mostly_below_the_tile_and_ends_at_rest() {
        assertEquals(0.64f, SplashTimeline.riseFraction(0f), tol)
        assertEquals(0.64f, SplashTimeline.riseFraction(140f), tol) // waits for its 140 ms delay
        assertEquals(0f, SplashTimeline.riseFraction(1000f), tol)
    }

    @Test
    fun chimu_overshoots_upwards_before_settling() {
        // 70 % of the 820 ms rise (starting at 140 ms) is the top of the bounce: 4 % of the tile above rest.
        assertEquals(-0.04f, SplashTimeline.riseFraction(140f + 0.7f * 820f), tol)
    }

    @Test
    fun the_curl_wobbles_both_ways_then_rests() {
        assertEquals(0f, SplashTimeline.curlDegrees(600f), tol)
        assertEquals(-16f, SplashTimeline.curlDegrees(700f + 0.3f * 760f), tol)
        assertEquals(9f, SplashTimeline.curlDegrees(700f + 0.62f * 760f), tol)
        assertEquals(0f, SplashTimeline.curlDegrees(1600f), tol)
    }

    @Test
    fun the_letters_arrive_in_order_60_ms_apart() {
        // At 600 ms the first letter is further along than the last.
        assertTrue(SplashTimeline.letterAlpha(0, 600f) > SplashTimeline.letterAlpha(4, 600f))
        assertEquals(0f, SplashTimeline.letterAlpha(4, 480f + 4 * 60f), tol)
        assertEquals(1f, SplashTimeline.letterAlpha(4, 480f + 4 * 60f + 640f), tol)
        assertEquals(0f, SplashTimeline.letterOffsetDp(0, 480f + 640f), tol)
    }

    @Test
    fun the_dots_drop_in_one_after_the_other_and_rest_on_the_line() {
        assertEquals(0f, SplashTimeline.dotAlpha(0, 859f), tol)
        assertEquals(-26f, SplashTimeline.dotDropDp(0, 860f), tol)
        assertTrue(SplashTimeline.dotAlpha(0, 1000f) > SplashTimeline.dotAlpha(1, 1000f)) // the left dot leads by 70 ms
        assertEquals(0f, SplashTimeline.dotDropDp(0, 860f + 560f), tol)
        assertEquals(0f, SplashTimeline.dotDropDp(1, 930f + 560f), tol)
    }

    @Test
    fun the_dots_bob_up_in_turn_after_they_land() {
        assertEquals(0f, SplashTimeline.dotBobDp(0, 1600f), tol)
        assertEquals(-6f, SplashTimeline.dotBobDp(0, 1700f + 0.3f * 1200f), tol)
        assertTrue(SplashTimeline.dotBobDp(1, 1700f + 0.3f * 1200f) > -6f) // the right dot has not reached its peak yet
        assertEquals(-6f, SplashTimeline.dotBobDp(1, 1900f + 0.3f * 1200f), tol)
    }

    @Test
    fun the_eyes_are_open_most_of_the_time_and_blink_every_3_6_seconds() {
        assertEquals(1f, SplashTimeline.eyeScaleY(0f), tol)
        // The blink's lowest point is 91 % through a 3.6 s round that began 2.076 s before launch.
        val low = 0.91f * 3600f - 2076f
        assertEquals(0.12f, SplashTimeline.eyeScaleY(low), tol)
        assertEquals(0.12f, SplashTimeline.eyeScaleY(low + 3600f), tol)
        assertEquals(1f, SplashTimeline.eyeScaleY(low + 1800f), tol)
    }

    @Test
    fun the_breathing_swells_two_percent_at_most() {
        assertEquals(1f, SplashTimeline.breathe(1000f), tol)
        assertEquals(1.022f, SplashTimeline.breathe(1500f + 1400f), tol)
    }

    @Test
    fun the_caption_only_shows_up_when_the_wait_is_long() {
        assertEquals(0f, SplashTimeline.captionAlpha(SplashTimeline.MIN_VISIBLE_MS.toFloat()), tol)
        assertEquals(1f, SplashTimeline.captionAlpha(2400f), tol)
    }

    @Test
    fun it_never_hands_over_before_the_animation_has_had_its_time() {
        assertFalse(SplashTimeline.shouldHandOver(ready = true, elapsedMs = SplashTimeline.MIN_VISIBLE_MS - 1))
        assertTrue(SplashTimeline.shouldHandOver(ready = true, elapsedMs = SplashTimeline.MIN_VISIBLE_MS))
    }

    @Test
    fun it_waits_for_the_app_but_not_forever() {
        assertFalse(SplashTimeline.shouldHandOver(ready = false, elapsedMs = SplashTimeline.MAX_WAIT_MS - 1))
        assertTrue(SplashTimeline.shouldHandOver(ready = false, elapsedMs = SplashTimeline.MAX_WAIT_MS))
    }

    @Test
    fun a_still_frame_shows_the_finished_logo() {
        val t = SplashTimeline.SETTLED_MS
        assertEquals(1f, SplashTimeline.tileScale(t), tol)
        assertEquals(1f, (0..4).minOf { SplashTimeline.letterAlpha(it, t) }, tol)
        assertEquals(1f, SplashTimeline.dotAlpha(1, t), tol)
        assertEquals(0f, SplashTimeline.dotDropDp(1, t), tol)
    }
}
