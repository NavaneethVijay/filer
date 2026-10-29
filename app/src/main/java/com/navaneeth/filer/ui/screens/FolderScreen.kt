package com.navaneeth.filer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.HorizontalDivider
import com.navaneeth.filer.ui.components.FileManageRows
import com.navaneeth.filer.ui.components.SelectionTopBar
import com.navaneeth.filer.ui.components.rememberSelection
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.components.LoadingState
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.viewmodel.AddFolderResult
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel
import com.navaneeth.filer.ui.viewmodel.SmartFolder
import kotlinx.coroutines.launch
import java.io.File
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.FileActionsSheet
import com.navaneeth.filer.ui.components.FileGridItem
import com.navaneeth.filer.ui.components.FileListItem
import com.navaneeth.filer.ui.components.ViewActions
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.FolderContent
import com.navaneeth.filer.ui.viewmodel.FolderViewModel

@Composable
fun FolderScreen(
    library: LibraryViewModel,
    onBack: () -> Unit,
    onOpenFolder: (path: String) -> Unit,
    onNavigateTo: (path: String) -> Unit,
    viewModel: FolderViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val content by viewModel.content.collectAsState()
    val listed = (content as? FolderContent.Loaded)?.items.orEmpty()
    val open = rememberFileOpener(listed)
    val refreshing by viewModel.isRefreshing.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val grid by viewModel.gridView.collectAsState()
    val smartFolders by library.smartFolders.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }
    var folderSheet by remember { mutableStateOf<FileUiItem?>(null) }
    var confirmRemove by remember { mutableStateOf<SmartFolder?>(null) }
    val fileOps = LocalFileOps.current
    val selection = rememberSelection()
    val barSpace = LocalBottomBarSpace.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val crumbs = remember(viewModel.path) { breadcrumbsFor(context, viewModel.path) }
    val smartPaths = remember(smartFolders) { smartFolders.mapTo(HashSet()) { it.path } }
    val covering = remember(smartFolders, viewModel.path) { library.smartFolderFor(viewModel.path) }
    val addedMessage = stringResource(R.string.smart_added)
    val includedMessage = covering?.let { stringResource(R.string.smart_already_included, it.name) }.orEmpty()

    fun onItemClick(item: FileUiItem) {
        when {
            selection.active -> { haptics.tick(); selection.toggle(item) }
            item.isDirectory -> item.documentId?.let(onOpenFolder)
            else -> open(item)
        }
    }

    BackHandler(enabled = selection.active) { selection.clear() }
    // Drop selections that vanished (moved, deleted) after a refresh.
    LaunchedEffect(listed) { if (selection.active) selection.retain(listed) }

    fun addToSmartSearch(path: String) {
        scope.launch {
            val message = when (val result = library.addSmartFolder(path)) {
                AddFolderResult.Added -> { haptics.confirm(); addedMessage }
                is AddFolderResult.AlreadyIncluded -> context.getString(R.string.smart_already_included, result.parentName)
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                if (selection.active) SelectionTopBar(selection, listed, scrollBehavior) else TopAppBar(
                    title = { Text(crumbs.lastOrNull()?.first.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        // Smart search for this folder: on (this folder), inherited (a parent is on), or off.
                        IconButton(onClick = {
                            when {
                                covering == null -> addToSmartSearch(viewModel.path)
                                covering.path == viewModel.path -> confirmRemove = covering
                                else -> scope.launch { snackbar.showSnackbar(includedMessage) }
                            }
                        }) {
                            Icon(
                                imageVector = if (covering != null) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome,
                                contentDescription = stringResource(if (covering != null) R.string.cd_smart_on else R.string.cd_smart_off),
                                tint = if (covering != null) MaterialTheme.colorScheme.tertiary else LocalContentColor.current
                            )
                        }
                        IconButton(onClick = { fileOps.requestNewFolder(viewModel.path) }) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.action_new_folder))
                        }
                        ViewActions(grid = grid, onGridChange = viewModel::setGridView, sort = sort, onSortChange = viewModel::setSort)
                    },
                    scrollBehavior = scrollBehavior
                )
                if (crumbs.size > 1) Breadcrumbs(crumbs, onNavigate = { haptics.tick(); onNavigateTo(it) })
            }
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { haptics.gestureThreshold(); viewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when (val state = content) {
                    FolderContent.Loading -> LoadingState(Modifier.padding(bottom = barSpace))
                    FolderContent.Inaccessible -> EmptyState(
                        icon = Icons.Rounded.FolderOff,
                        title = stringResource(R.string.folder_error_title),
                        body = stringResource(R.string.folder_error_body),
                        modifier = Modifier.padding(bottom = barSpace)
                    )
                    is FolderContent.Loaded -> if (state.items.isEmpty()) {
                        // Scrollable so pull-to-refresh still works on an empty folder.
                        LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                EmptyState(
                                    icon = Icons.Rounded.FolderOpen,
                                    title = stringResource(R.string.folder_empty_title),
                                    body = stringResource(R.string.folder_empty_body),
                                    modifier = Modifier.padding(top = 48.dp)
                                )
                            }
                        }
                    } else {
                        AnimatedContent(
                            targetState = grid,
                            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
                            label = "folderLayout"
                        ) { asGrid ->
                            if (asGrid) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 104.dp),
                                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = barSpace + 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(state.items, key = { it.uri }) { item ->
                                        FileGridItem(
                                            item = item,
                                            onClick = { onItemClick(item) },
                                            onMore = if (item.isDirectory) ({ folderSheet = item }) else ({ sheetItem = item }),
                                            onLongClick = { selection.toggle(item) },
                                            selected = if (selection.active) item in selection else null,
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barSpace + 24.dp)) {
                                    items(state.items, key = { it.uri }) { item ->
                                        FileListItem(
                                            item = item,
                                            supportingText = if (item.isDirectory) {
                                                pluralStringResource(R.plurals.item_count, item.childCount, item.childCount)
                                            } else {
                                                listOf(formatSize(context, item.sizeBytes), formatModified(item.modifiedAt))
                                                    .filter { it.isNotEmpty() }
                                                    .joinToString(" · ")
                                            },
                                            onClick = { onItemClick(item) },
                                            onMore = if (item.isDirectory) ({ folderSheet = item }) else ({ sheetItem = item }),
                                            onLongClick = { selection.toggle(item) },
                                            selected = if (selection.active) item in selection else null,
                                            onPreview = if (item.isDirectory) null else ({ open(item) }),
                                            trailingBadge = if (item.isDirectory && item.documentId in smartPaths) {
                                                { Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(R.string.cd_smart_on), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)) }
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sheetItem?.let { item -> FileActionsSheet(item = item, onDismiss = { sheetItem = null }) }
    folderSheet?.let { folder ->
        val path = folder.documentId.orEmpty()
        val folderCovering = library.smartFolderFor(path)
        FolderSheet(
            item = folder,
            smartState = when {
                folderCovering == null -> SmartState.OFF
                folderCovering.path == path -> SmartState.ON
                else -> SmartState.INHERITED
            },
            inheritedFrom = folderCovering?.name,
            onOpen = { folderSheet = null; onOpenFolder(path) },
            onAdd = { folderSheet = null; addToSmartSearch(path) },
            onRemove = { folderSheet = null; confirmRemove = folderCovering },
            onDismiss = { folderSheet = null },
            close = { then -> folderSheet = null; then() }
        )
    }
    confirmRemove?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.remove_smart_title, folder.name),
            body = stringResource(R.string.remove_smart_body),
            confirm = stringResource(R.string.action_remove),
            onConfirm = { haptics.confirm(); library.removeSmartFolder(folder); confirmRemove = null },
            onDismiss = { confirmRemove = null }
        )
    }
}

