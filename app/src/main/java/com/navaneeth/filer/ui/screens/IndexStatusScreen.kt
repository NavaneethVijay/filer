package com.navaneeth.filer.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.rounded.Check
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveLoader
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.ShapedIcon
import com.navaneeth.filer.ui.components.WavyProgressIndicator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import com.navaneeth.filer.data.telemetry.SystemResourceUsage
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import androidx.compose.material.icons.rounded.DocumentScanner
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.data.model.StorageBreakdown
import com.navaneeth.filer.data.repository.IndexProgress
import com.navaneeth.filer.ui.components.GroupTitle
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup
import com.navaneeth.filer.ui.components.StatTile
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.formatRelative
import com.navaneeth.filer.ui.components.icon
import com.navaneeth.filer.ui.components.label
import com.navaneeth.filer.ui.util.formatTimeLeft
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.IndexCounts
import com.navaneeth.filer.ui.viewmodel.IndexViewModel
import com.navaneeth.filer.ui.viewmodel.KindStat

@Composable
fun IndexStatusScreen(
    viewModel: IndexViewModel,
    onBack: () -> Unit,
    onOpenFiles: (IndexStatus?) -> Unit
) {
    val haptics = rememberHaptics()
    val progress by viewModel.progress.collectAsState()
    val photoText by com.navaneeth.filer.data.worker.PhotoTextWorker.progress.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val storage by viewModel.storage.collectAsState()
    val kinds by viewModel.kindStats.collectAsState()
    val lastScan by viewModel.lastScanAt.collectAsState()
    val waitingForCharger by viewModel.rebuildWaitingForCharger.collectAsState()
    val resources by viewModel.resourceUsage.collectAsState()
    val engines by viewModel.engineCounts.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Sizes change as files are processed; refresh when a pass finishes.
    LaunchedEffect(progress.isRunning) { if (!progress.isRunning) viewModel.refreshStorage() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.index_status)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rebuild_index)) },
                                leadingIcon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                                onClick = { menu = false; confirmRebuild = true }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                StatusHero(
                    progress = progress,
                    counts = counts,
                    lastScan = lastScan,
                    waitingForCharger = waitingForCharger,
                    onScan = { haptics.confirm(); viewModel.scanNow() },
                    onPause = { haptics.tick(); viewModel.pause() },
                    onResume = { haptics.tick(); viewModel.resume() },
                    onStop = { haptics.tick(); viewModel.stop() }
                )
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        value = counts.ready.toString(),
                        label = stringResource(R.string.status_ready),
                        colors = IndexStatus.INDEXED.colors(),
                        onClick = { onOpenFiles(IndexStatus.INDEXED) },
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        value = counts.waiting.toString(),
                        label = stringResource(R.string.status_waiting),
                        colors = IndexStatus.PENDING.colors(),
                        onClick = { onOpenFiles(IndexStatus.PENDING) },
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        value = counts.failed.toString(),
                        label = stringResource(R.string.status_failed),
                        colors = if (counts.failed > 0) IndexStatus.FAILED.colors() else IndexStatus.SKIPPED.colors(),
                        onClick = { onOpenFiles(IndexStatus.FAILED) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingRow(
                        Icons.Rounded.FolderOpen,
                        stringResource(R.string.indexed_files),
                        subtitle = pluralStringResource(R.plurals.file_count, counts.total, counts.total),
                        onClick = { onOpenFiles(null) },
                        chevron = true
                    )
                }
            }
            // AI plugins: reading text in photos already on the phone (optional, while charging).
            if (photoText.running && photoText.total > 0) {
                item {
                    SettingsGroup {
                        SettingRow(
                            Icons.Rounded.DocumentScanner,
                            stringResource(R.string.index_photo_text_row),
                            subtitle = stringResource(R.string.photo_text_progress, photoText.done, photoText.total)
                        )
                        WavyProgressIndicator(
                            photoText.done.toFloat() / photoText.total,
                            Modifier.fillMaxWidth().padding(start = 56.dp, end = 24.dp, bottom = 16.dp)
                        )
                    }
                }
            }
            storage?.let { item { GroupTitle(stringResource(R.string.storage_title)) }; item { StorageCard(it, onClearTemporary = viewModel::clearTemporaryFiles) } }
            item { GroupTitle(stringResource(R.string.resources_title)) }
            item { ResourceMetricsCard(resources = resources, indexing = progress.isRunning && !progress.isPaused) }
            if (kinds.isNotEmpty()) {
                item { GroupTitle(stringResource(R.string.file_types_title)) }
                item { KindBreakdown(kinds) }
            }
            if (engines.isNotEmpty()) {
                item { GroupTitle(stringResource(R.string.engines_title)) }
                item { EngineBreakdown(engines) }
            }
        }
    }

    if (confirmRebuild) {
        ConfirmDialog(
            title = stringResource(R.string.rebuild_index_title),
            body = stringResource(R.string.rebuild_index_body),
            confirm = stringResource(R.string.rebuild_index),
            onConfirm = { haptics.confirm(); viewModel.rebuildIndex(); confirmRebuild = false },
            onDismiss = { confirmRebuild = false }
        )
    }
}

