package com.navaneeth.filer.ui.screens.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.FolderNode
import com.navaneeth.filer.data.analysis.ScanStage
import com.navaneeth.filer.data.analysis.SpaceType
import com.navaneeth.filer.data.analysis.VolumeReport
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveLoader
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.LegendKey
import com.navaneeth.filer.ui.components.RingSegment
import com.navaneeth.filer.ui.components.ShapedIcon
import com.navaneeth.filer.ui.components.SizeBar
import com.navaneeth.filer.ui.components.UsageRing
import com.navaneeth.filer.ui.components.WavyProgressIndicator
import com.navaneeth.filer.ui.components.colors
import java.text.NumberFormat

// The cards that make up the Storage overview, top to bottom.

/** Two-option connected toggle (Internal / SD card): the selected side becomes a full pill. */
@Composable
fun VolumeToggle(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val sky = Accent.SKY.colors()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            val shape = when {
                isSelected -> ExpressiveShapes.Pill
                index == 0 -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 10.dp, bottomEnd = 10.dp)
                index == labels.lastIndex -> RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp, topStart = 10.dp, bottomStart = 10.dp)
                else -> RoundedCornerShape(10.dp)
            }
            Surface(
                onClick = { onSelect(index) },
                shape = shape,
                color = if (isSelected) sky.container else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (isSelected) sky.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The ring of used space by type, the big "used" number and a legend of chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageHero(report: VolumeReport, onOpenSystemStorage: () -> Unit) {
    val context = LocalContext.current
    val volume = report.volume
    val types = SpaceType.entries
        .map { it to (report.spaceByType[it] ?: 0L) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    val segments = types.map { (type, bytes) -> RingSegment(bytes.toFloat() / volume.totalBytes, type.color()) }

    Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                UsageRing(segments, Modifier.size(240.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(context.bytes(report.usedBytes), style = ExpressiveType.Big, color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.storage_used_of, context.bytes(volume.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val green = Accent.GREEN.colors()
                    Text(
                        stringResource(R.string.storage_free_badge, context.bytes(volume.freeBytes)),
                        style = MaterialTheme.typography.labelLarge,
                        color = green.onContainer,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(ExpressiveShapes.Pill)
                            .background(green.container)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.forEach { (type, bytes) -> LegendChip(stringResource(type.label), context.bytes(bytes), type.color()) }
            }
            TextButton(onClick = onOpenSystemStorage) {
                Text(stringResource(R.string.storage_system_note) + " › " + stringResource(R.string.action_system_storage))
            }
        }
    }
}

@Composable
private fun LegendChip(label: String, size: String, color: Color) {
    Surface(shape = ExpressiveShapes.Pill, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.height(36.dp).padding(start = 8.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LegendKey(color, size = 20.dp)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(size, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "Scanning… 42,000 files" or "Looking for duplicates…" with a wavy indicator. */
@Composable
fun ScanProgressCard(stage: ScanStage) {
    val (label, progress) = when (stage) {
        is ScanStage.Walking -> {
            val count = NumberFormat.getIntegerInstance().format(stage.filesSeen)
            pluralStringResource(R.plurals.storage_scanning_files, stage.filesSeen, count) to null
        }
        is ScanStage.Duplicates -> stringResource(R.string.storage_finding_duplicates) to stage.fraction
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ExpressiveLoader(size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (progress != null) {
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (progress != null) WavyProgressIndicator(progress, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Shown in place of the overview until the first scan of a volume is done. */
@Composable
fun FirstScanCard(stage: ScanStage?) {
    val label = when (stage) {
        is ScanStage.Walking -> {
            val count = NumberFormat.getIntegerInstance().format(stage.filesSeen)
            pluralStringResource(R.plurals.storage_scanning_files, stage.filesSeen, count)
        }
        is ScanStage.Duplicates -> stringResource(R.string.storage_finding_duplicates)
        null -> stringResource(R.string.storage_checking)
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ExpressiveLoader(size = 96.dp)
        Text(stringResource(R.string.storage_first_scan), style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The teal "You can free up 11.6 GB" card. */
@Composable
fun FreeUpHero(bytes: Long, onReviewAll: () -> Unit) {
    val context = LocalContext.current
    val teal = Accent.TEAL.colors()
    Surface(shape = RoundedCornerShape(40.dp), color = teal.container, contentColor = teal.onContainer, modifier = Modifier.fillMaxWidth()) {
        Box {
            // A big soft cookie peeking in from the corner, for character.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 48.dp, y = (-44).dp)
                    .size(190.dp)
                    .clip(ExpressiveShapes.Cookie)
                    .background(teal.strong.copy(alpha = 0.12f))
            )
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapedIcon(Icons.Rounded.AutoAwesome, ExpressiveShapes.Cookie, teal.strong, teal.onStrong, size = 64.dp, iconSize = 28.dp)
                Column {
                    Text(stringResource(R.string.storage_free_up_label), style = MaterialTheme.typography.titleSmall)
                    Text(context.bytes(bytes), style = ExpressiveType.Hero)
                    Text(stringResource(R.string.storage_free_up_body), style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = onReviewAll,
                    colors = ButtonDefaults.buttonColors(containerColor = teal.strong, contentColor = teal.onStrong),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(stringResource(R.string.action_review_all), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

/** Shown instead of [FreeUpHero] when nothing stands out. */
@Composable
fun AllCleanCard() {
    val green = Accent.GREEN.colors()
    Surface(shape = RoundedCornerShape(40.dp), color = green.container, contentColor = green.onContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapedIcon(Icons.Rounded.Check, ExpressiveShapes.Flower, green.strong, green.onStrong, size = 56.dp)
            Column {
                Text(stringResource(R.string.storage_nothing_to_clean), style = ExpressiveType.Section)
                Text(stringResource(R.string.storage_nothing_to_clean_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "Recycle bin · 340 MB · freed only when emptied" with an Empty button. */
@Composable
fun RecycleBinRow(bytes: Long, onOpen: () -> Unit, onEmpty: () -> Unit) {
    val context = LocalContext.current
    Surface(onClick = onOpen, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShapedIcon(Icons.Rounded.Delete, ExpressiveShapes.Squircle, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bin_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.storage_bin_body, context.bytes(bytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onEmpty) { Text(stringResource(R.string.action_empty_bin)) }
        }
    }
}

/** A folder worth naming in "Biggest folders": deep enough to be meaningful ("DCIM › Camera"). */
class NamedFolder(val label: String, val node: FolderNode)

/** Follows a folder down while one child holds most of it, so "DCIM" becomes "DCIM › Camera". */
fun biggestFolders(root: FolderNode, count: Int = 5): List<NamedFolder> =
    root.children.take(count).map { top ->
        var node = top
        val names = mutableListOf(top.name)
        while (true) {
            val main = node.children.firstOrNull() ?: break
            if (main.sizeBytes < node.sizeBytes * 0.75) break
            node = main
            names += main.name
        }
        NamedFolder(names.joinToString(" › "), node)
    }

@Composable
fun BiggestFoldersCard(folders: List<NamedFolder>, onOpenFolder: (String) -> Unit, onOpenMap: () -> Unit) {
    val context = LocalContext.current
    val largest = folders.maxOfOrNull { it.node.sizeBytes }?.takeIf { it > 0 } ?: 1L
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            folders.forEachIndexed { index, folder ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { onOpenFolder(folder.node.path) },
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row {
                        Text(
                            folder.label,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(context.bytes(folder.node.sizeBytes), style = MaterialTheme.typography.titleSmall)
                    }
                    val color = if (index == 0) MaterialTheme.colorScheme.primary else SpaceType.PHOTOS.color()
                    SizeBar(folder.node.sizeBytes.toFloat() / largest, color, height = 14.dp)
                }
            }
            FilledTonalButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.action_open_space_map), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