/** Tappable path from the storage root; tapping a segment jumps to that folder. */
@Composable
private fun Breadcrumbs(crumbs: List<Pair<String, String>>, onNavigate: (String) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs.size) { state.scrollToItem(crumbs.lastIndex) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(crumbs) { index, (name, path) ->
            if (index == crumbs.lastIndex) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                )
            } else {
                TextButton(onClick = { onNavigate(path) }) {
                    Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** (label, path) pairs from the volume root down to [path]. */
private fun breadcrumbsFor(context: Context, path: String): List<Pair<String, String>> {
    val volume = Storage.volumeFor(context, path) ?: return listOf(File(path).name to path)
    val crumbs = mutableListOf(volume.label to volume.root.path)
    var current = volume.root.path
    path.removePrefix(volume.root.path).split('/').filter { it.isNotEmpty() }.forEach { segment ->
        current = "$current/$segment"
        crumbs += segment to current
    }
    return crumbs
}

private enum class SmartState { ON, INHERITED, OFF }

/** ⋮ actions for a folder: open it, include it in (or drop it from) smart search, and manage it. */
@Composable
private fun FolderSheet(
    item: FileUiItem,
    smartState: SmartState,
    inheritedFrom: String?,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    close: (then: () -> Unit) -> Unit
) {
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            ListItem(
                colors = rowColors,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpen),
                leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.action_open)) }
            )
            when (smartState) {
                SmartState.OFF -> ListItem(
                    colors = rowColors,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onAdd),
                    leadingContent = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                    headlineContent = { Text(stringResource(R.string.add_to_smart)) },
                    supportingContent = { Text(stringResource(R.string.add_to_smart_body)) }
                )
                SmartState.ON -> ListItem(
                    colors = rowColors,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onRemove),
                    leadingContent = { Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(R.string.remove_from_smart), color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(stringResource(R.string.remove_from_smart_body)) }
                )
                SmartState.INHERITED -> ListItem(
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                    headlineContent = { Text(stringResource(R.string.smart_already_included, inheritedFrom.orEmpty())) }
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            FileManageRows(item = item, close = close)
        }
    }
}
