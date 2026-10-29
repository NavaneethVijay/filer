package com.navaneeth.filer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.files.NameResult
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import com.navaneeth.filer.ui.screens.ConfirmDialog
import com.navaneeth.filer.ui.util.FileActions
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.FileDialog
import com.navaneeth.filer.ui.viewmodel.FileOpsViewModel
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import com.navaneeth.filer.ui.viewmodel.Transfer
import kotlinx.coroutines.launch

/** Shows the file-operation dialogs and result snackbars (with Undo) for the whole app. */
@Composable
fun FileOpsHost(fileOps: FileOpsViewModel) {
    val snackbar = LocalSnackbarHostState.current
    val haptics = rememberHaptics()
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(fileOps) {
        fileOps.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = message.undo?.let { undoLabel },
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) message.undo?.invoke()
        }
    }

    when (val dialog = fileOps.dialog.collectAsState().value) {
        is FileDialog.Rename -> NameDialog(
            title = stringResource(R.string.rename_title),
            initial = dialog.name,
            // Select the name without its extension, like the Files app.
            selectAll = dialog.isDirectory,
            confirm = stringResource(R.string.action_rename),
            submit = { fileOps.rename(dialog.path, it) },
            onDismiss = fileOps::dismissDialog
        )
        is FileDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.new_folder_title),
            initial = "",
            selectAll = true,
            confirm = stringResource(R.string.action_create),
            submit = { fileOps.createFolder(dialog.parent, it) },
            onDismiss = fileOps::dismissDialog
        )
        is FileDialog.ConfirmLock -> ConfirmDialog(
            title = pluralStringResource(R.plurals.lock_confirm_title, dialog.paths.size, dialog.paths.size),
            body = stringResource(R.string.lock_confirm_body),
            confirm = stringResource(R.string.action_lock),
            destructive = false,
            onConfirm = { haptics.confirm(); fileOps.lock(dialog.paths) },
            onDismiss = fileOps::dismissDialog
        )
        is FileDialog.ConfirmTrash -> ConfirmDialog(
            title = pluralStringResource(R.plurals.trash_confirm_title, dialog.paths.size, dialog.paths.size),
            body = stringResource(R.string.trash_confirm_body),
            confirm = stringResource(R.string.action_trash),
            onConfirm = { haptics.confirm(); fileOps.dismissDialog(); fileOps.moveToTrash(dialog.paths) },
            onDismiss = fileOps::dismissDialog
        )
        null -> Unit
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    selectAll: Boolean,
    confirm: String,
    submit: suspend (String) -> NameResult,
    onDismiss: () -> Unit
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val stem = if (selectAll) initial.length else initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            when (submit(field.text)) {
                is NameResult.Done -> { haptics.confirm(); onDismiss() }
                NameResult.Invalid -> { haptics.reject(); error = R.string.name_error_invalid }
                NameResult.Exists -> { haptics.reject(); error = R.string.name_error_exists }
                NameResult.Failed -> { haptics.reject(); error = R.string.name_error_failed }
            }
            saving = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; error = null },
                label = { Text(stringResource(R.string.name_label)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(it)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = field.text.isNotBlank() && field.text != initial) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Roughly how tall [TransferBar] is, so the snackbar can sit above it. */
val TransferBarHeight = 72.dp

/**
 * Shown while files wait to be moved or copied: the user browses to a folder and taps
 * "Move here". [folder] is the folder on screen, or null when not in a folder.
 */
@Composable
fun TransferBar(transfer: Transfer, folder: String?, onPaste: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val count = transfer.paths.size
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(if (transfer.move) R.plurals.transfer_moving else R.plurals.transfer_copying, count, count),
                    style = MaterialTheme.typography.titleSmall
                )
                if (folder == null) {
                    Text(stringResource(R.string.transfer_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { haptics.tick(); onCancel() }) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.inversePrimary)
            }
            if (folder != null) {
                Button(onClick = { haptics.confirm(); onPaste(folder) }) {
                    Text(stringResource(if (transfer.move) R.string.action_move_here else R.string.action_copy_here))
                }
            }
        }
    }
}

/** Multi-select state for a file list, keyed by [FileUiItem.uri]. */
@Stable
class Selection {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    val active: Boolean get() = keys.isNotEmpty()

    operator fun contains(item: FileUiItem) = item.uri in keys
    fun toggle(item: FileUiItem) {
        keys = if (item.uri in keys) keys - item.uri else keys + item.uri
    }
    fun selectAll(items: List<FileUiItem>) {
        keys = items.mapTo(HashSet()) { it.uri }
    }
    fun clear() {
        keys = emptySet()
    }
    /** Keeps only keys still present in [items]. */
    fun retain(items: List<FileUiItem>) {
        val present = items.mapTo(HashSet()) { it.uri }
        keys = keys.filterTo(HashSet()) { it in present }
    }
}

