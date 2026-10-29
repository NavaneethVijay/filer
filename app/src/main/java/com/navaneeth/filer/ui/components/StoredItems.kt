package com.navaneeth.filer.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.rememberHaptics
import java.io.File

/** A Recycle bin or Locked folder entry: its database [id] and how it's drawn. */
@Immutable
data class StoredRow(val id: Long, val item: FileUiItem, val supporting: String)

/** A row for a file Filer keeps aside, built from its record so listing never touches the disk. */
fun storedItem(storedPath: String, name: String, mimeType: String, sizeBytes: Long, at: Long, isDirectory: Boolean) = FileUiItem(
    uri = Uri.fromFile(File(storedPath)).toString(),
    name = name,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    modifiedAt = at,
    isDirectory = isDirectory
)

/** Rows with multi-select: tap opens [onOpen] (or toggles while selecting), long-press selects. */
@Composable
fun StoredItemList(
    rows: List<StoredRow>,
    selection: Selection,
    onOpen: (StoredRow) -> Unit,
    onMore: (StoredRow) -> Unit,
    header: (LazyListScope.() -> Unit)? = null
) {
    val haptics = rememberHaptics()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomBarSpace.current + 24.dp)) {
        header?.invoke(this)
        items(rows, key = { it.id }) { row ->
            FileListItem(
                item = row.item,
                supportingText = row.supporting,
                onClick = { if (selection.active) { haptics.tick(); selection.toggle(row.item) } else onOpen(row) },
                onMore = { onMore(row) },
                onLongClick = { selection.toggle(row.item) },
                selected = if (selection.active) row.item in selection else null,
                onPreview = if (row.item.isDirectory) null else ({ onOpen(row) }),
                modifier = Modifier.animateItem()
            )
        }
    }
}

/** Contextual bar for selected stored items: one main action (Restore / Unlock) and Delete forever. */
@Composable
fun StoredSelectionBar(
    selection: Selection,
    rows: List<StoredRow>,
    primaryIcon: ImageVector,
    primaryLabel: String,
    onPrimary: (List<Long>) -> Unit,
    onDeleteForever: (List<Long>) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    val haptics = rememberHaptics()
    val ids = rows.filter { it.item in selection }.map { it.id }
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, ids.size, ids.size)) },
        navigationIcon = {
            IconButton(onClick = { haptics.tick(); selection.clear() }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_selection))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Accent.BLUE.colors().container,
            scrolledContainerColor = Accent.BLUE.colors().container
        ),
        actions = {
            IconButton(onClick = { selection.selectAll(rows.map { it.item }) }) {
                Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            IconButton(onClick = { haptics.confirm(); onPrimary(ids); selection.clear() }) {
                Icon(primaryIcon, contentDescription = primaryLabel)
            }
            IconButton(onClick = { onDeleteForever(ids) }) {
                Icon(Icons.Rounded.DeleteForever, contentDescription = stringResource(R.string.action_delete_forever))
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/** ⋮ sheet for one stored item: open, the main action, delete forever. */
@Composable
fun StoredItemSheet(
    row: StoredRow,
    primaryIcon: ImageVector,
    primaryLabel: String,
    onOpen: (() -> Unit)?,
    onPrimary: () -> Unit,
    onDeleteForever: () -> Unit,
    onDismiss: () -> Unit
) {
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)

    @Composable
    fun Action(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
        val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        ListItem(
            colors = rowColors,
            modifier = Modifier.clickable(role = Role.Button) { onDismiss(); onClick() },
            leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
            headlineContent = { Text(label, color = if (danger) tint else Color.Unspecified) }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                text = row.item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (onOpen != null) Action(Icons.Rounded.Visibility, stringResource(R.string.action_open), onClick = onOpen)
            Action(primaryIcon, primaryLabel, onClick = onPrimary)
            Action(Icons.Rounded.DeleteForever, stringResource(R.string.action_delete_forever), danger = true, onClick = onDeleteForever)
        }
    }
}
