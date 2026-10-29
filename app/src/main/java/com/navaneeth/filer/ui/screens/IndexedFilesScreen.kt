package com.navaneeth.filer.ui.screens

import com.navaneeth.filer.ui.components.indexProblemText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.FileListItem
import com.navaneeth.filer.ui.components.IndexStatusChip
import com.navaneeth.filer.ui.components.label
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.IndexViewModel

@Composable
fun IndexedFilesScreen(
    viewModel: IndexViewModel,
    initialFilter: IndexStatus?,
    onBack: () -> Unit,
    onOpenDetails: (Long) -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val files by viewModel.files.collectAsState()
    val counts by viewModel.counts.collectAsState()
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var query by rememberSaveable { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val errorColor = MaterialTheme.colorScheme.error

    val visible = remember(files, filter, query) {
        files.orEmpty().filter { file ->
            (filter == null || file.status == filter) &&
                (query.isBlank() || file.item.name.contains(query.trim(), ignoreCase = true))
        }
    }
    val chips = listOf(
        null to counts.total,
        IndexStatus.INDEXED to counts.ready,
        IndexStatus.PENDING to counts.waiting,
        IndexStatus.FAILED to counts.failed,
        IndexStatus.SKIPPED to counts.skipped
    ).filter { (status, count) -> status == null || count > 0 || status == filter }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.indexed_files)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.filter_by_name)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chips, key = { it.first?.name ?: "ALL" }) { (status, count) ->
                        val selected = status == filter
                        FilterChip(
                            selected = selected,
                            onClick = { haptics.tick(); filter = status },
                            label = { Text("${stringResource(status?.label ?: R.string.filter_all)} $count") },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding())) {
            when {
                files == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                visible.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = stringResource(R.string.indexed_empty_title),
                    body = stringResource(R.string.indexed_empty_body)
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
                    items(visible, key = { it.item.uri }) { file ->
                        val id = file.item.fileId
                        FileListItem(
                            item = file.item,
                            supportingText = listOfNotNull(
                                file.item.location,
                                formatSize(context, file.item.sizeBytes),
                                formatModified(file.item.modifiedAt)
                            ).joinToString(" · "),
                            snippet = indexProblemText(file.error)?.takeIf { file.status != IndexStatus.INDEXED }?.let {
                                AnnotatedString(it, SpanStyle(color = errorColor))
                            },
                            onClick = { if (id != null) onOpenDetails(id) },
                            onMore = null,
                            trailingBadge = { IndexStatusChip(file.status) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}