@Composable
fun rememberSelection() = remember { Selection() }

/** Back ends selection mode, and items that left [items] (moved, deleted) drop out of the selection. */
@Composable
fun SelectionEffects(selection: Selection, items: List<FileUiItem>?) {
    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(items) { if (selection.active && items != null) selection.retain(items) }
}

/** Contextual top bar while items are selected: share, delete, and the rest in an overflow menu. */
@Composable
fun SelectionTopBar(
    selection: Selection,
    items: List<FileUiItem>,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    /** Replaces the usual "Move to Recycle bin?" step; the screen clears the selection when it's done. */
    onDelete: ((List<FileUiItem>) -> Unit)? = null
) {
    val context = LocalContext.current
    val fileOps = LocalFileOps.current
    val haptics = rememberHaptics()
    val favorites by fileOps.favoritePaths.collectAsState()
    val selected = remember(items, selection.keys) { items.filter { it in selection } }
    val paths = remember(selected) { selected.mapNotNull { it.pathOrNull() } }
    var menu by remember { mutableStateOf(false) }

    fun act(block: () -> Unit) {
        menu = false
        block()
        selection.clear()
    }

    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size)) },
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
            if (selected.none { it.isDirectory }) {
                IconButton(onClick = { act { FileActions.shareAll(context, selected) } }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share))
                }
            }
            IconButton(onClick = { haptics.tick(); if (onDelete != null) onDelete(selected) else act { fileOps.requestTrash(paths) } }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_trash))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow(Icons.Rounded.SelectAll, R.string.action_select_all) { menu = false; selection.selectAll(items) }
                    MenuRow(Icons.AutoMirrored.Rounded.DriveFileMove, R.string.action_move_to) { act { fileOps.startTransfer(paths, move = true) } }
                    MenuRow(Icons.Rounded.ContentCopy, R.string.action_copy_to) { act { fileOps.startTransfer(paths, move = false) } }
                    if (selected.size == 1) {
                        MenuRow(Icons.Rounded.DriveFileRenameOutline, R.string.action_rename) { act { fileOps.requestRename(selected.first()) } }
                    }
                    val allFavorite = paths.isNotEmpty() && paths.all { it in favorites }
                    MenuRow(
                        if (allFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        if (allFavorite) R.string.action_unfavorite else R.string.action_favorite
                    ) { act { fileOps.setFavorite(paths, !allFavorite) } }
                    MenuRow(Icons.Rounded.Lock, R.string.action_lock) { act { fileOps.requestLock(paths) } }
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun MenuRow(icon: ImageVector, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}

/**
 * The file-manager rows of a file's or folder's sheet: favourite, rename, move, copy, lock, delete.
 * [close] dismisses the sheet before the action runs.
 */
@Composable
fun FileManageRows(item: FileUiItem, close: (then: () -> Unit) -> Unit) {
    val path = item.pathOrNull() ?: return
    val fileOps = LocalFileOps.current
    val haptics = rememberHaptics()
    val favorites by fileOps.favoritePaths.collectAsState()
    val isFavorite = path in favorites
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)

    @Composable
    fun ActionRow(icon: ImageVector, label: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
        ListItem(
            colors = rowColors,
            modifier = Modifier.clickable(role = Role.Button) { close(onClick) },
            leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
            headlineContent = {
                Text(
                    stringResource(label),
                    color = if (tint == MaterialTheme.colorScheme.error) tint else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    }

    Column {
        ActionRow(
            if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
            if (isFavorite) R.string.action_unfavorite else R.string.action_favorite,
            tint = if (isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
        ) { haptics.tick(); fileOps.setFavorite(listOf(path), !isFavorite) }
        ActionRow(Icons.Rounded.DriveFileRenameOutline, R.string.action_rename) { fileOps.requestRename(item) }
        ActionRow(Icons.AutoMirrored.Rounded.DriveFileMove, R.string.action_move_to) { fileOps.startTransfer(listOf(path), move = true) }
        ActionRow(Icons.Rounded.ContentCopy, R.string.action_copy_to) { fileOps.startTransfer(listOf(path), move = false) }
        ActionRow(Icons.Rounded.Lock, R.string.action_lock) { fileOps.requestLock(listOf(path)) }
        ActionRow(Icons.Rounded.Delete, R.string.action_trash, tint = MaterialTheme.colorScheme.error) {
            fileOps.requestTrash(listOf(path))
        }
    }
}
