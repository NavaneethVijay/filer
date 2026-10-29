package com.navaneeth.filer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LabelOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
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
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.FileActionsSheet
import com.navaneeth.filer.ui.components.FileListItem
import com.navaneeth.filer.ui.components.LoadingState
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.SelectionEffects
import com.navaneeth.filer.ui.components.SelectionTopBar
import com.navaneeth.filer.ui.components.rememberSelection
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.viewmodel.AiViewModel

/** Every file with one tag, newest first. */
@Composable
fun TagFilesScreen(tag: String, ai: AiViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val items by remember(tag) { ai.taggedFiles(tag) }.collectAsState(initial = null)
    val open = rememberFileOpener(items.orEmpty())
    val haptics = rememberHaptics()
    var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }
    val selection = rememberSelection()
    SelectionEffects(selection, items)
    // A large bold title that collapses on scroll; selection mode keeps a small pinned bar.
    val largeBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val pinnedBar = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scrollBehavior = if (selection.active) pinnedBar else largeBar
    val barSpace = LocalBottomBarSpace.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.active) {
                SelectionTopBar(selection, items.orEmpty(), scrollBehavior)
            } else {
                BoldLargeTopBar(title = "#$tag", onBack = onBack, scrollBehavior = scrollBehavior)
            }
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            val list = items
            when {
                list == null -> LoadingState(Modifier.padding(bottom = barSpace))
                list.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Rounded.LabelOff,
                    title = stringResource(R.string.tag_empty_title),
                    body = stringResource(R.string.tag_empty_body),
                    modifier = Modifier.padding(bottom = barSpace)
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = barSpace + 24.dp)) {
                    item(key = "count") {
                        Text(
                            pluralStringResource(R.plurals.tag_files, list.size, list.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                        )
                    }
                    items(list, key = { it.uri }) { item ->
                        FileListItem(
                            item = item,
                            supportingText = listOfNotNull(item.location, formatSize(context, item.sizeBytes), formatModified(item.modifiedAt))
                                .filter { it.isNotEmpty() }
                                .joinToString(" · "),
                            onClick = { if (selection.active) { haptics.tick(); selection.toggle(item) } else open(item) },
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
    }

    sheetItem?.let { FileActionsSheet(item = it, onDismiss = { sheetItem = null }) }
}
