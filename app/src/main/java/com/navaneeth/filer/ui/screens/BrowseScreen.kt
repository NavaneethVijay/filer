package com.navaneeth.filer.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.ai.TagCount
import com.navaneeth.filer.data.analysis.CleanupKind
import com.navaneeth.filer.data.media.MediaCategory
import com.navaneeth.filer.data.model.DownloadState
import com.navaneeth.filer.data.update.UpdateStatus
import com.navaneeth.filer.data.storage.StorageVolumeInfo
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.FileActionsSheet
import com.navaneeth.filer.ui.components.FileThumbnail
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.ShapedIcon
import com.navaneeth.filer.ui.components.WavyProgressIndicator
import com.navaneeth.filer.ui.components.SmartSearchBanner
import com.navaneeth.filer.ui.components.accent
import com.navaneeth.filer.ui.components.chipShape
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.icon
import com.navaneeth.filer.ui.components.isBusy
import com.navaneeth.filer.ui.components.label
import com.navaneeth.filer.ui.components.rememberSmartSearchEnabler
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.screens.storage.look
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.AiViewModel
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel
import com.navaneeth.filer.ui.viewmodel.StorageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Browse home: what Filer can do up front (a search hero with example searches), then the
 * phone's own story (new today, earlier this week, something worth cleaning), storage, then the
 * ways in (file types, kept-safe places, tags).
 */
