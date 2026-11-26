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

    val Home: ImageVector by lazy { stroked("home", 2.2f, "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z") }
    val Transactions: ImageVector by lazy { stroked("transactions", 2f, "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01") }
    val Group: ImageVector by lazy {
        stroked("group", 2f, "M12.5 8a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0", "M2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6 6 0 0 1 3.5 6")
    }
    val More: ImageVector by lazy { stroked("more", 3f, "M5 12h.01M12 12h.01M19 12h.01") }
    val Plus: ImageVector by lazy { stroked("plus", 2.4f, "M12 5v14M5 12h14") }
    val ArrowUpRight: ImageVector by lazy { stroked("arrow_up_right", 2.2f, "M7 17L17 7M9 7h8v8") }
    val ChevronUp: ImageVector by lazy { stroked("chevron_up", 2.8f, "M6 15l6-6 6 6") }
    val ChevronDown: ImageVector by lazy { stroked("chevron_down", 2.8f, "M6 9l6 6 6-6") }
    val Camera: ImageVector by lazy {
        stroked("camera", 2f, "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M15.5 13a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0")
    }
    val Pencil: ImageVector by lazy { stroked("pencil", 2f, "M4 20l4-1 11-11-3-3L5 16l-1 4z") }
}
