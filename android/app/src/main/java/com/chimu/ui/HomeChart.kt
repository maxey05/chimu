package com.chimu.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chimu.Money
import com.chimu.SpendPeriod
import com.chimu.SpendSeries
import com.chimu.ui.theme.chimu
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val CHART_HEIGHT = 190.dp
private val CHART_TOP = 70.dp
private val CHART_BOTTOM = 12.dp

/** Where the points sit and the smooth line through them, in pixels. */
private class ChartShape(val points: List<Offset?>, val path: Path)

/** One point per equal slot, so the axis labels (also one per slot) sit right under their points. */
private fun chartShape(values: List<Long?>, width: Float, height: Float, top: Float, bottom: Float): ChartShape {
    val n = values.size
    val known = values.filterNotNull()
    if (known.isEmpty()) return ChartShape(List(n) { null }, Path())
    val hi = known.max().toDouble()
    val low = known.min().toDouble()
    val span = max(hi - low, hi * 0.25)
    val lo = max(0.0, low - span * 0.35)
    val denom = (hi - lo).takeIf { it > 0 } ?: 1.0
    val points = values.mapIndexed { i, v ->
        if (v == null) null
        else Offset((i + 0.5f) * width / n, top + ((1 - (v - lo) / denom) * (height - top - bottom)).toFloat())
    }
    val pts = points.filterNotNull()
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts.getOrElse(i - 1) { pts[i] }
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts.getOrElse(i + 2) { p2 }
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
            p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6,
            p2.x, p2.y,
        )
    }
    return ChartShape(points, path)
}

/** Reads aloud every period's amount, so the chart never relies on sight (RULES 21). */
private fun chartSummary(series: SpendSeries): String =
    "Expenditures, ${series.caption}, total ${Money.format(series.total)}. " +
        series.points.joinToString(", ") { it.longLabel + " " + (it.spent?.let(Money::format) ?: "no data yet") }

/**
 * The Home Expenditures chart, as a line or as bars ([showBars]). The line is periwinkle over a lavender fade:
 * [progress] (0 to 1) is how much of it is drawn; the dot leads the line while it draws, then rests on the
 * [selected] point. The bars grow up one after another from the baseline; the selected bar is periwinkle and the
 * others lavender. Either way: tap or drag to pick a point.
 */
