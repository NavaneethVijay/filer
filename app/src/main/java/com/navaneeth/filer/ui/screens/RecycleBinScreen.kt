package com.navaneeth.filer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.components.BoldLargeTopBar
import com.navaneeth.filer.ui.components.LoadingState
import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.StoredItemList
import com.navaneeth.filer.ui.components.StoredItemSheet
import com.navaneeth.filer.ui.components.StoredRow
import com.navaneeth.filer.ui.components.StoredSelectionBar
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.rememberSelection
import com.navaneeth.filer.ui.components.storedItem
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import java.io.File
import java.util.concurrent.TimeUnit

/** Deleted items, kept for 30 days: restore them or delete them for good. */
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val trash by fileOps.trash.collectAsState()
    val selection = rememberSelection()
    var sheetRow by remember { mutableStateOf<StoredRow?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Long>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    // A large bold title that collapses on scroll; selection mode keeps a small pinned bar.
    val largeBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val pinnedBar = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scrollBehavior = if (selection.active) pinnedBar else largeBar
    val restoreLabel = stringResource(R.string.action_restore)
    val barSpace = LocalBottomBarSpace.current

    val rows = remember(trash) {
        val now = System.currentTimeMillis()
        trash?.map { item ->
            val daysLeft = (FileManager.TRASH_DAYS - TimeUnit.MILLISECONDS.toDays(now - item.deletedAt)).coerceAtLeast(1).toInt()
            val from = File(item.originalPath).parentFile?.name.orEmpty()
            StoredRow(
                id = item.id,
                item = storedItem(item.storedPath, item.name, item.mimeType, item.sizeBytes, item.deletedAt, item.isDirectory),
                supporting = listOf(
                    context.getString(R.string.bin_from, from),
                    formatSize(context, item.sizeBytes),
                    context.resources.getQuantityString(R.plurals.bin_days_left, daysLeft, daysLeft)
                ).joinToString(" · ")
            )
        }
    }

    val open = rememberFileOpener(rows?.map { it.item }.orEmpty(), manageable = false)

    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(rows) { rows?.let { list -> selection.retain(list.map { it.item }) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.active) {
                StoredSelectionBar(
                    selection = selection,
                    rows = rows.orEmpty(),
                    primaryIcon = Icons.Rounded.RestoreFromTrash,
                    primaryLabel = restoreLabel,
                    onPrimary = fileOps::restore,
                    onDeleteForever = { confirmDelete = it },
                    scrollBehavior = scrollBehavior
                )
            } else {
                BoldLargeTopBar(
                    title = stringResource(R.string.bin_title),
                    onBack = onBack,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (!rows.isNullOrEmpty()) {
                            IconButton(onClick = { confirmEmpty = true }) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.action_empty_bin))
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            val list = rows
            when {
                list == null -> LoadingState(Modifier.padding(bottom = barSpace))
                list.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.DeleteOutline,
                    title = stringResource(R.string.bin_empty_title),
                    body = stringResource(R.string.bin_empty_body),
                    modifier = Modifier.padding(bottom = barSpace)
                )
                else -> StoredItemList(
                    rows = list,
                    selection = selection,
                    onOpen = { if (!it.item.isDirectory) open(it.item) else sheetRow = it },
                    onMore = { sheetRow = it },
                    header = {
                        item(key = "hint") {
                            Text(
                                stringResource(R.string.bin_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                )
            }
        }
    }

    sheetRow?.let { row ->
        StoredItemSheet(
            row = row,
            primaryIcon = Icons.Rounded.RestoreFromTrash,
            primaryLabel = restoreLabel,
            onOpen = if (row.item.isDirectory) null else ({ open(row.item) }),
            onPrimary = { haptics.confirm(); fileOps.restore(listOf(row.id)) },
            onDeleteForever = { confirmDelete = listOf(row.id) },
            onDismiss = { sheetRow = null }
        )
    }
    confirmDelete?.let { ids ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_forever_title, ids.size, ids.size),
            body = stringResource(R.string.delete_forever_body),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { haptics.confirm(); fileOps.deleteForever(ids); selection.clear(); confirmDelete = null },
            onDismiss = { confirmDelete = null }
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.empty_bin_title),
            body = stringResource(R.string.empty_bin_body),
            confirm = stringResource(R.string.action_empty_bin),
            onConfirm = { haptics.confirm(); fileOps.emptyTrash(); confirmEmpty = false },
            onDismiss = { confirmEmpty = false }
        )
    }
}