@Composable
fun BrowseScreen(
    library: LibraryViewModel,
    ai: AiViewModel,
    storage: StorageViewModel,
    onOpenTag: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onManageTags: () -> Unit,
    onOpenSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onOpenFolder: (path: String) -> Unit,
    onOpenCollection: (MediaCategory) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenLockedFolder: () -> Unit,
    onOpenReview: (CleanupKind) -> Unit,
    onSeeAllRecent: () -> Unit,
    /** One day's files; the argument is the day's start (local midnight, millis). */
    onOpenDay: (Long) -> Unit
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current

    val volumes by library.volumes.collectAsState()
    val recent by library.recentFiles.collectAsState()
    val counts by library.categoryCounts.collectAsState()
    val downloadState by library.downloadState.collectAsState()
    val bannerDismissed by library.bannerDismissed.collectAsState()
    val report by storage.report.collectAsState()
    val tags by ai.tagCounts.collectAsState()
    val refreshing by library.refreshing.collectAsState()

    var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }
    val enableSmartSearch = rememberSmartSearchEnabler(library)
    val smartReadyMessage = stringResource(R.string.smart_search_ready)

    // Grouping a few hundred files by day is quick, but keep it off the main thread anyway.
    val timeline by produceState(Timeline.EMPTY, recent) {
        value = withContext(Dispatchers.Default) { Timeline.of(recent, System.currentTimeMillis()) }
    }
    val fresh = timeline.today.ifEmpty { recent.take(LATEST_COUNT) }
    val openFresh = rememberFileOpener(fresh)
    // The clutter kind that frees the most, once the Storage scan has run.
    val worthALook = remember(report) {
        report?.let { r ->
            CleanupKind.entries.filter { it.isClutter }
                .map { it to r.bytes(it) }
                .filter { (_, bytes) -> bytes >= WORTH_A_LOOK_BYTES }
                .maxByOrNull { (_, bytes) -> bytes }
        }
    }

    // Celebrate once when a download this session finishes.
    var sawDownload by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(downloadState) {
        if (downloadState.isBusy) sawDownload = true
        if (downloadState is DownloadState.Ready && sawDownload) {
            sawDownload = false
            haptics.confirm()
            snackbar.showSnackbar(smartReadyMessage)
        }
    }

    val showBanner = downloadState !is DownloadState.Ready && (!bannerDismissed || downloadState.isBusy)
    val barSpace = LocalBottomBarSpace.current

    // Surface (not a plain background) so text picks up the right content color in dark theme.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Pull down to catch up with files other apps changed without telling the media index.
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { haptics.confirm(); library.refresh() },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                )
            }
        ) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = barSpace + 32.dp)
        ) {
            item(key = "hero") {
                BrowseHero(
                    onSearch = { haptics.tick(); onOpenSearch() },
                    onSettings = { haptics.tick(); onOpenSettings() },
                    onExample = { haptics.tick(); onSearchFor(it) }
                )
            }
            item(key = "banner") {
                AnimatedVisibility(
                    visible = showBanner,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(300))
                ) {
                    SmartSearchBanner(
                        state = downloadState,
                        onEnable = enableSmartSearch,
                        onPause = library::pauseSmartSearchDownload,
                        onDismiss = { haptics.tick(); library.dismissSmartSearchBanner() },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            item(key = "updateBanner") {
                val updateStatus by library.updateStatus.collectAsState()
                val scope = rememberCoroutineScope()
                AnimatedVisibility(
                    visible = updateStatus is UpdateStatus.Available || updateStatus is UpdateStatus.ReadyToInstall || updateStatus is UpdateStatus.Downloading,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(300))
                ) {
                    UpdateBanner(
                        status = updateStatus,
                        onUpdate = {
                            when (val s = updateStatus) {
                                is UpdateStatus.Available -> scope.launch { library.updateManager.downloadUpdate(s.release) }
                                is UpdateStatus.ReadyToInstall -> library.updateManager.installUpdate(context, s.apkFile)
                                else -> Unit
                            }
                        },
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (fresh.isNotEmpty()) {
                item(key = "fresh_header") {
                    HomeHeader(
                        title = stringResource(if (timeline.today.isNotEmpty()) R.string.browse_new_today else R.string.browse_latest),
                        action = { TextButton(onClick = { haptics.tick(); onSeeAllRecent() }) { Text(stringResource(R.string.action_see_all)) } }
                    )
                }
                item(key = "fresh") {
                    FreshRow(
                        items = fresh,
                        today = timeline.today.isNotEmpty(),
                        onOpen = openFresh,
                        onActions = { sheetItem = it }
                    )
                }
            }

            if (timeline.week.isNotEmpty()) {
                item(key = "week_header") { HomeHeader(stringResource(R.string.browse_this_week)) }
                item(key = "week") {
                    WeekGroup(
                        days = timeline.week,
                        onOpenDay = { day ->
                            haptics.tick()
                            if (day.files.size == 1) openFresh(day.files.first()) else onOpenDay(day.dayStart)
                        }
                    )
                }
            }

            worthALook?.let { (kind, bytes) ->
                item(key = "worth") {
                    WorthALook(kind, bytes, onClick = { haptics.tick(); onOpenReview(kind) })
                }
            }

            item(key = "storage_header") { HomeHeader(stringResource(R.string.section_storage)) }
            item(key = "storage") { StorageGroup(volumes = volumes, onOpen = { onOpenFolder(it.root.path) }) }

            item(key = "types_header") { HomeHeader(stringResource(R.string.browse_your_files)) }
            item(key = "types") { TypeShortcuts(counts, onOpen = { haptics.tick(); onOpenCollection(it) }) }
            item(key = "kept") {
                KeptSafe(
                    onFavorites = { haptics.tick(); onOpenFavorites() },
                    onLocked = { haptics.tick(); onOpenLockedFolder() },
                    onBin = { haptics.tick(); onOpenRecycleBin() }
                )
            }

            if (tags.isNotEmpty()) {
                item(key = "tags_header") {
                    HomeHeader(
                        title = stringResource(R.string.section_tags),
                        action = { TextButton(onClick = { haptics.tick(); onManageTags() }) { Text(stringResource(R.string.action_see_all)) } }
                    )
                }
                item(key = "tags") { TagChips(tags, onOpen = { haptics.tick(); onOpenTag(it) }) }
            }

        }
        }
    }

    sheetItem?.let { item -> FileActionsSheet(item = item, onDismiss = { sheetItem = null }) }
}

/** Files from today, and from each earlier day of the past week (newest day first). */
private class Timeline(val today: List<FileUiItem>, val week: List<DayFiles>) {
    companion object {
        val EMPTY = Timeline(emptyList(), emptyList())

        fun of(files: List<FileUiItem>, now: Long): Timeline {
            val startOfToday = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = files.filter { it.modifiedAt >= startOfToday }
            val week = (1..WEEK_DAYS).mapNotNull { back ->
                val start = Calendar.getInstance().apply { timeInMillis = startOfToday; add(Calendar.DAY_OF_YEAR, -back) }.timeInMillis
                val end = Calendar.getInstance().apply { timeInMillis = startOfToday; add(Calendar.DAY_OF_YEAR, -back + 1) }.timeInMillis
                files.filter { it.modifiedAt in start until end }.takeIf { it.isNotEmpty() }?.let { DayFiles(start, it) }
            }
            return Timeline(today.take(FRESH_MAX), week)
        }
    }
}

private class DayFiles(val dayStart: Long, val files: List<FileUiItem>)

private const val WEEK_DAYS = 6
private const val FRESH_MAX = 12
private const val LATEST_COUNT = 8
private const val WORTH_A_LOOK_BYTES = 100L * 1024 * 1024
private val TileGap = 10.dp

/** A section title on Browse, with room above it so the sections read as separate blocks. */
@Composable
private fun HomeHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = ExpressiveType.Section,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        action?.invoke()
    }
}

