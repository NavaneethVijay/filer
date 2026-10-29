package com.navaneeth.filer.ui.screens.storage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import com.navaneeth.filer.ui.components.SelectionCheck
import com.navaneeth.filer.ui.components.SelectionEffects
import com.navaneeth.filer.ui.components.SelectionTopBar
import com.navaneeth.filer.ui.components.rememberSelection
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import com.navaneeth.filer.ui.components.ExpressiveLoader
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.FolderNode
import com.navaneeth.filer.data.analysis.PathRules
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.FileActionsSheet
import com.navaneeth.filer.ui.components.FileThumbnail
import com.navaneeth.filer.ui.components.SizeBar
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import com.navaneeth.filer.ui.viewmodel.StorageViewModel
import java.text.NumberFormat

/** A chip colour per child, by rank; neighbours differ in lightness as well as hue. */
private val blockAccents = listOf(Accent.BLUE, Accent.TEAL, Accent.SKY, Accent.BLUE, Accent.TEAL, Accent.SLATE)

/**
 * Folder sizes as a list (largest first, with share bars), followed by the files directly in
 * the folder (largest first). Tap a folder to go into it, a file to open it; back goes up a level
 * until [startPath] (or the volume root, when null). Long-press selects, as in every file list:
 * files and the folders that can be binned.
 */
@Composable
fun SpaceMapScreen(storage: StorageViewModel, startPath: String?, onBack: () -> Unit, onOpenInBrowse: (String) -> Unit) {
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val report by storage.report.collectAsState()
    val root = report?.root ?: return
    var path by rememberSaveable { mutableStateOf(startPath ?: root.path) }
    var toDelete by remember { mutableStateOf<List<FileUiItem>?>(null) }
    var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }
    val selection = rememberSelection()

    val trail = remember(root, path) { trailTo(root, path) ?: listOf(root) }
    val node = trail.last()
    val canGoUp = trail.size > 1 && node.path != startPath

    fun goUp() {
        if (canGoUp) path = trail[trail.size - 2].path else onBack()
    }
    BackHandler(enabled = canGoUp) { goUp() }
    val barSpace = LocalBottomBarSpace.current
    val context = LocalContext.current
    val children = node.children.filter { it.sizeBytes > 0 }
    // Android's own folders, app data and hidden folders can't be binned from here.
    val trashable = remember(children, root.path) {
        children.filter { PathRules.canTrashFolder(it.path.removePrefix(root.path).trimStart('/').lowercase()) }
            .associate { it.path to it.toUiItem() }
    }
    // Files aren't kept in the scan tree (there can be hundreds of thousands); list this folder's own.
    // Null while the folder is being read. Big folders (Camera) only show the largest first.
    val allFiles by remember(node.path) { storage.filesIn(node.path) }.collectAsState(initial = null)
    var shownCount by remember(node.path) { mutableIntStateOf(FILES_PAGE) }
    // filesIn lists live and re-reads after every file operation, so no per-file check is needed here.
    val existingFiles = allFiles.orEmpty()
    val files = existingFiles.take(shownCount)
    val hiddenCount = existingFiles.size - files.size
    val open = rememberFileOpener(siblings = files)
    val selectable = remember(trashable, files) { trashable.values + files }
    // After goUp's handler, so back leaves selection mode first.
    SelectionEffects(selection, selectable)

    fun onItemClick(item: FileUiItem, otherwise: () -> Unit) {
        if (selection.active) { haptics.tick(); selection.toggle(item) } else otherwise()
    }

    Scaffold(
        topBar = {
            if (selection.active) SelectionTopBar(selection, selectable, onDelete = { toDelete = it }) else TopAppBar(
                title = { Breadcrumbs(trail, onSelect = { haptics.tick(); path = it.path }) },
                navigationIcon = {
                    IconButton(onClick = ::goUp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenInBrowse(node.path) }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.action_show_in_folder))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = barSpace + 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "title") {
                val count = NumberFormat.getIntegerInstance().format(node.fileCount)
                ScreenHeader(
                    title = if (trail.size == 1) stringResource(R.string.space_map_title) else node.name,
                    subtitle = pluralStringResource(R.plurals.space_map_summary, node.fileCount, context.bytes(node.sizeBytes), count)
                )
            }
            if (children.isNotEmpty() && files.isNotEmpty()) {
                item(key = "folders") { SpaceMapHeader(stringResource(R.string.space_map_folders)) }
            }
            itemsIndexed(children, key = { _, child -> child.path }) { index, child ->
                val item = trashable[child.path]
                FolderRow(
                    node = child,
                    parentBytes = node.sizeBytes,
                    accent = blockAccents[index % blockAccents.size],
                    canTrash = item != null,
                    selected = if (selection.active) item?.let { it in selection } else null,
                    onOpen = {
                        when {
                            !selection.active -> { haptics.tick(); path = child.path }
                            item != null -> { haptics.tick(); selection.toggle(item) }
                        }
                    },
                    onLongClick = item?.let { { selection.toggle(it) } },
                    onTrash = { toDelete = listOfNotNull(item) },
                    modifier = Modifier.animateItem()
                )
            }
            if (allFiles == null) {
                item(key = "files_loading") { FilesLoading() }
            }
            if (files.isNotEmpty()) {
                item(key = "files") { SpaceMapHeader(stringResource(R.string.space_map_files)) }
                items(files, key = { "file:" + it.uri }) { file ->
                    FileSizeRow(
                        item = file,
                        parentBytes = node.sizeBytes,
                        selected = if (selection.active) file in selection else null,
                        onClick = { onItemClick(file) { open(file) } },
                        onLongClick = { selection.toggle(file) },
                        onPreview = { open(file) },
                        onMore = { sheetItem = file },
                        modifier = Modifier.animateItem()
                    )
                }
                if (hiddenCount > 0) {
                    item(key = "files_more") {
                        TextButton(onClick = { shownCount += FILES_PAGE }, modifier = Modifier.fillMaxWidth()) {
                            Text(pluralStringResource(R.plurals.space_map_show_more, hiddenCount, hiddenCount))
                        }
                    }
                }
            }
        }
    }

    sheetItem?.let { FileActionsSheet(item = it, onDismiss = { sheetItem = null }) }

    toDelete?.let { items ->
        DeleteDialog(
            items = items,
            onDelete = { forGood ->
                haptics.confirm()
                val paths = items.mapNotNull { it.pathOrNull() }
                if (forGood) fileOps.deleteNow(paths) else fileOps.moveToTrash(paths)
                if (paths.any { path == it || path.startsWith("$it/") }) goUp()
                selection.clear()
                toDelete = null
            },
            onDismiss = { toDelete = null }
        )
    }
}

