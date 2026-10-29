package com.navaneeth.filer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import com.navaneeth.filer.ui.components.SelectionEffects
import com.navaneeth.filer.ui.components.SelectionTopBar
import com.navaneeth.filer.ui.components.rememberSelection
import com.navaneeth.filer.ui.util.rememberHaptics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.components.BoldLargeTopBar
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.FileActionsSheet
import com.navaneeth.filer.ui.components.FileGridItem
import com.navaneeth.filer.ui.components.FileListItem
import com.navaneeth.filer.ui.components.ViewActions
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel
import java.util.Calendar
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import com.navaneeth.filer.ui.components.LoadingState
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RecentBucket(val label: Int) {
    TODAY(R.string.recent_today),
    YESTERDAY(R.string.recent_yesterday),
    THIS_WEEK(R.string.recent_this_week),
    THIS_MONTH(R.string.recent_this_month),
    EARLIER(R.string.recent_earlier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentScreen(
    library: LibraryViewModel,
    onBack: () -> Unit,
    /** Start of one day (local midnight, millis): show just that day's files, by part of the day. */
    day: Long? = null
) {
    val context = LocalContext.current
    val recent by library.recentFiles.collectAsState()
    // A day is read from MediaStore by itself, so a busy week isn't cut off by the Recent limit.
    val dayFiles by remember(day) { day?.let(library::filesOn) ?: flowOf(null) }.collectAsState(initial = null)
    val files = if (day != null) dayFiles.orEmpty() else recent
    val loading = day != null && dayFiles == null
    val open = rememberFileOpener(files)
    val haptics = rememberHaptics()
    var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }
    val selection = rememberSelection()
    SelectionEffects(selection, files)

    fun onItemClick(item: FileUiItem) {
        if (selection.active) { haptics.tick(); selection.toggle(item) } else open(item)
    }

    val grid by library.recentGridView.collectAsState()
    // Sections as (label, files): Today/Yesterday/… for Recent, Morning/Afternoon/… for one day.
    val grouped = remember(files, day) {
        if (day == null) {
            files.groupBy { bucketOf(it.modifiedAt) }.toSortedMap().map { (bucket, items) -> bucket.label to items }
        } else {
            files.groupBy { partOfDay(it.modifiedAt) }.toSortedMap().map { (part, items) -> part.label to items }
        }
    }
    val title = remember(day) {
        day?.let {
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(it))
        }
    }
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    val headerColor = MaterialTheme.colorScheme.surface
    val barSpace = LocalBottomBarSpace.current
    // A large bold title that collapses on scroll; selection mode keeps a small pinned bar.
    val largeBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val pinnedBar = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scrollBehavior = if (selection.active) pinnedBar else largeBar

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.active) SelectionTopBar(selection, files, scrollBehavior) else BoldLargeTopBar(
                title = title ?: stringResource(R.string.tab_recent),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = { ViewActions(grid = grid, onGridChange = library::setRecentGridView) }
            )
        }
    ) { padding ->
    Box(Modifier.fillMaxSize()) {
        if (loading) {
            LoadingState(Modifier.padding(top = padding.calculateTopPadding(), bottom = barSpace))
        } else if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.recent_empty_title),
                body = stringResource(R.string.recent_empty_body),
                modifier = Modifier.padding(top = padding.calculateTopPadding(), bottom = barSpace)
            )
        } else {
            Column(Modifier.padding(top = padding.calculateTopPadding())) {
                AnimatedContent(
                    targetState = grid,
                    transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
                    label = "recentLayout"
                ) { asGrid ->
                    if (asGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 104.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = barSpace + 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            grouped.forEach { (label, items) ->
                                item(key = "h_$label", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = stringResource(label),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                                    )
                                }
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
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = barSpace + 24.dp)
                        ) {
                            grouped.forEach { (label, items) ->
                                stickyHeader(key = "h_$label") {
                                    Text(
                                        text = stringResource(label),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(headerColor)
                                            .padding(horizontal = 20.dp, vertical = 10.dp)
                                    )
                                }
                                items(items, key = { it.uri }) { item ->
                                    FileListItem(
                                        item = item,
                                        supportingText = listOfNotNull(
                                            item.location,
                                            if (day != null) timeFormat.format(Date(item.modifiedAt)) else formatModified(item.modifiedAt)
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
                }
            }
        }

    }
    }

    sheetItem?.let { item -> FileActionsSheet(item = item, onDismiss = { sheetItem = null }) }
}

/** Parts of a day in display order, latest first like the files in them. */
private enum class DayPart(val label: Int) {
    NIGHT(R.string.day_night),
    EVENING(R.string.day_evening),
    AFTERNOON(R.string.day_afternoon),
    MORNING(R.string.day_morning),
    EARLY(R.string.day_early)
}

private fun partOfDay(millis: Long): DayPart {
    val hour = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
    return when {
        hour >= 21 -> DayPart.NIGHT
        hour >= 17 -> DayPart.EVENING
        hour >= 12 -> DayPart.AFTERNOON
        hour >= 5 -> DayPart.MORNING
        else -> DayPart.EARLY
    }
}

private fun bucketOf(millis: Long): RecentBucket {
    val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val day = 24L * 60 * 60 * 1000
    val today = startOfToday.timeInMillis
    val startOfWeek = (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.timeInMillis
    val startOfMonth = (startOfToday.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    return when {
        millis >= today -> RecentBucket.TODAY
        millis >= today - day -> RecentBucket.YESTERDAY
        millis >= startOfWeek -> RecentBucket.THIS_WEEK
        millis >= startOfMonth -> RecentBucket.THIS_MONTH
        else -> RecentBucket.EARLIER
    }
}