/**
 * Light-blue hero: today's date, the app's promise, a search field (opens search) and example
 * searches that run as soon as they're tapped.
 */
@Composable
private fun BrowseHero(onSearch: () -> Unit, onSettings: () -> Unit, onExample: (String) -> Unit) {
    val context = LocalContext.current
    val appIcon = remember(context) {
        runCatching {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(context.packageName)
            val sidePx = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
            val bitmap = Bitmap.createBitmap(sidePx, sidePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sidePx, sidePx)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    val blue = Accent.BLUE.colors()
    val date = remember {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
    }
    val examples = listOf(
        stringResource(R.string.browse_example_bills),
        stringResource(R.string.browse_example_photo_text),
        stringResource(R.string.browse_example_pdfs)
    )
    Surface(
        shape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
        color = blue.container,
        contentColor = blue.onContainer
    ) {
        Box {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp, y = (-24).dp)
                    .size(200.dp)
                    .clip(ExpressiveShapes.Cookie)
                    .background(blue.strong.copy(alpha = 0.10f))
            )
            // The examples scroll edge to edge, so only the rest of the hero is inset.
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 22.dp)
            ) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    // App icon on the left, Settings on the right (it isn't a tab).
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        appIcon?.let { iconBitmap ->
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        FilledTonalIconButton(
                            onClick = onSettings,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f),
                                contentColor = blue.onContainer
                            )
                        ) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tab_settings))
                        }
                    }
                    Text(date, style = ExpressiveType.Overline, color = blue.strong)
                    Surface(
                        onClick = onSearch,
                        shape = ExpressiveShapes.Pill,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                            Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 14.dp)
                ) {
                    items(examples) { example ->
                        Surface(
                            onClick = { onExample(example) },
                            shape = ExpressiveShapes.Pill,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, blue.onContainer.copy(alpha = 0.3f))
                        ) {
                            Text(
                                example,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Today's files (or the latest ones) as tall previews with the time over a scrim. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FreshRow(items: List<FileUiItem>, today: Boolean, onOpen: (FileUiItem) -> Unit, onActions: (FileUiItem) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.uri }) { item ->
            Box(
                Modifier
                    .width(128.dp)
                    .height(168.dp)
                    .clip(RoundedCornerShape(24.dp))
                    // No list here to select in, so long-press opens the actions sheet.
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { onOpen(item) },
                        onLongClick = { haptics.longPress(); onActions(item) }
                    )
                    .semantics { contentDescription = item.name }
            ) {
                FileThumbnail(item = item, modifier = Modifier.fillMaxSize(), shape = RectangleShape, iconSize = 40.dp, previewWidthPx = 384)
                Text(
                    if (today) timeFormat.format(Date(item.modifiedAt)) else formatModified(item.modifiedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                        .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 10.dp)
                )
            }
        }
    }
}

