package com.chimu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chimu.ui.theme.ChimuIcons
import com.chimu.ui.theme.Periwinkle
import com.chimu.ui.theme.chimu

/** Which tab is showing. */
enum class NavTab { HOME, TRANSACTIONS, GROUP, MORE }

/**
 * The floating, icon-only nav bar from the Dusk design: Home, Transactions, [ + ], Group, More.
 * Icons carry no words on screen, so each one has a spoken name (RULES 21). [attention] is how many things
 * wait for the person (Inbox items, a failed sync): it puts a dot on More, written out for screen readers,
 * so an error never hides just because sync moved off Home (RULES 20).
 */
@Composable
fun FloatingNav(
    selected: NavTab?,
    attention: Int,
    onSelect: (NavTab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    val shadowTint = Periwinkle.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
            .height(68.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .shadow(14.dp, shape, ambientColor = shadowTint, spotColor = shadowTint)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavSlot(ChimuIcons.Home, "Home", selected == NavTab.HOME, 0, Modifier.weight(1f)) { onSelect(NavTab.HOME) }
            NavSlot(ChimuIcons.Transactions, "Transactions", selected == NavTab.TRANSACTIONS, 0, Modifier.weight(1f)) { onSelect(NavTab.TRANSACTIONS) }
            Spacer(Modifier.weight(1f)) // the + button sits here
            NavSlot(ChimuIcons.Group, "Group", selected == NavTab.GROUP, 0, Modifier.weight(1f)) { onSelect(NavTab.GROUP) }
            val moreName = if (attention > 0) "More, $attention ${if (attention == 1) "thing needs" else "things need"} your attention" else "More"
            NavSlot(ChimuIcons.More, moreName, selected == NavTab.MORE, attention, Modifier.weight(1f)) { onSelect(NavTab.MORE) }
        }

        // Raised add button with a white ring, centred over the bar.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-34).dp)
                .size(62.dp)
                .shadow(14.dp, CircleShape, ambientColor = shadowTint, spotColor = shadowTint)
                .clip(CircleShape)
                .border(BorderStroke(4.dp, MaterialTheme.colorScheme.background), CircleShape)
                .background(MaterialTheme.colorScheme.onBackground)
                .selectable(selected = false, role = Role.Button, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ChimuIcons.Plus,
                contentDescription = "Add: scan a receipt or log a transaction",
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(26.dp),
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
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(
                    icon,
                    contentDescription = name,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.chimu.outlineStrong,
                    modifier = Modifier.size(26.dp),
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
            Spacer(Modifier.height(4.dp))
            // The active icon gets a small dot under it, so selection is shape and colour, not colour alone.
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
        }
    }
}
