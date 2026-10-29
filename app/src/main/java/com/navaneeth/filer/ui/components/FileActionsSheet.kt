package com.navaneeth.filer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.util.LocalOpenDetails
import android.net.Uri
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import com.navaneeth.filer.data.ai.PluginData
import com.navaneeth.filer.data.ai.TextSource
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.extractor.UnreadableFileException
import com.navaneeth.filer.data.model.IndexStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import com.navaneeth.filer.ui.util.FileActions
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Bottom sheet for a file: open, open with, share, the file-manager actions, its basic details,
 * and a row into the details screen (smart search, tags, text read from it).
 */
@Composable
fun FileActionsSheet(
    item: FileUiItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val noAppMessage = stringResource(R.string.error_no_app)
    val open = rememberFileOpener()

    fun runAndClose(action: () -> Boolean) {
        val ok = action()
        if (!ok) {
            haptics.reject()
            scope.launch { snackbar.showSnackbar(noAppMessage) }
        }
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileThumbnail(item = item, modifier = Modifier.size(56.dp), iconSize = 28.dp, preview = true, previewWidthPx = 168)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                    Text(
                        text = "${formatSize(context, item.sizeBytes)} · ${stringResource(item.kind.label)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Folders open in Filer itself, so they only get the file-manager actions.
            if (!item.isDirectory) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetAction(
                    icon = Icons.Rounded.Visibility,
                    label = stringResource(R.string.action_open),
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) { runAndClose { open(item); true } }
                SheetAction(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    label = stringResource(R.string.action_open_with),
                    modifier = Modifier.weight(1f)
                ) { runAndClose { FileActions.openWith(context, item) } }
                SheetAction(
                    icon = Icons.Rounded.Share,
                    label = stringResource(R.string.action_share),
                    modifier = Modifier.weight(1f)
                ) { runAndClose { FileActions.share(context, item) } }
            }

            if (item.pathOrNull() != null) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    FileManageRows(item = item, close = ::closeThen)
                }
            }

            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    item.location?.let {
                        DetailRow(stringResource(R.string.detail_location), it)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    DetailRow(stringResource(R.string.detail_modified), formatModified(item.modifiedAt))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    DetailRow(stringResource(R.string.detail_size), formatSize(context, item.sizeBytes))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    DetailRow(stringResource(R.string.detail_type), stringResource(item.kind.label))
                }
            }

            // Everything Filer knows beyond the basics (smart search, tags, text it read) lives on
            // the details screen; this row says what is there, the same from every list.
            val openDetails = LocalOpenDetails.current
            if (openDetails != null && !item.isDirectory && item.pathOrNull() != null) {
                Surface(
                    onClick = { closeThen { openDetails(item) } },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    FileKnowledgeRow(item)
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
    val shape = MaterialTheme.shapes.large
    val padding = ButtonDefaults.ContentPadding
    if (primary) {
        Button(onClick = onClick, modifier = modifier.height(80.dp), shape = shape, contentPadding = padding) { content() }
    } else {
        FilledTonalButton(onClick = onClick, modifier = modifier.height(80.dp), shape = shape, contentPadding = padding) { content() }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** What Filer has on a file beyond its name: smart search state, tags, text read from it. */
private class FileKnowledge(val status: Int, val tags: Int, val textRead: Boolean, val transcribed: Boolean)

/** "Details" with a one-line summary of what the details screen holds for this file. */
@Composable
private fun FileKnowledgeRow(item: FileUiItem) {
    val context = LocalContext.current
    val path = item.pathOrNull() ?: return
    val knowledge by produceState<FileKnowledge?>(null, item.uri, item.fileId) {
        value = withContext(Dispatchers.IO) {
            val files = FilerDatabase.getInstance(context).fileDao()
            val entity = item.fileId?.let { files.getFileById(it) } ?: files.getFileByUri(Uri.fromFile(File(path)).toString())
            val plugins = PluginData.getInstance(context)
            FileKnowledge(
                status = when {
                    entity?.errorMessage == UnreadableFileException.PROTECTED -> R.string.smart_status_protected
                    else -> when (entity?.status) {
                        IndexStatus.INDEXED -> R.string.smart_status_ready
                        IndexStatus.PENDING -> R.string.smart_status_waiting
                        IndexStatus.FAILED -> R.string.smart_status_failed
                        IndexStatus.SKIPPED -> R.string.smart_status_skipped
                        else -> R.string.smart_status_none
                    }
                },
                tags = plugins.tagsFlow(path).first().size,
                textRead = !plugins.textFlow(path, TextSource.OCR).first()?.text.isNullOrBlank(),
                transcribed = !plugins.textFlow(path, TextSource.TRANSCRIPT).first()?.text.isNullOrBlank()
            )
        }
    }
    val summary = knowledge?.let { k ->
        listOfNotNull(
            stringResource(k.status),
            k.tags.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.details_tag_count, it, it) },
            stringResource(R.string.details_text_read).takeIf { k.textRead },
            stringResource(R.string.details_transcribed).takeIf { k.transcribed }
        ).joinToString(" · ")
    }
    val blue = Accent.BLUE.colors()
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { ShapedIcon(Icons.Rounded.AutoAwesome, ExpressiveShapes.Cookie, blue.container, blue.onContainer, size = 40.dp, iconSize = 20.dp) },
        headlineContent = { Text(stringResource(R.string.action_details)) },
        supportingContent = { Text(summary ?: stringResource(R.string.details_loading), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }
    )
}