/** One row per earlier day this week: the weekday, stacked previews and what came in. */
@Composable
private fun WeekGroup(days: List<DayFiles>, onOpenDay: (DayFiles) -> Unit) {
    val weekday = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            days.forEach { day ->
                Surface(onClick = { onOpenDay(day) }, color = Color.Transparent) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            weekday.format(Date(day.dayStart)).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(min = 40.dp)
                        )
                        PreviewStack(day.files.take(3))
                        Text(
                            daySummary(day.files),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Up to three previews overlapping, each ringed in the card colour so the stack reads. */
@Composable
private fun PreviewStack(files: List<FileUiItem>) {
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
        files.forEach { file ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(ring)
                    .padding(2.dp)
            ) {
                FileThumbnail(item = file, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(11.dp), iconSize = 18.dp, previewWidthPx = 120)
            }
        }
    }
}

/** "12 photos", "3 videos", the file's name when there's one, otherwise "5 files". */
@Composable
private fun daySummary(files: List<FileUiItem>): String = when {
    files.size == 1 -> files.first().name
    files.all { it.kind == FileKind.IMAGE } -> pluralStringResource(R.plurals.browse_day_photos, files.size, files.size)
    files.all { it.kind == FileKind.VIDEO } -> pluralStringResource(R.plurals.browse_day_videos, files.size, files.size)
    else -> pluralStringResource(R.plurals.browse_day_files, files.size, files.size)
}