@Composable
fun SpendChart(
    series: SpendSeries,
    progress: Float,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showBars: Boolean = false,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.chimu
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val n = series.points.size
    val vertical = series.period == SpendPeriod.YEAR
    val labelBand by animateDpAsState(if (vertical) 46.dp else 24.dp, tween(320, easing = EaseOutExpo), label = "labelBand")
    val ready = progress >= 0.999f
    // Bars: each one starts 45 ms after the one before and grows for 360 ms.
    val reduce = rememberReduceMotion()
    val barT = remember { Animatable(0f) }
    val barStep = 45
    val barGrow = 360
    val barTotal = barGrow + barStep * (n - 1)
    LaunchedEffect(showBars, series.period) {
        if (showBars) {
            if (reduce) {
                barT.snapTo(1f)
            } else {
                barT.snapTo(0f)
                barT.animateTo(1f, tween(barTotal, easing = LinearEasing))
            }
        }
    }
    val interactive = if (showBars) barT.value >= 0.999f else ready
    val tipAlpha by animateFloatAsState(if (interactive) 1f else 0f, tween(280), label = "tip")
    var tipSize by remember { mutableStateOf(IntSize.Zero) }

    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .semantics { contentDescription = chartSummary(series) },
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { CHART_HEIGHT.toPx() }
            val shape = remember(series, widthPx) {
                chartShape(series.points.map { it.spent }, widthPx, heightPx, with(density) { CHART_TOP.toPx() }, with(density) { CHART_BOTTOM.toPx() })
            }
            val measure = remember(shape) { PathMeasure().also { it.setPath(shape.path, false) } }
            val length = measure.length
            val known = shape.points.filterNotNull()
            val tip: Offset = when {
                known.isEmpty() -> Offset.Zero
                length <= 0f -> known[0]
                else -> measure.getPosition(length * progress.coerceIn(0f, 1f))
            }
            val target = shape.points.getOrNull(selected) ?: known.lastOrNull() ?: Offset.Zero
            val resting by animateOffsetAsState(target, tween(320, easing = EaseOutExpo), label = "dot")
            val dot = if (ready) resting else tip

            // Bars start at zero, so a bar's height is its share of the tallest one.
            val slotPx = widthPx / n
            val baseY = heightPx - with(density) { CHART_BOTTOM.toPx() }
            val barArea = baseY - with(density) { CHART_TOP.toPx() }
            val maxSpent = series.points.maxOfOrNull { it.spent ?: 0L }?.takeIf { it > 0L } ?: 1L
            fun barHeight(i: Int): Float = ((series.points[i].spent ?: 0L).toFloat() / maxSpent) * barArea
            val anchor = if (showBars) Offset((selected + 0.5f) * slotPx, baseY - barHeight(selected.coerceIn(0, n - 1))) else dot

            // Tap or drag anywhere on the chart to pick the nearest point that has data.
            fun pick(x: Float) {
                if (!interactive) return
                val i = (x / (widthPx / n)).toInt().coerceIn(0, n - 1)
                if (series.points[i].spent != null) onSelect(i)
            }

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT)
                    .pointerInput(series, interactive) { detectTapGestures { pick(it.x) } }
                    .pointerInput(series, interactive) { detectHorizontalDragGestures { change, _ -> pick(change.position.x) } },
            ) {
                if (showBars) {
                    val barW = min(slotPx * 0.5f, 28.dp.toPx())
                    val corner = 6.dp.toPx()
                    series.points.forEachIndexed { i, p ->
                        if (p.spent == null) return@forEachIndexed
                        val t = ((barT.value * barTotal - i * barStep) / barGrow).coerceIn(0f, 1f)
                        val h = barHeight(i) * EaseOutExpo.transform(t)
                        if (h < 1f) return@forEachIndexed
                        val cx = (i + 0.5f) * slotPx
                        val r = CornerRadius(min(corner, h))
                        val bar = Path().apply {
                            addRoundRect(RoundRect(cx - barW / 2, baseY - h, cx + barW / 2, baseY, r, r, CornerRadius.Zero, CornerRadius.Zero))
                        }
                        drawPath(bar, if (i == selected) primary else colors.accent.copy(alpha = 0.45f))
                    }
                } else if (known.isNotEmpty()) {
                    if (length > 0f) {
                        val drawn = Path()
                        measure.getSegment(0f, length * progress.coerceIn(0f, 1f), drawn, true)
                        val fill = Path().apply {
                            addPath(drawn)
                            lineTo(tip.x, size.height)
                            lineTo(known[0].x, size.height)
                            close()
                        }
                        drawPath(
                            fill,
                            Brush.verticalGradient(
                                listOf(colors.accent.copy(alpha = 0.55f), Color.Transparent),
                                startY = CHART_TOP.toPx(),
                                endY = size.height,
                            ),
                        )
                        drawPath(drawn, primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    if (progress > 0f) {
                        if (tipAlpha > 0f) {
                            drawLine(
                                colors.accent.copy(alpha = tipAlpha),
                                Offset(dot.x, dot.y + 8.dp.toPx()),
                                Offset(dot.x, size.height),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                            )
                        }
                        drawCircle(surface, radius = 7.dp.toPx(), center = dot)
                        drawCircle(ink, radius = 4.dp.toPx(), center = dot)
                    }
                }
            }

            // Tooltip above the dot, kept inside the chart's width.
            val point = series.points.getOrNull(selected)
            if (point?.spent != null) {
                val tipX = (anchor.x - tipSize.width / 2f).coerceIn(0f, max(0f, widthPx - tipSize.width))
                val tipY = max(0f, anchor.y - tipSize.height - with(density) { 10.dp.toPx() })
                Column(
                    modifier = Modifier
                        .offset { IntOffset(tipX.roundToInt(), tipY.roundToInt()) }
                        .onSizeChanged { tipSize = it }
                        .graphicsLayer { alpha = tipAlpha }
                        .shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = primary.copy(alpha = 0.3f), spotColor = primary.copy(alpha = 0.3f))
                        .background(surface, RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .clearAndSetSemantics { },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(point.longLabel, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.format(point.spent), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink)
                }
            }
        }

        // Axis labels: one per slot, 14 sp minimum; turned on their side for the 12 months of Year.
        Row(
            Modifier.fillMaxWidth().height(labelBand).clearAndSetSemantics { },
        ) {
            series.points.forEachIndexed { i, p ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        p.label,
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp).then(if (vertical) Modifier.sideways() else Modifier),
                    )
                }
            }
        }
    }
}

/** Turns text a quarter turn (reads bottom to top) and reports its turned size, so neighbours make room for it. */
private fun Modifier.sideways(): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width - placeable.height) / 2,
            y = (placeable.width - placeable.height) / 2,
        ) { rotationZ = -90f }
    }
}