/**
 * Deleting from the space map: to the Recycle bin by default, or for good so the space is freed
 * now (binned files still count until the bin is emptied).
 */
@Composable
private fun DeleteDialog(items: List<FileUiItem>, onDelete: (forGood: Boolean) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val bytes = LocalContext.current.bytes(items.sumOf { it.sizeBytes })
    var forGood by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                items.singleOrNull()?.let { stringResource(R.string.space_delete_title_one, it.name) }
                    ?: pluralStringResource(R.plurals.space_delete_title, items.size, items.size)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (forGood) stringResource(R.string.review_delete_forever_body, bytes)
                    else stringResource(R.string.space_delete_bin_body, bytes)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(value = forGood, role = Role.Checkbox, onValueChange = { haptics.toggle(it); forGood = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = forGood, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                    Text(stringResource(R.string.space_delete_skip_bin), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(forGood) }) {
                Text(stringResource(if (forGood) R.string.action_delete else R.string.action_trash), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** The folders from [root] down to [path], or null if it isn't in the tree. */
private fun trailTo(root: FolderNode, path: String): List<FolderNode>? {
    if (root.path == path) return listOf(root)
    val next = root.children.firstOrNull { path.startsWith(it.path + "/") || path == it.path } ?: return null
    return trailTo(next, path)?.let { listOf(root) + it }
}

@Composable
private fun Breadcrumbs(trail: List<FolderNode>, onSelect: (FolderNode) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(trail) { index, folder ->
            val current = index == trail.lastIndex
            val sky = Accent.SKY.colors()
            Surface(
                onClick = { onSelect(folder) },
                shape = ExpressiveShapes.Pill,
                color = if (current) sky.container else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (current) sky.onContainer else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    if (index == 0) stringResource(R.string.space_map_root) else folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * A folder in the list: share chip, name, size, bar, and a bin button. While selecting, the bin
 * button gives way to a check ([selected] is null for folders that can't be selected).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    node: FolderNode,
    parentBytes: Long,
    accent: Accent,
    canTrash: Boolean,
    selected: Boolean?,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)?,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val colors = accent.colors()
    val share = node.sizeBytes.toFloat() / parentBytes.coerceAtLeast(1)
    val count = NumberFormat.getIntegerInstance().format(node.fileCount)

    Surface(shape = RoundedCornerShape(24.dp), color = rowColor(selected), modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .combinedClickable(
                    role = Role.Button,
                    onClick = onOpen,
                    onLongClick = onLongClick?.let { long -> { haptics.longPress(); long() } }
                )
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 72.dp).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = if (node.children.isEmpty()) ExpressiveShapes.Cookie else ExpressiveShapes.Squircle, color = colors.strong, contentColor = colors.onStrong, modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (share < 0.01f) "<1%" else "${(share * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(node.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(context.bytes(node.sizeBytes), style = MaterialTheme.typography.titleSmall)
                    }
                    SizeBar(share, colors.strong, height = 10.dp)
                    Text(
                        pluralStringResource(R.plurals.storage_files, node.fileCount, count) + " · " + stringResource(R.string.space_map_tap_to_open),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                selected != null -> SelectionCheck(selected, Modifier.padding(horizontal = 12.dp))
                canTrash -> {
                    FilledTonalIconButton(onClick = onTrash, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.cd_move_folder_to_bin, node.name))
                    }
                }
            }
        }
    }
}

/** Row background: tinted while selected, the usual card colour otherwise. */
@Composable
private fun rowColor(selected: Boolean?): Color {
    val color by animateColorAsState(
        if (selected == true) Accent.BLUE.colors().container else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "rowColor"
    )
    return color
}

@Composable
private fun FilesLoading() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ExpressiveLoader(size = 40.dp)
        Text(stringResource(R.string.space_map_loading_files), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How many files a folder lists at first, and how many more each "Show more" adds. */
private const val FILES_PAGE = 50

@Composable
private fun SpaceMapHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp)
    )
}

