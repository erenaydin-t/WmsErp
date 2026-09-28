package com.wmserp.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wmserp.app.presentation.theme.WmsTheme

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/** Custom bottom bar: four destinations with a raised, gradient scan button in the middle. */
@Composable
fun WmsBottomBar(
    items: List<BottomNavItem>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    scanSelected: Boolean = false,
) {
    require(items.size == 4) { "WmsBottomBar expects exactly four items" }
    val extended = WmsTheme.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), clip = false),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.take(2).forEach { item ->
                BottomBarItem(item, selected = currentRoute == item.route, onClick = { onNavigate(item.route) }, modifier = Modifier.weight(1f))
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .shadow(if (scanSelected) 2.dp else 8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(extended.gradientStart, extended.gradientEnd)))
                        .clickable(interactionSource = interaction, indication = null, onClick = onScan)
                        .semantics { contentDescription = "Scan" }
                        .testTagCompat("nav_scan"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                }
            }
            items.drop(2).forEach { item ->
                BottomBarItem(item, selected = currentRoute == item.route, onClick = { onNavigate(item.route) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BottomBarItem(item: BottomNavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTagCompat("nav_${item.route}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 28.dp)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (selected) item.selectedIcon else item.icon, contentDescription = item.label, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
