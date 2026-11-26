package com.chimu.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chimu.ui.theme.ChimuIcons
import com.chimu.ui.theme.Periwinkle
import com.chimu.ui.theme.chimu
import kotlin.math.abs

private val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val BarHeight = 68.dp
private val SideInset = 6.dp          // space between the bar's ends and its first / last slot
private val NotchHalf = 40.dp         // the dip around + reaches this far either side of the centre
private val AddSize = 52.dp
private val AddRise = 22.dp         // how far + stands above the bar
private val DotSize = 4.dp
private val DotTop = 54.dp          // the dot sits just under the centred icons, inside the bar

/**
 * The bar's outline: a pill with a round dip in the middle for the + button (v4 design). The numbers are the
 * design's, measured from the centre so the dip stays centred on any screen width.
 */
private object NotchedBar : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val w = size.width / d
        val cx = w / 2f
        // M34 .5 H(cx-40) C… A31 31 … C… H(w-34) A33.5 … H34 A33.5 … Z  (all in dp)
        val data = "M34 0.5 H${cx - 40} " +
            "C${cx - 34} 0.5 ${cx - 31.8} 7.4 ${cx - 29} 14.95 " +
            "A31 31 0 0 0 ${cx + 29} 14.95 " +
            "C${cx + 31.8} 7.4 ${cx + 34} 0.5 ${cx + 40} 0.5 " +
            "H${w - 34} A33.5 33.5 0 0 1 ${w - 34} 67.5 " +
            "H34 A33.5 33.5 0 0 1 34 0.5 Z"
        val path = PathParser().parsePathString(data).toPath()
        val scaled = Path().apply { addPath(path) }
        scaled.transform(androidx.compose.ui.graphics.Matrix().apply { scale(d, d) })
        return Outline.Generic(scaled)
    }
}

/**
 * The floating, icon-only nav bar from the Dusk design: Home, Transactions, Plan, [ + ], Dashboard, Group, Profile.
 * Each side's three slots are equal width, the icons sit on the bar's centre line, and a small dot slides to the chosen tab (it stretches while it moves).
 * Icons carry no words on screen, so each one has a spoken name (RULES 21). [attention] is how many things wait for
 * the person (Inbox items, a failed sync): it puts a dot on Profile, written out for screen readers, so an error
 * never hides just because sync moved off Home (RULES 20).
 */
@Composable
fun FloatingNav(
    selected: NavTab?,
    attention: Int,
    onSelect: (NavTab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shadowTint = Periwinkle.copy(alpha = 0.30f)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = SideInset, end = SideInset, bottom = 22.dp)
            .height(BarHeight + AddRise), // taller than the bar so the raised + button stays inside it and gets its taps
    ) {
        val barWidth = maxWidth
        val centre = barWidth / 2
        // Each side is three equal slots between the bar's end and the dip.
        val sideWidth = centre - NotchHalf - SideInset
        val slot = sideWidth / 3
        fun slotCentre(tab: NavTab): androidx.compose.ui.unit.Dp {
            val i = tab.ordinal
            return if (i < 3) SideInset + slot * i + slot / 2 else centre + NotchHalf + slot * (i - 3) + slot / 2
        }

        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(BarHeight)) {
            // The bar itself.
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(14.dp, NotchedBar, ambientColor = shadowTint, spotColor = shadowTint)
                    .clip(NotchedBar)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), NotchedBar),
            )

            // The sliding dot: eases to the chosen slot and is longer while it is on its way.
            if (selected != null) {
                val target = slotCentre(selected)
                val x by animateDpAsState(target, tween(420, easing = EaseOutExpo), label = "navDot")
                val stretch = (abs((target - x).value) / 40f).coerceIn(0f, 1f) * 8f
                val dotWidth = DotSize + stretch.dp
                Box(
                    Modifier
                        .offset(x = x - dotWidth / 2, y = DotTop)
                        .width(dotWidth)
                        .height(DotSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }

            // The six slots.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(SideInset))
                NavSlot(ChimuIcons.Home, "Home", selected == NavTab.HOME, 0, Modifier.width(slot)) { onSelect(NavTab.HOME) }
                NavSlot(ChimuIcons.Transactions, "Transactions", selected == NavTab.TRANSACTIONS, 0, Modifier.width(slot)) { onSelect(NavTab.TRANSACTIONS) }
                NavSlot(ChimuIcons.Plan, "Plan: budgets, goals and recurring", selected == NavTab.PLAN, 0, Modifier.width(slot)) { onSelect(NavTab.PLAN) }
                Box(Modifier.width(NotchHalf * 2)) // the + button sits here
                NavSlot(ChimuIcons.Dashboard, "Dashboard", selected == NavTab.DASHBOARD, 0, Modifier.width(slot)) { onSelect(NavTab.DASHBOARD) }
                NavSlot(ChimuIcons.Group, "Group", selected == NavTab.GROUP, 0, Modifier.width(slot)) { onSelect(NavTab.GROUP) }
                val profileName = if (attention > 0) "Profile, $attention ${if (attention == 1) "thing needs" else "things need"} your attention" else "Profile"
                NavSlot(ChimuIcons.Profile, profileName, selected == NavTab.PROFILE, attention, Modifier.width(slot)) { onSelect(NavTab.PROFILE) }
            }

        }

        // The add button, centred over the dip.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(AddSize)
                .shadow(10.dp, CircleShape, ambientColor = shadowTint, spotColor = shadowTint)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground)
                .selectable(selected = false, role = Role.Button, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ChimuIcons.Plus,
                contentDescription = "Add: scan a receipt or log a transaction",
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun NavSlot(
    icon: ImageVector,
    name: String,
    selected: Boolean,
    badge: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // The slot takes the tap but draws nothing itself. The press glow belongs to a round 44 dp patch around the icon, so
    // it can never spill past the bar's rounded ends the way a full-slot rectangle did on Home and Profile.
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize(44.dp)
                .clip(CircleShape)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            // The icon sits exactly on the bar's centre line; the chosen tab is shown by its colour and the dot.
            Box {
                Icon(
                    icon,
                    contentDescription = name,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.chimu.outlineStrong,
                    modifier = Modifier.size(24.dp),
                )
                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-2).dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.surface), CircleShape),
                    )
                }
            }
        }
    }
}
