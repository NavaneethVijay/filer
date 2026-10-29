package com.navaneeth.filer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.components.BoldLargeTopBar
import com.navaneeth.filer.ui.components.LoadingState
import com.navaneeth.filer.data.media.MediaCategory
import com.navaneeth.filer.ui.components.label
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.FileActionsSheet
import com.navaneeth.filer.ui.components.FileGridItem
import com.navaneeth.filer.ui.components.FileListItem
import com.navaneeth.filer.ui.components.FileThumbnail
import com.navaneeth.filer.ui.components.SelectionMark
import com.navaneeth.filer.ui.components.SelectionTopBar
import com.navaneeth.filer.ui.components.rememberSelection
import com.navaneeth.filer.ui.components.ViewActions
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.FolderSort
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(
    kind: MediaCategory,
    library: LibraryViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val files by remember(kind) { library.collection(kind) }.collectAsState()
    val grid by library.collectionGridView.collectAsState()
    var sort by rememberSaveable { mutableStateOf(FolderSort.DATE) }
    var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }
    val selection = rememberSelection()
    var collapsedSections by rememberSaveable { mutableStateOf(setOf<String>()) }
    // A large bold title that collapses on scroll; selection mode keeps a small pinned bar.
    val largeBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val pinnedBar = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scrollBehavior = if (selection.active) pinnedBar else largeBar
    val isGallery = kind == MediaCategory.IMAGES || kind == MediaCategory.VIDEOS
    val barSpace = LocalBottomBarSpace.current

    // Sorting and month grouping can touch thousands of files: do it off the main thread, and keep
    // showing the previous order until the new one is ready.
    val view by produceState<CollectionView?>(null, files, sort) {
        val list = files ?: return@produceState
        value = withContext(Dispatchers.Default) { CollectionView.of(list, sort, groupByMonth = isGallery && sort == FolderSort.DATE) }
    }
    val sorted = view?.items
    val open = rememberFileOpener(sorted.orEmpty())

    fun onItemClick(item: FileUiItem) {
        if (selection.active) { haptics.tick(); selection.toggle(item) } else open(item)
    }
    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(sorted) { if (selection.active && sorted != null) selection.retain(sorted) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.active) SelectionTopBar(selection, sorted.orEmpty(), scrollBehavior) else BoldLargeTopBar(
                title = stringResource(kind.label),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    ViewActions(grid = grid, onGridChange = library::setCollectionGridView, sort = sort, onSortChange = { sort = it })
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            when {
                sorted == null -> LoadingState(Modifier.padding(bottom = barSpace))
                sorted.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = stringResource(R.string.collection_empty_title),
                    body = stringResource(R.string.collection_empty_body),
                    modifier = Modifier.padding(bottom = barSpace)
                )
                else -> AnimatedContent(
                    targetState = grid,
                    transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
                    label = "collectionLayout"
                ) { asGrid ->
                    val sections = view?.sections.orEmpty()
                    when {
                        !asGrid -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barSpace + 24.dp)) {
                            sections.forEach { (title, items) ->
                                if (title.isNotEmpty()) {
                                    stickyHeader(key = "h_$title") {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            MonthHeader(
                                                title = title,
                                                count = items.size,
                                                collapsed = title in collapsedSections,
                                                onToggle = {
                                                    haptics.tick()
                                                    collapsedSections = if (title in collapsedSections) {
                                                        collapsedSections - title
                                                    } else {
                                                        collapsedSections + title
                                                    }
                                                },
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                                if (title.isEmpty() || title !in collapsedSections) {
                                    items(items, key = { it.uri }) { item ->
                                        FileListItem(
                                            item = item,
                                            supportingText = listOfNotNull(
                                                item.location,
                                                formatSize(context, item.sizeBytes),
                                                formatModified(item.modifiedAt)
                                            ).joinToString(" · "),
                                            onClick = { onItemClick(item) },
                                            onMore = { sheetItem = item },
                                            onLongClick = { selection.toggle(item) },
                                            selected = if (selection.active) item in selection else null,
                                            onPreview = { open(item) },
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                            }
                        }
                        isGallery -> {
                            // Photo gallery: tight square tiles, grouped by month when sorted by date.
                            val galleryGridState = rememberLazyGridState()
                            Box(Modifier.fillMaxSize()) {
                                LazyVerticalGrid(
                                    state = galleryGridState,
                                    columns = GridCells.Adaptive(minSize = 108.dp),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = barSpace + 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    sections.forEach { (title, items) ->
                                        if (title.isNotEmpty()) {
                                            item(key = "h_$title", span = { GridItemSpan(maxLineSpan) }) {
                                                MonthHeader(
                                                    title = title,
                                                    count = items.size,
                                                    collapsed = title in collapsedSections,
                                                    onToggle = {
                                                        haptics.tick()
                                                        collapsedSections = if (title in collapsedSections) {
                                                            collapsedSections - title
                                                        } else {
                                                            collapsedSections + title
                                                        }
                                                    },
                                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                                                )
                                            }
                                        }
                                        if (title.isEmpty() || title !in collapsedSections) {
                                            items(items, key = { it.uri }) { item ->
                                                Box(
                                                    Modifier
                                                        .aspectRatio(1f)
                                                        .animateItem()
                                                        .semantics { contentDescription = item.name }
                                                        .combinedClickable(
                                                            role = Role.Image,
                                                            onClick = { onItemClick(item) },
                                                            onLongClick = { haptics.longPress(); selection.toggle(item) }
                                                        )
                                                ) {
                                                    FileThumbnail(
                                                        item = item,
                                                        shape = MaterialTheme.shapes.medium,
                                                        previewWidthPx = 320,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                    if (selection.active) {
                                                        SelectionMark(item in selection, Modifier.align(Alignment.TopStart).padding(6.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                StickyMonthHeaderOverlay(
                                    sections = sections,
                                    gridState = galleryGridState,
                                    collapsedSections = collapsedSections,
                                    onToggle = { title ->
                                        haptics.tick()
                                        collapsedSections = if (title in collapsedSections) {
                                            collapsedSections - title
                                        } else {
                                            collapsedSections + title
                                        }
                                    }
                                )
                            }
                        }
                        else -> {
                            val normalGridState = rememberLazyGridState()
                            Box(Modifier.fillMaxSize()) {
                                LazyVerticalGrid(
                                    state = normalGridState,
                                    columns = GridCells.Adaptive(minSize = 104.dp),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = barSpace + 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    sections.forEach { (title, items) ->
                                        if (title.isNotEmpty()) {
                                            item(key = "h_$title", span = { GridItemSpan(maxLineSpan) }) {
                                                MonthHeader(
                                                    title = title,
                                                    count = items.size,
                                                    collapsed = title in collapsedSections,
                                                    onToggle = {
                                                        haptics.tick()
                                                        collapsedSections = if (title in collapsedSections) {
                                                            collapsedSections - title
                                                        } else {
                                                            collapsedSections + title
                                                        }
                                                    },
                                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                                                )
                                            }
                                        }
                                        if (title.isEmpty() || title !in collapsedSections) {
                                            items(items, key = { it.uri }) { item ->
                                                FileGridItem(
                                                    item = item,
                                                    onClick = { onItemClick(item) },
                                                    onMore = { sheetItem = item },
                                                    onLongClick = { selection.toggle(item) },
                                                    selected = if (selection.active) item in selection else null,
                                                    modifier = Modifier.animateItem()
                                                )
                                            }
                                        }
                                    }
                                }
                                StickyMonthHeaderOverlay(
                                    sections = sections,
                                    gridState = normalGridState,
                                    collapsedSections = collapsedSections,
                                    onToggle = { title ->
                                        haptics.tick()
                                        collapsedSections = if (title in collapsedSections) {
                                            collapsedSections - title
                                        } else {
                                            collapsedSections + title
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    sheetItem?.let { item -> FileActionsSheet(item = item, onDismiss = { sheetItem = null }) }
}

@Composable
private fun MonthHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = tween(200),
        label = "monthChevron"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "· $count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(if (collapsed) R.string.action_expand else R.string.action_collapse),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation)
        )
    }
}

@Composable
private fun StickyMonthHeaderOverlay(
    sections: List<Pair<String, List<FileUiItem>>>,
    gridState: LazyGridState,
    collapsedSections: Set<String>,
    onToggle: (String) -> Unit
) {
    if (sections.none { it.first.isNotEmpty() }) return

    val keyToSection = remember(sections) {
        buildMap {
            sections.forEach { (title, items) ->
                if (title.isNotEmpty()) {
                    put("h_$title", title to items.size)
                    items.forEach { put(it.uri, title to items.size) }
                }
            }
        }
    }

    val visibleItems = gridState.layoutInfo.visibleItemsInfo
    val firstKey = visibleItems.firstOrNull()?.key ?: return
    val currentSectionInfo = keyToSection[firstKey] ?: return

    val (currentTitle, currentCount) = currentSectionInfo
    val density = LocalDensity.current
    var measuredHeightPx by remember { mutableStateOf(0) }
    val headerHeightPx = if (measuredHeightPx > 0) measuredHeightPx else with(density) { 44.dp.roundToPx() }

    val nextHeader = visibleItems.firstOrNull { item ->
        val key = item.key
        key is String && key.startsWith("h_") && key != "h_$currentTitle"
    }
    val translationY = if (nextHeader != null && nextHeader.offset.y in 0 until headerHeightPx) {
        (nextHeader.offset.y - headerHeightPx).toFloat()
    } else {
        0f
    }

    val inlineHeader = visibleItems.firstOrNull { it.key == "h_$currentTitle" }
    val showSticky = inlineHeader == null || inlineHeader.offset.y <= 0

    if (showSticky) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { measuredHeightPx = it.height }
                .graphicsLayer { this.translationY = translationY }
                .padding(horizontal = 16.dp)
        ) {
            MonthHeader(
                title = currentTitle,
                count = currentCount,
                collapsed = currentTitle in collapsedSections,
                onToggle = { onToggle(currentTitle) },
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            )
        }
    }
}

/** A category's files in display order, plus month sections for the photo gallery. */
private class CollectionView(val items: List<FileUiItem>, val sections: List<Pair<String, List<FileUiItem>>>) {
    companion object {
        fun of(files: List<FileUiItem>, sort: FolderSort, groupByMonth: Boolean): CollectionView {
            val items = when (sort) {
                FolderSort.NAME -> files.sortedBy { it.name.lowercase() }
                FolderSort.DATE -> files.sortedByDescending { it.modifiedAt }
                FolderSort.SIZE -> files.sortedByDescending { it.sizeBytes }
            }
            val sections = if (groupByMonth) {
                val fmt = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
                items.groupBy { fmt.format(Date(it.modifiedAt)) }.toList()
            } else {
                listOf("" to items)
            }
            return CollectionView(items, sections)
        }
    }
}
