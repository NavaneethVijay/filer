package com.navaneeth.filer.ui.screens.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.DuplicateGroup
import com.navaneeth.filer.data.analysis.FoundFile
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.AccentColors
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.FileThumbnail
import com.navaneeth.filer.ui.components.ShapedIcon
import com.navaneeth.filer.ui.components.WavyProgressIndicator
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.screens.ConfirmDialog
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.util.rememberFileOpener
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.annotation.StringRes
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import kotlinx.coroutines.launch
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import com.navaneeth.filer.ui.viewmodel.StorageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Groups of identical files. One copy per group is kept (the first, by [DuplicateFinder]'s rule,
 * until the user taps another), every other copy is removed.
 */
@Composable
fun DuplicatesScreen(storage: StorageViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val report by storage.report.collectAsState()
    val rawGroups = report?.duplicates
    var deletedPaths by remember { mutableStateOf(emptySet<String>()) }
    // Disk checks run off the main thread (there can be hundreds of copies); until they finish the
    // groups are shown as scanned.
    val presentGroups by produceState(rawGroups, rawGroups) {
        value = withContext(Dispatchers.IO) {
            rawGroups?.map { group -> group.copy(copies = group.copies.filter { File(it.path).exists() }) }
        }
    }
    val groups = remember(presentGroups, deletedPaths) {
        presentGroups?.map { group -> group.copy(copies = group.copies.filter { it.path !in deletedPaths }) }
            ?.filter { it.copies.size > 1 }
    }
    val sky = Accent.SKY.colors()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val lastCopyMessage = stringResource(R.string.duplicates_last_copy)

    /** How copies are picked unless the user chose for a group. */
    var rule by rememberSaveable { mutableStateOf(KeepRule.SMART) }
    /** Group id (its first copy's path) → the copies the user chose to remove from it. */
    var chosen by rememberSaveable { mutableStateOf(emptyMap<String, Set<String>>()) }
    var skipBin by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun removalsOf(group: DuplicateGroup): Set<String> {
        val present = group.copies.mapTo(HashSet()) { it.path }
        // Copies that have gone since the choice was made drop out; a group must keep one copy.
        val picked = chosen[group.id]?.filterTo(HashSet()) { it in present }
        return picked?.takeIf { it.size < group.copies.size } ?: rule.removals(group)
    }

    fun setRemovals(group: DuplicateGroup, paths: Set<String>) {
        chosen = chosen + (group.id to paths)
    }

    /** Tap on a copy: keep it or remove it, but never remove the last copy kept. */
    fun toggle(group: DuplicateGroup, path: String) {
        val removals = removalsOf(group)
        when {
            path in removals -> { haptics.tick(); setRemovals(group, removals - path) }
            removals.size + 1 < group.copies.size -> { haptics.tick(); setRemovals(group, removals + path) }
            else -> {
                haptics.reject()
                scope.launch { snackbar.showSnackbar(lastCopyMessage) }
            }
        }
    }

    /** Long-press on a copy: in every group with a copy in that folder, keep that copy and remove the rest. */
    fun preferFolder(folder: String) {
        haptics.longPress()
        val updates = groups.orEmpty().mapNotNull { group ->
            val keep = group.copies.firstOrNull { it.parentPath == folder } ?: return@mapNotNull null
            group.id to group.copies.filter { it.path != keep.path }.mapTo(HashSet()) { it.path }
        }
        chosen = chosen + updates
        scope.launch { snackbar.showSnackbar(context.getString(R.string.duplicates_prefer_folder, Storage.displayPath(context, folder))) }
    }

    val toRemove = groups.orEmpty().flatMap { group ->
        val removals = removalsOf(group)
        group.copies.filter { it.path in removals }
    }
    val removeBytes = toRemove.sumOf { it.sizeBytes }

    fun delete() {
        val paths = toRemove.map { it.path }
        haptics.confirm()
        deletedPaths = deletedPaths + paths
        if (skipBin) fileOps.deleteNow(paths) else fileOps.moveToTrash(paths)
    }

    val barSpace = LocalBottomBarSpace.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = barSpace + 190.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "hero") { DuplicatesHero(groups, removeBytes, sky, onBack) }
                if (!groups.isNullOrEmpty()) {
                    item(key = "rules") {
                        RuleChips(rule, onRule = { haptics.tick(); rule = it; chosen = emptyMap() })
                    }
                }
                items(groups.orEmpty(), key = { it.id }) { group ->
                    val removals = removalsOf(group)
                    DuplicateGroupCard(
                        group = group,
                        removals = removals,
                        onToggle = { path -> toggle(group, path) },
                        onPreferFolder = ::preferFolder,
                        onKeepAll = {
                            haptics.tick()
                            // Kept all → back to the rule's pick (or one copy if the rule keeps all).
                            val back = if (rule == KeepRule.NONE) KeepRule.SMART else rule
                            setRemovals(group, if (removals.isEmpty()) back.removals(group) else emptySet())
                        },
                        modifier = Modifier.padding(horizontal = 12.dp).animateItem()
                    )
                }
            }
            if (groups != null) {
                DeleteToolbar(
                    count = toRemove.size,
                    bytes = removeBytes,
                    skipBin = skipBin,
                    onSkipBin = { haptics.toggle(!skipBin); skipBin = !skipBin },
                    actionLabel = stringResource(R.string.action_remove_copies),
                    onDelete = { if (skipBin) confirmDelete = true else delete() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = barSpace)
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.review_delete_forever_title, toRemove.size, toRemove.size),
            body = stringResource(R.string.review_delete_forever_body, context.bytes(removeBytes)),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; delete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Blue-grey header: how much removing the extra copies frees, and the keep rule. */
@Composable
private fun DuplicatesHero(groups: List<DuplicateGroup>?, bytes: Long, colors: AccentColors, onBack: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
        color = colors.container,
        contentColor = colors.onContainer
    ) {
        Box {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 70.dp, y = (-50).dp)
                    .size(220.dp)
                    .clip(ExpressiveShapes.Flower)
                    .background(colors.strong.copy(alpha = 0.12f))
            )
            Column(Modifier.statusBarsPadding().padding(bottom = 20.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (groups == null) {
                        Text(stringResource(R.string.cleanup_duplicates), style = ExpressiveType.Big)
                        Text(stringResource(R.string.duplicates_checking), style = MaterialTheme.typography.bodyMedium)
                        WavyProgressIndicator(null, Modifier.fillMaxWidth().padding(top = 12.dp), color = colors.strong, trackColor = colors.strong.copy(alpha = 0.24f))
                    } else {
                        Text(pluralStringResource(R.plurals.duplicates_groups, groups.size, groups.size), style = MaterialTheme.typography.titleSmall)
                        Text(context.bytes(bytes), style = ExpressiveType.Hero)
                        Text(stringResource(R.string.duplicates_rule), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** A group: a tilted stack preview with the copy count and a Keep all switch, then one row per copy. */
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    removals: Set<String>,
    onToggle: (String) -> Unit,
    onPreferFolder: (String) -> Unit,
    onKeepAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sky = Accent.SKY.colors()
    val first = group.copies.first()
    val preview = remember(first.path) { first.toUiItem() }
    val open = rememberFileOpener(listOf(preview))
    val frees = group.sizeBytes * removals.size
    val skipped = removals.isEmpty()

    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CopyStack(preview, group.copies.size, sky, onOpen = { open(preview) })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(first.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.duplicates_each, context.bytes(group.sizeBytes)), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.duplicates_frees, context.bytes(frees)),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (skipped) MaterialTheme.colorScheme.onSurfaceVariant else sky.strong
                    )
                }
                TextButton(onClick = onKeepAll) {
                    Text(stringResource(if (skipped) R.string.duplicates_remove_extras else R.string.duplicates_keep_all))
                }
            }
            group.copies.forEach { copy ->
                CopyRow(
                    copy = copy,
                    kept = copy.path !in removals,
                    onToggle = { onToggle(copy.path) },
                    onPreferFolder = { onPreferFolder(copy.parentPath) }
                )
            }
        }
    }
}

