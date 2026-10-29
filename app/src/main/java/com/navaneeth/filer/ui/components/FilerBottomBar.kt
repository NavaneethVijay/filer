package com.navaneeth.filer.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Search
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.navaneeth.filer.R

/** Space the floating navigation pill takes, excluding the system navigation inset: item, padding, margin. */
val BottomBarHeight = 56.dp + 6.dp * 2 + 12.dp

/**
 * Height of the floating navigation bar plus the system navigation inset (0 when it isn't shown).
 * Lists draw behind the bar: add this to their bottom contentPadding and lift bottom-anchored bars by it.
 */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

enum class BottomTab(
    @StringRes val title: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    BROWSE(R.string.tab_browse, Icons.Rounded.Folder, Icons.Outlined.Folder),
    STORAGE(R.string.tab_storage, Icons.Rounded.DataUsage, Icons.Outlined.DataUsage),
    INSIGHTS(R.string.tab_insights, Icons.Rounded.Insights, Icons.Outlined.Insights)
}

/**
 * Expressive floating navigation, like Google Photos: a pill of tabs above the bottom edge and,
 * beside it, a round Search button of the same height (search opens over any screen, it isn't a
 * tab). The selected tab grows into a tonal pill with its label; the others show just their icon.
 */
@Composable
fun FilerBottomBar(
    currentTab: BottomTab?,
    onTabSelected: (BottomTab) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = FloatingMargin),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp
        ) {
            Row(Modifier.padding(PillPadding), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BottomTab.entries.forEach { tab ->
                    PillItem(tab, selected = tab == currentTab, onClick = { onTabSelected(tab) })
                }
            }
        }
        val searchLabel = stringResource(R.string.tab_search)
        Surface(
            onClick = onSearch,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp,
            modifier = Modifier
                .size(ItemHeight + PillPadding * 2)
                .clearAndSetSemantics {
                    contentDescription = searchLabel
                    role = Role.Button
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Search, contentDescription = null)
            }
        }
    }
}

@Composable
private fun PillItem(tab: BottomTab, selected: Boolean, onClick: () -> Unit) {
    val blue = Accent.BLUE.colors()
    val label = stringResource(tab.title)
    val container by animateColorAsState(if (selected) blue.container else Color.Transparent, label = "tabColor")
    val content by animateColorAsState(if (selected) blue.onContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabInk")
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = label
            role = Role.Tab
            this.selected = selected
        }
    ) {
        Row(
            Modifier
                .height(ItemHeight)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, contentDescription = null)
            if (selected) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private val ItemHeight = 56.dp
private val PillPadding = 6.dp
private val FloatingMargin = 12.dp