/**
 * A file directly in the folder: thumbnail, name, size and its share of the folder. While
 * selecting, the ⋮ gives way to a check and the thumbnail still opens the file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileSizeRow(
    item: FileUiItem,
    parentBytes: Long,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val share = item.sizeBytes.toFloat() / parentBytes.coerceAtLeast(1)
    Surface(shape = RoundedCornerShape(24.dp), color = rowColor(selected), modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = { haptics.longPress(); onLongClick() })
                .heightIn(min = 72.dp)
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FileThumbnail(
                item = item,
                modifier = Modifier
                    .size(44.dp)
                    .then(
                        if (selected != null) {
                            Modifier
                                .clip(ExpressiveShapes.Squircle)
                                .clickable(role = Role.Image, onClickLabel = stringResource(R.string.action_open), onClick = onPreview)
                        } else {
                            Modifier
                        }
                    ),
                shape = ExpressiveShapes.Squircle,
                iconSize = 22.dp,
                previewWidthPx = 132
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(context.bytes(item.sizeBytes), style = MaterialTheme.typography.titleSmall)
                }
                SizeBar(share, item.kind.colors().content, height = 10.dp)
            }
            if (selected != null) {
                SelectionCheck(selected, Modifier.padding(horizontal = 12.dp))
            } else {
                IconButton(onClick = onMore) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_more_options, item.name))
                }
            }
        }
    }
}

/** A scanned folder as a list item, so it can be selected and binned like a file. */
private fun FolderNode.toUiItem() = FileUiItem(
    uri = Uri.fromFile(File(path)).toString(),
    name = name,
    mimeType = "",
    sizeBytes = sizeBytes,
    modifiedAt = 0L,
    isDirectory = true,
    documentId = path,
    childCount = fileCount
)
