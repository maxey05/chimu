package com.chimu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform

// The mascot's colours (DESIGN.md -> Mascot, "Peek" pose). They belong to the artwork, not to the app theme.
private val Navy = Color(0xFF10182F)
private val WingLight = Color(0xFFD6C8EA)
private val Cheek = Color(0xFFEBC6E0)

private val BodyBrushStops = arrayOf(
    0f to Color(0xFFFFFFFF), 0.55f to Color(0xFFFEFDFF), 0.84f to Color(0xFFF1EBF8), 1f to Color(0xFFDCCFEC),
)

private val CurlPath = Path().apply {
    moveTo(98f, 60f)
    cubicTo(92f, 46f, 100f, 34f, 114f, 33f)
    cubicTo(109f, 41f, 107f, 50f, 110f, 60f)
    close()
}

private val BeakPath = Path().apply {
    moveTo(95.5f, 139f)
    quadraticTo(103f, 135.5f, 110.5f, 139f)
    quadraticTo(106f, 150f, 103f, 150f)
    quadraticTo(100f, 150f, 95.5f, 139f)
    close()
}

private val BodyPath = Path().apply { addOval(Rect(12f, 54f, 196f, 246f)) }

private val LeftRim = Path().apply {
    moveTo(24f, 136f)
    cubicTo(30f, 100f, 52f, 78f, 78f, 68f)
}

private val RightRim = Path().apply {
    moveTo(186f, 136f)
    cubicTo(180f, 100f, 158f, 78f, 132f, 68f)
}

/**
 * Chimu, the soft round mascot, drawn from the design's 200 x 200 artwork (the "Peek" pose: a round body, a curl,
 * two wing nubs, eyes and a small beak). It fills the square it is given and draws past the edges when pushed, so a
 * parent that clips (the loading screen's tile) is what cuts the round body off.
 *
 * [curlDegrees] tilts the curl and [eyeScaleY] squashes the eyes for a blink. They are functions, not values, so
 * the animation only redraws this picture and never recomposes it.
 */
@Composable
fun ChimuMascot(
    modifier: Modifier = Modifier,
    curlDegrees: () -> Float = { 0f },
    eyeScaleY: () -> Float = { 1f },
) {
    Canvas(modifier) {
        val k = size.minDimension / 200f
        scale(k, k, pivot = Offset.Zero) {
            val wing = { cx: Float, cy: Float, r: Float ->
                Brush.radialGradient(
                    0f to Color(0xFFFFFFFF), 0.65f to Color(0xFFF2ECF8), 1f to WingLight,
                    center = Offset(cx, cy), radius = r,
                )
            }

            // A soft ring of shadow under the whole shape.
            softOval(104f, 154f, 104f, 108f, Navy, 0.30f, solid = 0.85f)

            // The curl, drawn first so the body overlaps its base.
            rotate(curlDegrees(), pivot = Offset(103f, 60f)) {
                drawPath(CurlPath, wing(104.5f, 39.8f, 20f))
            }

            // The body: a ball lit from the top left.
            drawOval(
                brush = Brush.radialGradient(*BodyBrushStops, center = Offset(82f, 108f), radius = 144f),
                topLeft = Offset(12f, 54f),
                size = Size(184f, 192f),
            )

            // Shading and the wing nubs stay inside the body.
            clipPath(BodyPath) {
                for ((w, a) in listOf(30f to 0.10f, 22f to 0.14f, 14f to 0.18f)) {
                    drawPath(LeftRim, WingLight, alpha = a, style = Stroke(w, cap = StrokeCap.Round))
                    drawPath(RightRim, WingLight, alpha = a, style = Stroke(w, cap = StrokeCap.Round))
                }
                softOval(190f, 150f, 22f, 66f, Color(0xFFE2D3F2), 0.7f, solid = 0.4f)
                softOval(72f, 88f, 48f, 26f, Color(0xFFFFFFFF), 0.95f, solid = 0.45f)
                rotate(-25f, pivot = Offset(30f, 202f)) {
                    drawOval(wing(30f, 186f, 41f), Offset(6f, 170f), Size(48f, 64f))
                }
                rotate(25f, pivot = Offset(178f, 202f)) {
                    drawOval(wing(178f, 186f, 41f), Offset(154f, 170f), Size(48f, 64f))
                }
            }

            // Cheeks.
            softOval(62f, 147f, 12f, 7.5f, Cheek, 0.75f, solid = 0.4f)
            softOval(142f, 147f, 12f, 7.5f, Cheek, 0.75f, solid = 0.4f)

            // Eyes, squashed together towards their middle line for a blink.
            scale(1f, eyeScaleY(), pivot = Offset(103f, 130f)) {
                for (cx in listOf(79f, 127f)) {
                    drawOval(
                        Brush.radialGradient(0f to Color(0xFF3B4672), 1f to Navy, center = Offset(cx - 8.6f + 6.0f, 130f - 9.8f + 5.9f), radius = 12f),
                        Offset(cx - 8.6f, 130f - 9.8f),
                        Size(17.2f, 19.6f),
                    )
                    drawCircle(Color.White, radius = 2.9f, center = Offset(cx + 2.8f, 126.4f))
                }
            }

            // Beak.
            drawPath(BeakPath, Color(0xFF2A3358))
        }
    }
}

/**
 * A soft-edged oval (the design blurs these). It is a round gradient squeezed into [rx] by [ry]: full [alpha] out to
 * [solid] of the radius, then fading to nothing at the edge.
 */
private fun DrawScope.softOval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, alpha: Float, solid: Float = 0.5f) {
    withTransform({
        translate(cx, cy)
        scale(rx, ry, pivot = Offset.Zero)
    }) {
        drawCircle(
            Brush.radialGradient(0f to color.copy(alpha), solid to color.copy(alpha), 1f to color.copy(0f), center = Offset.Zero, radius = 1f),
            radius = 1f,
            center = Offset.Zero,
        )
    }
}
