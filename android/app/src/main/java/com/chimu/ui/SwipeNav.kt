package com.chimu.ui

import kotlin.math.abs

/**
 * The rules for swiping between the four tabs (v6 design), kept as plain numbers so they can be unit tested.
 * Distances are in dp; a drag to the left is negative and goes to the next tab, a drag to the right goes back.
 */
object SwipeNav {
    /** A drag this long switches tabs. */
    const val COMMIT_DP = 72f

    /** A quick flick switches tabs even when it is shorter, as long as it travelled at least [MIN_FLICK_DP]. */
    const val FLICK_DP_PER_SECOND = 600f
    const val MIN_FLICK_DP = 16f

    /** The screen follows the finger by this share of the drag. */
    const val FOLLOW = 0.9f

    /** Where there is no tab to go to (left of Home, right of Group) the screen only stretches by this share. */
    const val RUBBER_BAND = 0.28f

    /** The tab [step] places along (1 = next, -1 = previous), or null at either end. */
    fun neighbour(tab: NavTab, step: Int): NavTab? = NavTab.entries.getOrNull(tab.ordinal + step)

    /**
     * The tab a finished drag leads to, or null to stay put. [dragDp] is the whole drag; [velocityDpPerSecond] is the
     * finger's speed when it lifted. A flick only counts when it points the same way as the drag.
     */
    fun target(tab: NavTab, dragDp: Float, velocityDpPerSecond: Float): NavTab? {
        if (dragDp == 0f) return null
        val step = if (dragDp < 0f) 1 else -1
        val far = abs(dragDp) >= COMMIT_DP
        val sameWay = (velocityDpPerSecond < 0f) == (dragDp < 0f)
        val flick = abs(dragDp) >= MIN_FLICK_DP && abs(velocityDpPerSecond) >= FLICK_DP_PER_SECOND && sameWay
        return if (far || flick) neighbour(tab, step) else null
    }

    /** How far (dp) to draw the screen for a drag of [dragDp]. */
    fun shown(tab: NavTab, dragDp: Float): Float {
        if (dragDp == 0f) return 0f
        val step = if (dragDp < 0f) 1 else -1
        return dragDp * if (neighbour(tab, step) == null) RUBBER_BAND else FOLLOW
    }
}
