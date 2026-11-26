package com.chimu.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chimu.ui.theme.chimu

/**
 * The soft lavender wash at the top of Home and Transactions (v4 design). It stays put while the page scrolls
 * and fades to the page colour, so no edge shows.
 */
@Composable
fun TopWash(modifier: Modifier = Modifier) {
    val c = MaterialTheme.chimu.topWash
    Box(
        modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(
                Brush.verticalGradient(
                    0f to c[0],
                    0.38f to c[1],
                    0.68f to c[2],
                    1f to c[3],
                ),
            ),
    )
}

/** True when Android's "remove animations" setting is on. Motion is skipped then. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
