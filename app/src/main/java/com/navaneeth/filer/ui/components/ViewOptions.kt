package com.navaneeth.filer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.FolderSort

/**
 * Top-app-bar actions for a file list: an optional sort menu and a list/grid toggle.
 * The toggle shows the layout you'd switch *to*, like the Files app.
 */
@Composable
fun RowScope.ViewActions(
    grid: Boolean,
    onGridChange: (Boolean) -> Unit,
    sort: FolderSort? = null,
    onSortChange: ((FolderSort) -> Unit)? = null
) {
    val haptics = rememberHaptics()
    var sortMenu by remember { mutableStateOf(false) }
    if (sort != null && onSortChange != null) {
        Box {
            IconButton(onClick = { sortMenu = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.cd_sort))
            }
            SortMenu(
                expanded = sortMenu,
                current = sort,
                onDismiss = { sortMenu = false },
                onSelect = { haptics.tick(); onSortChange(it); sortMenu = false }
            )
        }
    }
    IconButton(onClick = { haptics.tick(); onGridChange(!grid) }) {
        Crossfade(targetState = grid, label = "viewToggle") { isGrid ->
            if (isGrid) {
                Icon(Icons.AutoMirrored.Rounded.ViewList, contentDescription = stringResource(R.string.cd_list_view))
            } else {
                Icon(Icons.Rounded.GridView, contentDescription = stringResource(R.string.cd_grid_view))
            }
        }
    }
}

val FolderSort.label: Int
    get() = when (this) {
        FolderSort.NAME -> R.string.sort_name
        FolderSort.DATE -> R.string.sort_date
        FolderSort.SIZE -> R.string.sort_size
    }

@Composable
fun SortMenu(
    expanded: Boolean,
    current: FolderSort,
    onDismiss: () -> Unit,
    onSelect: (FolderSort) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FolderSort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(option.label)) },
                onClick = { onSelect(option) },
                trailingIcon = if (option == current) {
                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                } else {
                    null
                }
            )
        }
    }
}