/** Quick picks for every group at once; choosing one clears per-group choices. */
@Composable
private fun RuleChips(rule: KeepRule, onRule: (KeepRule) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(KeepRule.entries) { option ->
            FilterChip(
                selected = option == rule,
                onClick = { onRule(option) },
                label = { Text(stringResource(option.label)) },
                leadingIcon = if (option == rule) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
                shape = ExpressiveShapes.Pill
            )
        }
    }
}

/** How the copy to keep is picked in a group nobody has chosen for. */
private enum class KeepRule(@StringRes val label: Int) {
    /** [DuplicateFinder]'s order: Camera or the user's folders over Download and chats, then the oldest. */
    SMART(R.string.duplicates_rule_smart),
    NEWEST(R.string.duplicates_rule_newest),
    OLDEST(R.string.duplicates_rule_oldest),
    /** Nothing removed until the user picks copies. */
    NONE(R.string.duplicates_rule_none);

    fun removals(group: DuplicateGroup): Set<String> {
        val keep = when (this) {
            SMART -> group.copies.first()
            NEWEST -> group.copies.maxBy { it.modifiedAt }
            OLDEST -> group.copies.minBy { it.modifiedAt }
            NONE -> return emptySet()
        }
        return group.copies.filter { it.path != keep.path }.mapTo(HashSet()) { it.path }
    }
}

