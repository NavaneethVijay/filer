package com.navaneeth.filer.ui.screens.storage

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.CleanupKind
import com.navaneeth.filer.data.analysis.StorageInsights
import com.navaneeth.filer.data.analysis.VolumeReport
import com.navaneeth.filer.ui.components.ExpressiveLoader
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.screens.ConfirmDialog
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import com.navaneeth.filer.ui.viewmodel.StorageViewModel
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * The Storage tab: what's using space, what could go, and how it's changing.
 * [onOpenReview] with null opens everything to clean up in one list.
 */
@Composable
fun StorageScreen(
    storage: StorageViewModel,
    onOpenReview: (CleanupKind?) -> Unit,
    onOpenInsights: () -> Unit,
    onOpenSpaceMap: (path: String?) -> Unit,
    onOpenRecycleBin: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val state by storage.state.collectAsState()
    val report by storage.report.collectAsState()
    val volumeIndex by storage.volumeIndex.collectAsState()
    val binBytes by storage.binBytes.collectAsState()
    var confirmEmptyBin by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val barSpace = LocalBottomBarSpace.current
    val scope = rememberCoroutineScope()
    val scanningLabel = stringResource(R.string.storage_scanning_first)

    LaunchedEffect(Unit) { storage.ensureScanned() }

    fun openKind(kind: CleanupKind) {
        haptics.tick()
        onOpenReview(kind)
    }

    Scaffold(
        topBar = {
            StorageTopBar(stringResource(R.string.storage_overview_title), listState) {
                // While scanning the button turns into a loader; the progress card is at the top.
                IconButton(
                    onClick = {
                        haptics.confirm()
                        storage.rescan()
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    enabled = state.stage == null
                ) {
                    if (state.stage != null) {
                        ExpressiveLoader(size = 24.dp, modifier = Modifier.semantics { contentDescription = scanningLabel })
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_scan_again))
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = barSpace + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "header") {
                ScreenHeader(stringResource(R.string.storage_overview_title), report?.let { scannedLine(it) })
            }

            if (state.reports.size > 1) {
                item(key = "volumes") {
                    VolumeToggle(state.reports.map { it.volume.label }, volumeIndex, onSelect = { haptics.tick(); storage.selectVolume(it) })
                }
            }

            val current = report
            if (current == null) {
                item(key = "first_scan") { FirstScanCard(state.stage) }
                return@LazyColumn
            }
            state.stage?.let { stage -> item(key = "progress") { ScanProgressCard(stage) } }

            overview(
                report = current,
                binBytes = binBytes,
                onOpenKind = ::openKind,
                onReviewAll = { haptics.tick(); onOpenReview(null) },
                onOpenInsights = onOpenInsights,
                onOpenSpaceMap = onOpenSpaceMap,
                onOpenRecycleBin = onOpenRecycleBin,
                onEmptyBin = { confirmEmptyBin = true },
                onOpenSystemStorage = { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            )
        }
    }

    if (confirmEmptyBin) {
        ConfirmDialog(
            title = stringResource(R.string.empty_bin_title),
            body = stringResource(R.string.empty_bin_body),
            confirm = stringResource(R.string.action_empty_bin),
            onConfirm = { haptics.confirm(); fileOps.emptyTrash(); confirmEmptyBin = false },
            onDismiss = { confirmEmptyBin = false }
        )
    }
}

/** Everything below the header once a volume has been walked. */
private fun LazyListScope.overview(
    report: VolumeReport,
    binBytes: Long,
    onOpenKind: (CleanupKind) -> Unit,
    onReviewAll: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenSpaceMap: (String?) -> Unit,
    onOpenRecycleBin: () -> Unit,
    onEmptyBin: () -> Unit,
    onOpenSystemStorage: () -> Unit
) {
    item(key = "usage") { UsageHero(report, onOpenSystemStorage) }

    item(key = "free_up") {
        if (report.clutterBytes > 0) FreeUpHero(report.clutterBytes, onReviewAll) else AllCleanCard()
    }

    // What's worth knowing, as a carousel; each card opens its cleanup list (or Insights).
    val highlights = StorageInsights.highlights(report)
    item(key = "insights_title") {
        SectionTitle(stringResource(R.string.storage_insights)) {
            FilledTonalButton(onClick = onOpenInsights) { Text(stringResource(R.string.action_see_all)) }
        }
    }
    if (highlights.isNotEmpty()) {
        item(key = "insights") {
            HighlightCarousel(highlights, onOpen = { highlight -> highlight.kind?.let(onOpenKind) ?: onOpenInsights() })
        }
    }

    // Every kind with files, each opening its own review list.
    if (CleanupKind.entries.any { report.bytes(it) > 0 }) {
        item(key = "cleanup_title") {
            SectionTitle(stringResource(R.string.storage_clean_up)) {
                FilledTonalButton(onClick = onReviewAll) { Text(stringResource(R.string.action_review_all)) }
            }
        }
        item(key = "cleanup") { CleanupGrid(report, onOpenKind) }
    }
    if (binBytes > 0) {
        item(key = "bin") { RecycleBinRow(binBytes, onOpenRecycleBin, onEmptyBin) }
    }

    item(key = "folders_title") { SectionTitle(stringResource(R.string.storage_biggest_folders)) }
    item(key = "folders") {
        BiggestFoldersCard(biggestFolders(report.root), onOpenFolder = onOpenSpaceMap, onOpenMap = { onOpenSpaceMap(null) })
    }
}

/** "Scanned 2 minutes ago · 148,203 files". */
@Composable
private fun scannedLine(report: VolumeReport): String {
    val ago = DateUtils.getRelativeTimeSpanString(report.scannedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val count = NumberFormat.getIntegerInstance().format(report.fileCount)
    return stringResource(R.string.storage_scanned, ago) + " · " + pluralStringResource(R.plurals.storage_files, report.fileCount, count)
}
