package com.chimu.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the Dusk design needs, drawn as stroked paths (the same shapes as the design draft).
 * The app only ships `material-icons-core`, and RULES 3 asks for a reason before adding a library,
 * so these are drawn here instead. Tint them with `Icon(tint = ...)`.
 */
object ChimuIcons {
    private fun stroked(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    // Nav icons (v4 design): one 1.8 stroke so every slot looks the same weight.
    val Home: ImageVector by lazy { stroked("home", 1.8f, "M4 10.4L12 4l8 6.4V19a1.5 1.5 0 0 1-1.5 1.5H15V16a3 3 0 0 0-6 0v4.5H5.5A1.5 1.5 0 0 1 4 19z") }
    val Transactions: ImageVector by lazy {
        stroked("transactions", 1.8f, "M4 4h16", "M6.5 7v13l2.2-1.4 2.2 1.4 2.2-1.4 2.2 1.4 2.2-1.4V7", "M10 11h4.5M10 14.5h4.5")
    }
    val Plan: ImageVector by lazy {
        stroked(
            "plan", 1.8f,
            "M7.5 5h9A3.5 3.5 0 0 1 20 8.5V17a3.5 3.5 0 0 1-3.5 3.5h-9A3.5 3.5 0 0 1 4 17V8.5A3.5 3.5 0 0 1 7.5 5z",
            "M8 3v4M16 3v4M4 10h16", "M9 15l2 2 4-4",
        )
    }
    /** Bar chart: a baseline with four bars (the Dashboard tab). */
    val Dashboard: ImageVector by lazy { stroked("dashboard", 1.8f, "M3.5 20.5h17", "M6.5 17.5v-5M10.5 17.5V8M14.5 17.5v-3.5M18.5 17.5V4.5") }
    /** Three people, the middle one in front (the Group tab). */
    val Group: ImageVector by lazy {
        stroked(
            "group", 1.8f,
            "M15.2 9.2a3.2 3.2 0 1 1-6.4 0 3.2 3.2 0 0 1 6.4 0",
            "M6.9 17.4v-1.3a3.6 3.6 0 0 1 3.6-3.6h3a3.6 3.6 0 0 1 3.6 3.6v1.3a1.2 1.2 0 0 1-1.2 1.2H8.1a1.2 1.2 0 0 1-1.2-1.2z",
            "M7.3 6.4C5.9 6.9 5 8.2 5 9.7c0 1.1.6 2 2.2 2.4M7.2 12.1C5 12.6 3.6 14.3 3.6 16.1v.7l1.5.3",
            "M16.7 6.4C18.1 6.9 19 8.2 19 9.7c0 1.1-.6 2-2.2 2.4M16.8 12.1c2.2.5 3.6 2.2 3.6 4v.7l-1.5.3",
        )
    }
    val Profile: ImageVector by lazy { stroked("profile", 1.8f, "M15.5 8.5a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0", "M5 20a7 7 0 0 1 14 0") }
    val More: ImageVector by lazy { stroked("more", 3f, "M5 12h.01M12 12h.01M19 12h.01") }
    val Plus: ImageVector by lazy { stroked("plus", 2.4f, "M12 5v14M5 12h14") }
    val ArrowUpRight: ImageVector by lazy { stroked("arrow_up_right", 2.2f, "M7 17L17 7M9 7h8v8") }
    val ChevronUp: ImageVector by lazy { stroked("chevron_up", 2.8f, "M6 15l6-6 6 6") }
    val ChevronDown: ImageVector by lazy { stroked("chevron_down", 2.8f, "M6 9l6 6 6-6") }
    val Check: ImageVector by lazy { stroked("check", 2.6f, "M5 12.5l4.5 4.5L19 7.5") }
    val Camera: ImageVector by lazy {
        stroked("camera", 2f, "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M15.5 13a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0")
    }
    val Pencil: ImageVector by lazy { stroked("pencil", 2f, "M4 20l4-1 11-11-3-3L5 16l-1 4z") }

    // Account menu, chart switch, transaction rows.
    val Inbox: ImageVector by lazy { stroked("inbox", 1.8f, "M3.5 13.5l2.6-7.1A2 2 0 0 1 8 5h8a2 2 0 0 1 1.9 1.4l2.6 7.1V18a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z", "M3.5 13.5H8l1.5 2.5h5l1.5-2.5h4.5") }
    val Wallet: ImageVector by lazy { stroked("wallet", 1.8f, "M4 7.5A2.5 2.5 0 0 1 6.5 5H18a1 1 0 0 1 1 1v2", "M4 7.5V17a2 2 0 0 0 2 2h13a1 1 0 0 0 1-1v-9a1 1 0 0 0-1-1H6.5A2.5 2.5 0 0 1 4 7.5z", "M16 13.5h.01") }
    val Sync: ImageVector by lazy { stroked("sync", 1.8f, "M20 11a8 8 0 0 0-14.5-4.5M4 4v4h4", "M4 13a8 8 0 0 0 14.5 4.5M20 20v-4h-4") }
    val SignOut: ImageVector by lazy { stroked("sign_out", 1.8f, "M14 4H6.5A1.5 1.5 0 0 0 5 5.5v13A1.5 1.5 0 0 0 6.5 20H14", "M10 12h10M17 8.5l3.5 3.5-3.5 3.5") }
    val LineChart: ImageVector by lazy { stroked("line_chart", 2f, "M3 17c3-1 4-8 7-8s3.5 6 6.5 6S20 8 21 6") }
    val BarChart: ImageVector by lazy { stroked("bar_chart", 2f, "M5 20v-7M10 20V8M15 20v-5M20 20V5") }
    val Receipt: ImageVector by lazy { stroked("receipt", 2.2f, "M6 3h12v18l-3-2-3 2-3-2-3 2z", "M9 8h6M9 12h6") }
    val Transfer: ImageVector by lazy { stroked("transfer", 2.4f, "M4 8h15l-4-4M20 16H5l4 4") }
    val Repeat: ImageVector by lazy { stroked("repeat", 2.2f, "M17 2l4 4-4 4", "M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4", "M21 13v2a3 3 0 0 1-3 3H3") }
    val Search: ImageVector by lazy { stroked("search", 2.2f, "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0", "M20 20l-3.5-3.5") }

    // Hide / show the Home balance (v7): an open eye, and the same eye struck through.
    private const val EYE = "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"
    private const val PUPIL = "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0"
    val Eye: ImageVector by lazy { stroked("eye", 2f, EYE, PUPIL) }
    val EyeOff: ImageVector by lazy { stroked("eye_off", 2f, EYE, PUPIL, "M4 4l16 16") }
}