/** A group's id across recompositions: its first copy, the one [DuplicateFinder] would keep. */
private val DuplicateGroup.id: String get() = copies.first().path

/** The file's preview on top of two tilted cards, with a ×N badge. Tap to open the file. */
@Composable
private fun CopyStack(item: FileUiItem, count: Int, colors: AccentColors, onOpen: () -> Unit) {
    Box(
        Modifier
            .size(width = 68.dp, height = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.action_open), onClick = onOpen)
    ) {
        Box(Modifier.offset(12.dp, 2.dp).size(52.dp).rotate(10f).clip(RoundedCornerShape(16.dp)).background(colors.strong.copy(alpha = 0.35f)))
        Box(Modifier.offset(6.dp, 4.dp).size(52.dp).rotate(-6f).clip(RoundedCornerShape(16.dp)).background(colors.strong.copy(alpha = 0.6f)))
        FileThumbnail(
            item = item,
            shape = RoundedCornerShape(16.dp),
            iconSize = 24.dp,
            previewWidthPx = 156,
            modifier = Modifier.offset(2.dp, 8.dp).size(52.dp)
        )
        Text(
            "×$count",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onStrong,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clip(ExpressiveShapes.Pill)
                .background(colors.strong)
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

/**
 * One copy: a green star cookie when kept, a red cross when it goes. Tap to switch; long-press to
 * keep the copies in this folder in every group.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyRow(copy: FoundFile, kept: Boolean, onToggle: () -> Unit, onPreferFolder: () -> Unit) {
    val context = LocalContext.current
    val green = Accent.GREEN.colors()
    val error = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (kept) 24.dp else 16.dp)
    Surface(
        shape = shape,
        color = if (kept) green.container.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .combinedClickable(
                role = Role.Checkbox,
                onClickLabel = stringResource(if (kept) R.string.duplicates_remove else R.string.duplicates_keep),
                onLongClickLabel = stringResource(R.string.duplicates_prefer_folder_action),
                onClick = onToggle,
                onLongClick = onPreferFolder
            )
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kept) {
                ShapedIcon(Icons.Rounded.Star, ExpressiveShapes.Cookie, green.strong, green.onStrong, size = 32.dp, iconSize = 16.dp)
            } else {
                ShapedIcon(Icons.Rounded.Close, ExpressiveShapes.Pill, error.errorContainer, error.onErrorContainer, size = 32.dp, iconSize = 16.dp)
            }
            Column(Modifier.weight(1f)) {
                val folder = remember(copy.parentPath) { Storage.displayPath(context, copy.parentPath) }
                Text(
                    folder,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(formatModified(copy.modifiedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(if (kept) R.string.duplicates_keep else R.string.duplicates_remove).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (kept) green.strong else error.error
            )
        }
    }
}
