package com.chimu.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.chimu.ui.theme.Baloo2
import com.chimu.ui.theme.Periwinkle
import kotlinx.coroutines.flow.first

/**
 * Has the loading screen handed over to the app yet? Home waits for this before it plays its own entrance, so the
 * header and cards rise as the splash fades away instead of finishing out of sight behind it.
 * It stays true for the rest of the app run: the loading screen only shows when the app starts.
 */
object LaunchGate {
    var handedOver by mutableStateOf(false)
        private set

    fun handOver() {
        handedOver = true
    }
}

/**
 * The loading screen shown over the app on start (v6 design): the purple tile with Chimu peeking up into it, the
 * chimu wordmark landing letter by letter, and the two dots dropping onto the ı.
 *
 * The app is already being built underneath. When [ready] is true (the person's data is open, or the sign-in screen
 * knows it is needed) and the animation has had its [SplashTimeline.MIN_VISIBLE_MS], the screen fades and shrinks a
 * little away while Home rises into place, with no hard cut in between.
 *
 * With Android's "remove animations" on, a still, finished frame is shown for the same time, then it fades.
 */
@Composable
fun LaunchSplash(ready: Boolean) {
    // Handed over before this call (the screen was rebuilt later in the same app run)? Then there is nothing to show.
    // Decided once: handOver() flips the flag when the fade begins, and the fade itself must still be drawn.
    val alreadyDone = remember { LaunchGate.handedOver }
    if (alreadyDone) return

    val reduce = rememberReduceMotion()
    var elapsed by remember { mutableLongStateOf(0L) }
    val readyNow by rememberUpdatedState(ready) // the effect below outlives this call, so it reads the latest value
    val exit = remember { Animatable(0f) }
    var gone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { SplashTimeline.shouldHandOver(readyNow, elapsed) }.first { it }
        LaunchGate.handOver()
        exit.animateTo(1f, tween(if (reduce) 150 else SplashTimeline.EXIT_MS, easing = FastOutLinearInEasing))
        gone = true
    }
    if (gone) return

    // Time as the drawing functions read it. They call this inside draw and graphicsLayer blocks, so a tick redraws without recomposing.
    val time = { if (reduce) SplashTimeline.SETTLED_MS else elapsed.toFloat() }

    // Two boxes: the outer one is the white page and only fades; the inner one holds the logo and also shrinks a touch.
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(Color.White)
            .pointerInput(Unit) { detectTapGestures { } } // touches stop here until the app is showing
            .semantics { contentDescription = "chimu is loading" },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.06f * exit.value
                    scaleX = s
                    scaleY = s
                },
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(bottom = 64.dp).clearAndSetSemantics { },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                val tileShape = RoundedCornerShape(34.dp)
                Box(
                    Modifier
                        .graphicsLayer {
                            alpha = SplashTimeline.tileAlpha(time())
                            val s = SplashTimeline.tileScale(time())
                            scaleX = s
                            scaleY = s
                        }
                        .shadow(18.dp, tileShape, ambientColor = Periwinkle.copy(alpha = 0.22f), spotColor = Periwinkle.copy(alpha = 0.22f))
                        .clip(tileShape)
                        .background(Periwinkle)
                        .size(132.dp),
                ) {
                    ChimuMascot(
                        Modifier
                            .size(132.dp)
                            .graphicsLayer {
                                translationY = SplashTimeline.riseFraction(time()) * size.height
                                val b = SplashTimeline.breathe(time())
                                scaleX = b
                                scaleY = b
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            },
                        curlDegrees = { SplashTimeline.curlDegrees(time()) },
                        eyeScaleY = { SplashTimeline.eyeScaleY(time()) },
                    )
                }
                ChimuWordmark(time)
            }

            Text(
                "Chimu is getting things ready…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 32.dp, end = 32.dp, bottom = 120.dp)
                    .graphicsLayer {
                        alpha = SplashTimeline.captionAlpha(time())
                        translationY = SplashTimeline.captionOffsetDp(time()).dp.toPx()
                    }
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// The wordmark's measurements, in units of the font size, from the approved draft (Baloo 2 ExtraBold).
private const val BaselineEm = 0.777f   // baseline's depth inside a line box that is exactly one em tall
private const val DotCentreEm = 0.924f  // the dots' centres sit this far above the baseline
private const val DotRadiusEm = 0.0776f // a 9 px dot at 58 px
private const val DotOffsetEm = 0.112f  // each dot's centre is this far left or right of the ı's centre (6.5 px at 58 px)
private const val SpaceAboveEm = 0.276f // 16 px of room above the line for the dots and their bounce

/**
 * "chimu" with a dotless ı. The letters come from the font; the two dots are drawn separately so they can drop in and bob.
 * Drawn in one Canvas so the letters and dots share one clock and never recompose.
 */
@Composable
private fun ChimuWordmark(time: () -> Float, fontSize: TextUnit = 58.sp) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Baloo2, fontWeight = FontWeight.ExtraBold, fontSize = fontSize, letterSpacing = (-0.02).em, color = Periwinkle)
    val letters = remember(style) { listOf("c", "h", "ı", "m", "u").map { measurer.measure(it, style) } }
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.toPx() }
    val widthPx = letters.sumOf { it.size.width }
    val heightPx = (fontPx * (1f + SpaceAboveEm))

    Canvas(Modifier.size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })) {
        val baselineY = fontPx * (SpaceAboveEm + BaselineEm)
        var x = 0f
        letters.forEachIndexed { i, layout ->
            val t = time()
            drawLetter(layout, x, baselineY, SplashTimeline.letterAlpha(i, t), SplashTimeline.letterOffsetDp(i, t).dp.toPx())
            if (i == 2) {
                val centreX = x + layout.size.width / 2f
                for (d in 0..1) {
                    val dy = SplashTimeline.dotDropDp(d, t).dp.toPx() + SplashTimeline.dotBobDp(d, t).dp.toPx()
                    drawCircle(
                        Periwinkle.copy(alpha = SplashTimeline.dotAlpha(d, t)),
                        radius = fontPx * DotRadiusEm,
                        center = Offset(centreX + (if (d == 0) -1 else 1) * fontPx * DotOffsetEm, baselineY - fontPx * DotCentreEm + dy),
                    )
                }
            }
            x += layout.size.width
        }
    }
}

private fun DrawScope.drawLetter(layout: TextLayoutResult, x: Float, baselineY: Float, alpha: Float, offsetY: Float) {
    if (alpha <= 0f) return
    drawText(layout, topLeft = Offset(x, baselineY - layout.firstBaseline + offsetY), alpha = alpha)
}
