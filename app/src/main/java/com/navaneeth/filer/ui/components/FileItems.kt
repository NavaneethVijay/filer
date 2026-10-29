package com.navaneeth.filer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.rememberHaptics

/**
 * Standard Material 3 one/two/three-line file row. Long-press opens the actions sheet, or in lists
 * with multi-select starts selecting. While selecting, a tap (de)selects the row, the check on the
 * right shows the state, and the preview on the left still opens the file ([onPreview]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    item: FileUiItem,
    supportingText: String,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snippet: AnnotatedString? = null,
    trailingBadge: (@Composable () -> Unit)? = null,
    /** Long-press; defaults to [onMore]. Lists with multi-select pass their "select" action. */
    onLongClick: (() -> Unit)? = onMore,
    /** Null outside selection mode; otherwise whether this row is selected. */
    selected: Boolean? = null,
    /** Opens the file from its preview while selecting; null keeps the preview part of the row. */
    onPreview: (() -> Unit)? = null
) {
    val haptics = rememberHaptics()
    val container by animateColorAsState(
        if (selected == true) Accent.BLUE.colors().container else MaterialTheme.colorScheme.surface,
        label = "rowColor"
    )
    ListItem(
        modifier = modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick?.let { long -> { haptics.longPress(); long() } }
        ),
        colors = ListItemDefaults.colors(containerColor = container),
        leadingContent = {
            val preview = onPreview?.takeIf { selected != null }
            FileThumbnail(
                item = item,
                modifier = Modifier
                    .size(44.dp)
                    .then(
                        if (preview != null) {
                            Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(role = Role.Image, onClickLabel = stringResource(R.string.action_open), onClick = preview)
                        } else {
                            Modifier
                        }
                    ),
                iconSize = 22.dp,
                previewWidthPx = 132
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (trailingBadge != null) {
                    Spacer(Modifier.size(8.dp))
                    trailingBadge()
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (supportingText.isNotEmpty()) {
                    Text(supportingText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (snippet != null) {
                    Text(
                        text = snippet,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingContent = when {
            selected != null -> {
                { SelectionCheck(selected, Modifier.padding(horizontal = 12.dp)) }
            }
            onMore != null -> {
                {
                    IconButton(onClick = onMore) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_more_options, item.name))
                    }
                }
            }
            else -> null
        }
    )
}

/** Grid tile with a large preview and the name underneath, as in a gallery or folder grid. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    item: FileUiItem,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showName: Boolean = true,
    onLongClick: (() -> Unit)? = onMore,
    selected: Boolean? = null
) {
    val haptics = rememberHaptics()
    Column(
        modifier = modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick?.let { long -> { haptics.longPress(); long() } }
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            FileThumbnail(
                item = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = MaterialTheme.shapes.medium,
                iconSize = 36.dp,
                preview = true,
                previewWidthPx = 320
            )
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        if (showName) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/** Check circle at the end of a list row in selection mode: filled when selected, outlined otherwise. */
@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) null else BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.size(24.dp)
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Check circle on a grid tile in selection mode: filled when selected, outlined otherwise. */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f),
        border = if (selected) null else BorderStroke(2.dp, Color.White),
        modifier = modifier.size(24.dp)
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}

/** Rounded container that groups list rows, as in system Settings. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(content = content)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            val blue = Accent.BLUE.colors()
            ShapedIcon(icon, ExpressiveShapes.Cookie, blue.container, blue.onContainer, size = 96.dp, iconSize = 42.dp)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
