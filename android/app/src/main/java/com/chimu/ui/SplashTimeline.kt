package com.chimu.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * The loading screen's motion as plain functions of time, so a test can check any moment (v6 design).
 *
 * Every function takes [t], the milliseconds since the app opened, and returns where one thing is at that moment.
 * The shapes copy CSS keyframes: a list of (progress, value) stops, with an easing curve shaping each stretch
 * between two stops. Times and values come from the approved draft.
 */
object SplashTimeline {
    /** The wordmark has landed and the dots have settled by here; a still frame (reduced motion) uses this moment. */
    const val SETTLED_MS = 1700f

    /** The screen stays at least this long, so the animation is never cut off half way. */
    const val MIN_VISIBLE_MS = 1700L

    /** If the app is still not ready after this long, hand over anyway so the person is never stuck on the logo. */
    const val MAX_WAIT_MS = 8000L

    /** How long the fade into Home takes. */
    const val EXIT_MS = 480

    private val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    private val Expo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    private val Spring = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)

    /** Time to leave the splash: ready and shown long enough, or waited too long. */
    fun shouldHandOver(ready: Boolean, elapsedMs: Long): Boolean =
        elapsedMs >= MAX_WAIT_MS || (ready && elapsedMs >= MIN_VISIBLE_MS)

    /** How far through an animation that starts at [start] and lasts [duration] we are, 0 to 1. */
    fun progress(t: Float, start: Float, duration: Float): Float = ((t - start) / duration).coerceIn(0f, 1f)

    /** How far through the current round of a looping animation we are, 0 to 1. Before [start] it rests at 0. */
    fun loop(t: Float, start: Float, duration: Float): Float {
        if (t < start) return 0f
        val into = (t - start) % duration
        return into / duration
    }

    /** CSS-style keyframes: the value at [p] (0 to 1) between [stops], each stretch shaped by [easing]. */
    fun keyframes(p: Float, easing: Easing, vararg stops: Pair<Float, Float>): Float {
        if (p <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (p1, v1) = stops[i]
            if (p <= p1) {
                val (p0, v0) = stops[i - 1]
                val local = easing.transform((p - p0) / (p1 - p0))
                return v0 + (v1 - v0) * local
            }
        }
        return stops.last().second
    }

    // The purple tile: fades in while growing a little past full size, then settles.
    fun tileAlpha(t: Float) = keyframes(progress(t, 0f, 520f), Expo, 0f to 0f, 0.6f to 1f, 1f to 1f)
    fun tileScale(t: Float) = keyframes(progress(t, 0f, 520f), Expo, 0f to 0.84f, 0.6f to 1.03f, 1f to 1f)

    /** Chimu rising into the tile, as a share of the tile's height pushed down (0.64 = mostly hidden, 0 = home). */
    fun riseFraction(t: Float) = keyframes(progress(t, 140f, 820f), Spring, 0f to 0.64f, 0.7f to -0.04f, 1f to 0f)

    /** The curl on Chimu's head wobbling, in degrees. */
    fun curlDegrees(t: Float) = keyframes(progress(t, 700f, 760f), EaseInOut, 0f to 0f, 0.3f to -16f, 0.62f to 9f, 1f to 0f)

    /** Eye height: 1 open, 0.12 at the middle of a blink. The phase starts part way round so a blink is not far off. */
    fun eyeScaleY(t: Float) = keyframes(loop(t, -2076f, 3600f), Ease, 0f to 1f, 0.86f to 1f, 0.91f to 0.12f, 0.96f to 1f, 1f to 1f)

    /** Chimu breathing: a 2.2 % swell, starting once the letters are in. */
    fun breathe(t: Float) = keyframes(loop(t, 1500f, 2800f), EaseInOut, 0f to 1f, 0.5f to 1.022f, 1f to 1f)

    /** Letter [i] of c-h-ı-m-u rising into place, 60 ms after the one before. */
    fun letterAlpha(i: Int, t: Float) = progress(t, 480f + i * 60f, 640f).let { keyframes(it, Expo, 0f to 0f, 1f to 1f) }
    fun letterOffsetDp(i: Int, t: Float) = progress(t, 480f + i * 60f, 640f).let { keyframes(it, Expo, 0f to 16f, 1f to 0f) }

    /** The two dots of the ı ([i] 0 = left, 1 = right) dropping in from above with a small bounce. */
    fun dotAlpha(i: Int, t: Float) = keyframes(progress(t, 860f + i * 70f, 560f), EaseOut, 0f to 0f, 0.45f to 1f, 1f to 1f)
    fun dotDropDp(i: Int, t: Float) =
        keyframes(progress(t, 860f + i * 70f, 560f), EaseOut, 0f to -26f, 0.45f to 0f, 0.64f to -7f, 0.82f to 0f, 0.92f to -1.5f, 1f to 0f)

    /** Then they bob in turn, left one first, up 6 dp and back. */
    fun dotBobDp(i: Int, t: Float) = keyframes(loop(t, 1700f + i * 200f, 1200f), EaseInOut, 0f to 0f, 0.3f to -6f, 0.6f to 0f, 1f to 0f)

    /** The "getting things ready" line fading up, 1.8 s in. */
    fun captionAlpha(t: Float) = keyframes(progress(t, 1800f, 500f), EaseOut, 0f to 0f, 1f to 1f)
    fun captionOffsetDp(t: Float) = keyframes(progress(t, 1800f, 500f), EaseOut, 0f to 6f, 1f to 0f)
}