private enum class HeroState { RUNNING, PAUSED, WAITING, ATTENTION, DONE }

@Composable
private fun StatusHero(
    progress: IndexProgress,
    counts: IndexCounts,
    lastScan: Long?,
    waitingForCharger: Boolean,
    onScan: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val state = when {
        progress.isRunning && progress.isPaused -> HeroState.PAUSED
        progress.isRunning -> HeroState.RUNNING
        counts.waiting > 0 -> HeroState.WAITING
        counts.failed > 0 -> HeroState.ATTENTION
        else -> HeroState.DONE
    }
    // Running/paused sit on a light blue card; done is a plain card; waiting and attention use amber.
    val accent = when (state) {
        HeroState.RUNNING, HeroState.PAUSED -> Accent.BLUE
        HeroState.WAITING, HeroState.ATTENTION -> Accent.AMBER
        HeroState.DONE -> Accent.GREEN
    }.colors()
    val active = state == HeroState.RUNNING || state == HeroState.PAUSED
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = if (active) accent.container else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (active) accent.onContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateContentSize()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val title = when (state) {
                HeroState.RUNNING -> stringResource(
                    when {
                        progress.waitingForMac -> R.string.waiting_for_mac_hero
                        progress.viaMac -> R.string.via_mac_hero
                        else -> R.string.hero_running
                    }
                )
                HeroState.PAUSED -> stringResource(R.string.hero_paused)
                HeroState.WAITING -> pluralStringResource(R.plurals.hero_waiting, counts.waiting, counts.waiting)
                HeroState.ATTENTION, HeroState.DONE -> stringResource(R.string.hero_done)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state == HeroState.RUNNING) {
                    ExpressiveLoader(size = 48.dp, color = accent.strong, containerColor = accent.strong.copy(alpha = 0.14f))
                } else {
                    val icon = when (state) {
                        HeroState.PAUSED -> Icons.Rounded.Pause
                        HeroState.WAITING -> Icons.Rounded.HourglassTop
                        HeroState.ATTENTION -> Icons.Rounded.WarningAmber
                        else -> Icons.Rounded.Check
                    }
                    ShapedIcon(icon, ExpressiveShapes.Cookie, if (active) accent.strong else accent.container, if (active) accent.onStrong else accent.onContainer, size = 48.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (state == HeroState.RUNNING && progress.viaMac && progress.itemsPerSec > 0f) {
                        Text(stringResource(R.string.via_mac_rate, progress.itemsPerSec), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (active) {
                val fraction = if (progress.totalCount > 0) progress.processedCount / progress.totalCount.toFloat() else 0f
                val animated by animateFloatAsState(fraction, tween(400), label = "heroProgress")
                val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
                Row {
                    Text(
                        numbers.format(progress.processedCount),
                        style = ExpressiveType.Number,
                        modifier = Modifier.alignByBaseline()
                    )
                    Text(
                        stringResource(R.string.hero_of_total, numbers.format(progress.totalCount)),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline().padding(start = 6.dp)
                    )
                }
                WavyProgressIndicator(
                    progress = if (progress.totalCount > 0) animated else null,
                    modifier = Modifier.fillMaxWidth(),
                    color = accent.strong,
                    trackColor = accent.strong.copy(alpha = 0.2f)
                )
                // Its own line: "About 1 h 17 min left · done around 9:15 pm" is too long to share one.
                if (state == HeroState.RUNNING) {
                    formatTimeLeft(LocalContext.current, progress.etaSeconds)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (state == HeroState.RUNNING && progress.currentFileName.isNotBlank()) {
                    Text(
                        text = progress.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = accent.onContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state == HeroState.RUNNING && progress.isThermalThrottled) {
                    val amber = Accent.AMBER.colors()
                    Text(
                        text = stringResource(R.string.hero_cooling_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = amber.onContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(amber.container)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                // Equal-width buttons so long labels never push one off the card.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == HeroState.RUNNING) {
                        FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) { ButtonContent(Icons.Rounded.Pause, R.string.action_pause) }
                    } else {
                        Button(onClick = onResume, modifier = Modifier.weight(1f)) { ButtonContent(Icons.Rounded.PlayArrow, R.string.action_resume) }
                    }
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { ButtonContent(Icons.Rounded.Stop, R.string.action_stop) }
                }
            } else {
                val detail = buildList {
                    if (state == HeroState.ATTENTION) add(pluralStringResource(R.plurals.hero_failed, counts.failed, counts.failed))
                    lastScan?.let { add(stringResource(R.string.hero_last_scan, formatRelative(it))) }
                }.joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state == HeroState.WAITING && waitingForCharger) {
                    Text(stringResource(R.string.hero_waiting_charger), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state == HeroState.WAITING) {
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { ButtonContent(Icons.Rounded.PlayArrow, R.string.action_index_now) }
                } else {
                    FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { ButtonContent(Icons.Rounded.Sync, R.string.rescan_now) }
                }
            }
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, label: Int) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.size(8.dp))
    Text(stringResource(label))
}

@Composable
private fun StorageCard(storage: StorageBreakdown, onClearTemporary: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var confirmClear by remember { mutableStateOf(false) }
    // Everything Filer keeps, adding up to the size in Android's Settings › Apps.
    val parts = listOf(
        Triple(R.string.storage_model, storage.modelBytes, MaterialTheme.colorScheme.primary),
        Triple(R.string.storage_search_data, storage.indexBytes, MaterialTheme.colorScheme.secondary),
        Triple(R.string.storage_ai_plugins, storage.pluginBytes, MaterialTheme.colorScheme.tertiary),
        Triple(R.string.storage_locked, storage.lockedBytes, Accent.SKY.colors().strong),
        Triple(R.string.storage_temporary, storage.cacheBytes, Accent.AMBER.colors().strong),
        Triple(R.string.storage_app, storage.appBytes, MaterialTheme.colorScheme.outline),
        Triple(R.string.storage_other, storage.otherBytes, MaterialTheme.colorScheme.outlineVariant)
    ).filter { it.second > 0 }
    val total = storage.totalBytes.coerceAtLeast(1L)
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.storage_total, formatSize(context, storage.totalBytes)),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.storage_total_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                parts.filter { it.second > 0 }.forEach { (_, bytes, color) ->
                    val weight by animateFloatAsState(bytes / total.toFloat(), tween(600), label = "storagePart")
                    Box(
                        Modifier
                            .weight(weight.coerceAtLeast(0.01f))
                            .height(12.dp)
                            .background(color)
                    )
                }
            }
            parts.forEach { (label, bytes, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatSize(context, bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = stringResource(R.string.storage_free, formatSize(context, storage.freeSpaceBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (storage.cacheBytes > 0) {
                OutlinedButton(onClick = { haptics.tick(); confirmClear = true }) {
                    Text(stringResource(R.string.storage_clear_temporary))
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.storage_clear_temporary_title, formatSize(context, storage.cacheBytes))) },
            text = { Text(stringResource(R.string.storage_clear_temporary_body)) },
            confirmButton = {
                TextButton(onClick = { haptics.confirm(); confirmClear = false; onClearTemporary() }) {
                    Text(stringResource(R.string.storage_clear))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun KindBreakdown(kinds: List<KindStat>) {
    val max = kinds.maxOf { it.count }.coerceAtLeast(1)
    SettingsGroup {
        Column(Modifier.padding(vertical = 8.dp)) {
            kinds.forEach { stat ->
                val colors = stat.kind.colors()
                val fraction by animateFloatAsState(stat.count / max.toFloat(), tween(600), label = "kindBar")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = MaterialTheme.shapes.medium, color = colors.container, modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(stat.kind.icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text(stringResource(stat.kind.label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(stat.count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.content.copy(alpha = 0.8f))
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Debug: vectors per engine (phone-gpu, phone-cpu, mac-gpu, fallback…). */
@Composable
private fun EngineBreakdown(engines: List<com.navaneeth.filer.data.db.dao.EngineCount>) {
    SettingsGroup {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            engines.forEach { (engine, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(engine, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ResourceMetricsCard(resources: SystemResourceUsage, indexing: Boolean) {
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.resources_title),
                    style = MaterialTheme.typography.titleMedium
                )
                // What indexing is doing right now; nothing to say while it isn't running.
                if (indexing) {
                    val badgeText = when {
                        resources.isThrottled -> stringResource(R.string.resources_status_throttled)
                        resources.isInferring -> stringResource(R.string.resources_status_inferring)
                        else -> stringResource(R.string.resources_status_yielding)
                    }
                    val badgeColor = when {
                        resources.isThrottled -> MaterialTheme.colorScheme.errorContainer
                        resources.isInferring -> Accent.BLUE.colors().container
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    val badgeTextColor = when {
                        resources.isThrottled -> MaterialTheme.colorScheme.onErrorContainer
                        resources.isInferring -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = CircleShape,
                        color = badgeColor,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTextColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Metric 1: CPU Load
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.resources_cpu), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(String.format(java.util.Locale.US, "%.1f%%", resources.cpuPercentage), style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(
                    progress = { (resources.cpuPercentage / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                )
            }

            // Metric 2: Memory (RAM)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.resources_memory), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Heap ${resources.heapRamMb} MB · Model & native ${resources.nativeRamMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("${resources.appRamMb} MB", style = MaterialTheme.typography.bodyMedium)
            }

            // Metric 3: Thermals & Temperature
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.DeviceThermostat,
                    contentDescription = null,
                    tint = if (resources.isThrottled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.resources_temperature), style = MaterialTheme.typography.bodyMedium)
                    val detail = if (resources.batteryTempC > 0f) {
                        String.format(java.util.Locale.US, "%.1f°C · Status: %s", resources.batteryTempC, resources.thermalStatusLabel)
                    } else {
                        "Status: ${resources.thermalStatusLabel}"
                    }
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resources.isThrottled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (resources.thermalHeadroom > 0f) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.0f%% headroom", resources.thermalHeadroom * 100f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Metric 4: Accelerator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.resources_accelerator), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    text = "${resources.acceleratorName} (${if (resources.isInferring) "Active" else "Idle"})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