/** The clutter that frees the most, from the Storage scan; opens its review. */
@Composable
private fun WorthALook(kind: CleanupKind, bytes: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val look = kind.look
    val colors = look.accent.colors()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = colors.container,
        contentColor = colors.onContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShapedIcon(look.icon, look.shape, colors.strong, colors.onStrong, size = 44.dp, iconSize = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(look.title) + " · " + formatSize(context, bytes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(stringResource(R.string.browse_worth_a_look), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** File types as large shaped icons in a 3-column grid, with a small grey count under the name. */
@Composable
private fun TypeShortcuts(counts: Map<MediaCategory, Int>, onOpen: (MediaCategory) -> Unit) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MediaCategory.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { category ->
                    val colors = category.accent.colors()
                    val count = counts[category] ?: 0
                    val label = stringResource(category.label)
                    val description = pluralStringResource(R.plurals.file_count, count, count)
                    Surface(
                        onClick = { onOpen(category) },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .semantics(mergeDescendants = true) { contentDescription = "$label, $description" }
                    ) {
                        Column(
                            Modifier.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ShapedIcon(category.icon, category.chipShape, colors.container, colors.onContainer, size = 64.dp, iconSize = 28.dp)
                            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text(
                                numbers.format(count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Favourites, Locked folder and Recycle bin as three pills. */
@Composable
private fun KeptSafe(onFavorites: () -> Unit, onLocked: () -> Unit, onBin: () -> Unit) {
    @Composable
    fun Pill(icon: ImageVector, tint: Color, label: Int, onClick: () -> Unit, modifier: Modifier) {
        Surface(
            onClick = onClick,
            shape = ExpressiveShapes.Pill,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.height(52.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Pill(Icons.Rounded.Star, Accent.BLUE.colors().strong, R.string.browse_favorites_short, onFavorites, Modifier.weight(1f))
        Pill(Icons.Rounded.Lock, Accent.TEAL.colors().strong, R.string.browse_locked_short, onLocked, Modifier.weight(1f))
        Pill(Icons.Rounded.Delete, MaterialTheme.colorScheme.onSurfaceVariant, R.string.browse_bin_short, onBin, Modifier.weight(1f))
    }
}

/** Internal storage and any SD card: used space large, a thick bar and what is free. */
@Composable
private fun StorageGroup(volumes: List<StorageVolumeInfo>, onOpen: (StorageVolumeInfo) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(TileGap)) {
        volumes.forEachIndexed { index, volume ->
            val used = (volume.totalBytes - volume.freeBytes).coerceAtLeast(0)
            val fraction = if (volume.totalBytes > 0) used / volume.totalBytes.toFloat() else 0f
            val accent = if (volume.isPrimary) Accent.BLUE else Accent.TEAL
            val colors = accent.colors()
            val green = Accent.GREEN.colors()
            Surface(
                onClick = { onOpen(volume) },
                shape = if (index % 2 == 0) RoundedCornerShape(32.dp) else RoundedCornerShape(32.dp, 32.dp, 32.dp, 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ShapedIcon(
                            if (volume.isPrimary) Icons.Rounded.PhoneAndroid else Icons.Rounded.SdCard,
                            if (volume.isPrimary) ExpressiveShapes.Squircle else ExpressiveShapes.Cookie,
                            colors.container,
                            colors.onContainer,
                            size = 44.dp,
                            iconSize = 22.dp
                        )
                        Text(
                            volume.label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f)) {
                            Text(formatSize(context, used), style = ExpressiveType.Number, modifier = Modifier.alignByBaseline())
                            Text(
                                stringResource(R.string.storage_used_of, formatSize(context, volume.totalBytes)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.alignByBaseline().padding(start = 8.dp)
                            )
                        }
                        Text(
                            stringResource(R.string.storage_free_badge, formatSize(context, volume.freeBytes)),
                            style = MaterialTheme.typography.labelLarge,
                            color = green.onContainer,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(ExpressiveShapes.Pill)
                                .background(green.container)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    WavyProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.strong,
                        trackColor = colors.strong.copy(alpha = 0.18f),
                        thickness = 8.dp
                    )
                }
            }
        }
    }
}

/** The most used tags in one scrollable row; the rest are on the Tags screen (See all). */
@Composable
private fun TagChips(tags: List<TagCount>, onOpen: (String) -> Unit) {
    val accents = listOf(Accent.TEAL, Accent.BLUE, Accent.SKY)
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tags.take(MAX_TAG_CHIPS).size) { index ->
            val tag = tags[index]
            // The three most used get an accent; the rest stay neutral.
            val colors = if (index < accents.size) accents[index].colors() else null
            Surface(
                onClick = { onOpen(tag.tag) },
                shape = ExpressiveShapes.Pill,
                color = colors?.container ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = colors?.onContainer ?: MaterialTheme.colorScheme.onSurface
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#${tag.tag}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(6.dp))
                    Text(tag.count.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.75f))
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    status: UpdateStatus,
    onUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberHaptics()
    val colors = Accent.BLUE.colors()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = { haptics.tick(); onOpenSettings() }),
        color = colors.container,
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShapedIcon(
                    icon = Icons.Rounded.SystemUpdate,
                    shape = ExpressiveShapes.Squircle,
                    container = colors.strong.copy(alpha = 0.12f),
                    content = colors.strong,
                    size = 40.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val title = when (status) {
                        is UpdateStatus.Available -> stringResource(R.string.updates_available_title, status.release.versionName)
                        is UpdateStatus.ReadyToInstall -> stringResource(R.string.updates_ready_title)
                        is UpdateStatus.Downloading -> stringResource(R.string.updates_downloading, (status.progress * 100).toInt())
                        else -> stringResource(R.string.updates_title)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val sub = when (status) {
                        is UpdateStatus.Available -> status.release.title.ifBlank { stringResource(R.string.updates_auto_check_body) }
                        is UpdateStatus.ReadyToInstall -> stringResource(R.string.updates_ready_body, status.release.versionName)
                        else -> null
                    }
                    if (sub != null) {
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                when (status) {
                    is UpdateStatus.Available -> {
                        Button(onClick = { haptics.confirm(); onUpdate() }) {
                            Text(stringResource(R.string.updates_action_update))
                        }
                    }
                    is UpdateStatus.ReadyToInstall -> {
                        Button(onClick = { haptics.confirm(); onUpdate() }) {
                            Text(stringResource(R.string.updates_action_install))
                        }
                    }
                    else -> Unit
                }
            }
            if (status is UpdateStatus.Downloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private const val MAX_TAG_CHIPS = 6
