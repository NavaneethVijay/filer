package com.navaneeth.filer.ui.screens.storage

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.CleanupKind
import com.navaneeth.filer.data.analysis.FoundFile
import com.navaneeth.filer.data.analysis.VolumeReport
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.AccentColors
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.FileThumbnail
import com.navaneeth.filer.ui.components.SizeBar
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.LoadingState
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.model.FileCategory
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.labelRes
import com.navaneeth.filer.ui.screens.ConfirmDialog
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.components.SelectionCheck
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import com.navaneeth.filer.ui.viewmodel.StorageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One reviewable file, with the kind of clutter it was found as. */
private class ReviewItem(val file: FoundFile, val kind: CleanupKind, val ui: FileUiItem)

/** A chip above the list: either a kind of clutter (in "Review all") or a file type. */
private class ReviewFilter(val label: Int, val matches: (ReviewItem) -> Boolean)

/** Kinds where everything is safe to remove, so it starts selected. Nothing else is ticked for the user. */
private val preselectedKinds = setOf(CleanupKind.JUNK)

/**
 * Review one kind of clutter (or, with a null [kind], all of it): pick files and move them to the
 * bin or delete them for good. The list is always selecting: tap a row to pick it, its preview to
 * open the file and check it first.
 */
@Composable
fun CleanupReviewScreen(storage: StorageViewModel, kind: CleanupKind?, onBack: () -> Unit) {
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val report by storage.report.collectAsState()
    var deletedPaths by remember { mutableStateOf(emptySet<String>()) }
    // Kinds can hold thousands of files and each disk check is a round trip on shared storage, so
    // the list is built off the main thread: shown straight from the scan, then rechecked in the
    // background so files deleted outside Filer drop out. Null while it's first being built.
    val rawItems by produceState<List<ReviewItem>?>(null, report, kind) {
        val found = report?.let { withContext(Dispatchers.IO) { reviewItems(it, kind) } }.orEmpty()
        value = found
        val present = withContext(Dispatchers.IO) { found.filter { File(it.file.path).exists() } }
        if (present.size != found.size) value = present
    }
    val items = remember(rawItems, deletedPaths) { rawItems.orEmpty().filter { it.file.path !in deletedPaths } }
    val filters = remember(items, kind) { filtersFor(items, kind) }

    var filterIndex by rememberSaveable { mutableStateOf(0) }
    // Not saveable: a selection of thousands of paths is too big for saved state.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var preselected by remember { mutableStateOf(false) }
    var skipBin by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(rawItems) {
        val rawPaths = rawItems?.mapTo(HashSet()) { it.file.path } ?: return@LaunchedEffect
        deletedPaths = deletedPaths.filterTo(HashSet()) { it in rawPaths }
    }

    // Start with the safe kinds ticked, once.
    LaunchedEffect(items) {
        if (!preselected && items.isNotEmpty()) {
            selected = items.filter { it.kind in preselectedKinds }.mapTo(HashSet()) { it.file.path }
            preselected = true
        }
        // Files deleted meanwhile can't stay selected.
        val present = items.mapTo(HashSet()) { it.file.path }
        selected = selected.filterTo(HashSet()) { it in present }
    }

    val filter = filters.getOrElse(filterIndex) { filters.first() }
    val shown = remember(items, filter) { items.filter(filter.matches) }
    val chosen = remember(items, selected) { items.filter { it.file.path in selected } }
    val chosenBytes = chosen.sumOf { it.file.sizeBytes }
    val open = rememberFileOpener(shown.map { it.ui })
    val accent = (kind?.look?.accent ?: Accent.TEAL).colors()
    // Sensitive files are for keeping safe, not deleting: the action locks them instead.
    val locks = kind == CleanupKind.SENSITIVE

    fun delete() {
        val paths = chosen.map { it.file.path }
        haptics.confirm()
        deletedPaths = deletedPaths + paths
        if (skipBin) fileOps.deleteNow(paths) else fileOps.moveToTrash(paths)
        selected = emptySet()
    }

    val barSpace = LocalBottomBarSpace.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = barSpace + 190.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "hero") {
                    ReviewHero(
                        title = stringResource(kind?.look?.title ?: R.string.cleanup_all),
                        subtitle = stringResource(if (locks) R.string.review_selected_lock else R.string.review_selected),
                        count = chosen.size,
                        bytes = chosenBytes,
                        accent = accent,
                        allSelected = shown.isNotEmpty() && shown.all { it.file.path in selected },
                        onBack = onBack,
                        onToggleAll = {
                            haptics.tick()
                            val paths = shown.map { it.file.path }
                            selected = if (paths.all { it in selected }) selected - paths.toSet() else selected + paths
                        },
                        filters = filters,
                        filterIndex = filterIndex,
                        onFilter = { haptics.tick(); filterIndex = it }
                    )
                }
                if (rawItems == null) {
                    item(key = "loading") { LoadingState(Modifier.height(320.dp)) }
                } else if (shown.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Rounded.TaskAlt,
                            title = stringResource(R.string.review_empty_title),
                            body = stringResource(R.string.review_empty_body)
                        )
                    }
                }
                val largest = shown.maxOfOrNull { it.file.sizeBytes }?.takeIf { it > 0 } ?: 1L
                items(shown, key = { it.file.path }) { item ->
                    ReviewRow(
                        item = item,
                        selected = item.file.path in selected,
                        fraction = item.file.sizeBytes.toFloat() / largest,
                        onToggle = {
                            haptics.tick()
                            selected = if (item.file.path in selected) selected - item.file.path else selected + item.file.path
                        },
                        onOpen = { if (!item.ui.isDirectory) open(item.ui) },
                        modifier = Modifier.padding(horizontal = 12.dp).animateItem()
                    )
                }
            }

            if (locks) {
                DeleteToolbar(
                    count = chosen.size,
                    bytes = chosenBytes,
                    skipBin = false,
                    onSkipBin = {},
                    actionLabel = stringResource(R.string.action_lock),
                    onDelete = {
                        haptics.confirm()
                        fileOps.requestLock(chosen.map { it.file.path })
                        selected = emptySet()
                    },
                    showBinChoice = false,
                    actionIcon = Icons.Rounded.Lock,
                    destructive = false,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = barSpace)
                )
            } else {
                DeleteToolbar(
                    count = chosen.size,
                    bytes = chosenBytes,
                    skipBin = skipBin,
                    onSkipBin = { haptics.toggle(!skipBin); skipBin = !skipBin },
                    actionLabel = stringResource(if (skipBin) R.string.action_delete else R.string.action_to_bin),
                    onDelete = { if (skipBin) confirmDelete = true else delete() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = barSpace)
                )
            }
        }
    }

    if (confirmDelete) {
        val context = LocalContext.current
        ConfirmDialog(
            title = pluralStringResource(R.plurals.review_delete_forever_title, chosen.size, chosen.size),
            body = stringResource(R.string.review_delete_forever_body, context.bytes(chosenBytes)),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; delete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

// region Data

private fun reviewItems(report: VolumeReport, kind: CleanupKind?): List<ReviewItem> {
    val kinds = if (kind != null) listOf(kind) else CleanupKind.entries.filter { it.isClutter }
    val seen = HashSet<String>()
    return kinds
        .flatMap { k -> report.files(k).map { it to k } }
        .filter { (file, _) -> seen.add(file.path) }
        .sortedByDescending { (file, _) -> file.sizeBytes }
        .map { (file, k) -> ReviewItem(file, k, file.toUiItem()) }
}

/** "All", then a chip for each kind (in Review all) or file type present. */
private fun filtersFor(items: List<ReviewItem>, kind: CleanupKind?): List<ReviewFilter> {
    val all = ReviewFilter(R.string.filter_all) { true }
    val chips = if (kind == null) {
        items.map { it.kind }.distinct().map { k -> ReviewFilter(k.look.title) { it.kind == k } }
    } else {
        FileCategory.entries
            .filter { category -> items.any { category.matches(it.ui.kind) } }
            .map { category -> ReviewFilter(category.labelRes) { category.matches(it.ui.kind) } }
    }
    return if (chips.size > 1) listOf(all) + chips else listOf(all)
}

// endregion

// region Pieces

/** Coloured header: the selected total in big type, select-all, and the filter chips. */
@Composable
private fun ReviewHero(
    title: String,
    subtitle: String,
    count: Int,
    bytes: Long,
    accent: AccentColors,
    allSelected: Boolean,
    onBack: () -> Unit,
    onToggleAll: () -> Unit,
    filters: List<ReviewFilter>,
    filterIndex: Int,
    onFilter: (Int) -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
        color = accent.container,
        contentColor = accent.onContainer,
        modifier = Modifier.padding(bottom = 10.dp)
    ) {
        Box {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 60.dp, y = (-20).dp)
                    .size(200.dp)
                    .clip(ExpressiveShapes.Cookie)
                    .background(accent.strong.copy(alpha = 0.10f))
            )
            Column(Modifier.statusBarsPadding().padding(bottom = 20.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onToggleAll,
                        colors = ButtonDefaults.textButtonColors(contentColor = accent.onContainer)
                    ) {
                        Text(stringResource(if (allSelected) R.string.action_clear_selection else R.string.action_select_all))
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        title + " · " + pluralStringResource(R.plurals.review_files, count, count),
                        style = MaterialTheme.typography.titleSmall,
                        color = accent.onContainer.copy(alpha = 0.85f)
                    )
                    Text(context.bytes(bytes), style = ExpressiveType.Hero)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = accent.onContainer.copy(alpha = 0.85f))
                }
                if (filters.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        items(filters.size) { index ->
                            val isSelected = index == filterIndex
                            FilterChip(
                                selected = isSelected,
                                onClick = { onFilter(index) },
                                label = { Text(stringResource(filters[index].label)) },
                                leadingIcon = if (isSelected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                                shape = ExpressiveShapes.Pill,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = accent.onContainer,
                                    selectedContainerColor = accent.strong,
                                    selectedLabelColor = accent.onStrong,
                                    selectedLeadingIconColor = accent.onStrong
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = accent.onContainer.copy(alpha = 0.4f)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A file row, laid out like a list row in selection mode anywhere in Filer: tap the row to
 * (de)select it, the preview to open the file, and the check on the right shows the state.
 */
@Composable
private fun ReviewRow(
    item: ReviewItem,
    selected: Boolean,
    fraction: Float,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val container by animateColorAsState(
        if (selected) Accent.BLUE.colors().container else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "rowColor"
    )
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(if (selected) 28.dp else 20.dp),
        color = container,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FileThumbnail(
                item = item.ui,
                shape = ExpressiveShapes.Squircle,
                modifier = Modifier
                    .size(56.dp)
                    .clip(ExpressiveShapes.Squircle)
                    .clickable(role = Role.Image, onClickLabel = stringResource(R.string.action_open), onClick = onOpen),
                iconSize = 26.dp,
                previewWidthPx = 168
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.ui.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.ui.location, formatModified(item.file.modifiedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SizeBar(fraction, item.kind.look.accent.colors().strong, Modifier.padding(top = 4.dp), height = 6.dp)
            }
            Text(context.bytes(item.file.sizeBytes), style = MaterialTheme.typography.titleMedium)
            SelectionCheck(selected, Modifier.padding(end = 6.dp))
        }
    }
}

/**
 * Floating bottom toolbar: whether files go to the bin or are deleted for good, the selected total,
 * and the delete button.
 */
@Composable
internal fun DeleteToolbar(
    count: Int,
    bytes: Long,
    skipBin: Boolean,
    onSkipBin: () -> Unit,
    actionLabel: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Off when the action isn't a delete (nothing goes to the bin). */
    showBinChoice: Boolean = true,
    actionIcon: ImageVector = Icons.Rounded.Delete,
    destructive: Boolean = true
) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val warn = MaterialTheme.colorScheme.errorContainer
        if (showBinChoice) Surface(
            onClick = onSkipBin,
            shape = ExpressiveShapes.Pill,
            color = if (skipBin) warn else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (skipBin) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(Modifier.height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (skipBin) Icons.Rounded.WarningAmber else Icons.Rounded.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(if (skipBin) R.string.review_skip_bin else R.string.review_keep_in_bin), style = MaterialTheme.typography.labelLarge)
            }
        }
        Surface(
            shape = ExpressiveShapes.Pill,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            Row(Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.review_files, count, count), style = MaterialTheme.typography.labelMedium)
                    Text(context.bytes(bytes), style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = onDelete,
                    enabled = count > 0,
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(actionLabel, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

// endregion
