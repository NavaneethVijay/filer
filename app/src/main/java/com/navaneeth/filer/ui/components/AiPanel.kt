package com.navaneeth.filer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.ai.AiPlugin
import com.navaneeth.filer.data.ai.PackState
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import com.navaneeth.filer.ui.viewmodel.AiViewModel

/** Whether [plugin] is installed, switched on and runs on this phone. */
@Composable
fun rememberPluginReady(ai: AiViewModel): (AiPlugin) -> Boolean {
    val rows by ai.plugins.collectAsState()
    return remember(rows) {
        { plugin -> rows.any { it.plugin == plugin && it.state == PackState.Installed && it.enabled && it.unsupported == null } }
    }
}

/**
 * The AI button on a viewer: always there (tags need no plugin), with a spinner while anything is
 * running for this file.
 */
@Composable
fun AiButton(path: String, ai: AiViewModel, onClick: () -> Unit) {
    val states by ai.states.collectAsState()
    val busy = states[path].orEmpty().isNotEmpty()
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = {
            if (busy) ExpressiveLoader(size = 24.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            else Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
        },
        text = { Text(stringResource(R.string.viewer_ai)) }
    )
}

/**
 * Everything the AI plugins (and tags) offer for one file, in a sheet: a suggested name, text in a
 * photo, a transcript, tags. Each card shows its own state.
 */
@Composable
fun AiPanelSheet(item: FileUiItem, ai: AiViewModel, player: AudioPlayer? = null, onRenamed: (String) -> Unit = {}, onDismiss: () -> Unit) {
    val path = item.pathOrNull() ?: return
    val ready = rememberPluginReady(ai)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            NameSuggestionCard(path, item.modifiedAt, ai, onRenamed)
            if (item.kind == FileKind.IMAGE && ready(AiPlugin.PHOTO_TEXT)) PhotoTextCard(path, ai)
            if (item.kind == FileKind.AUDIO && ready(AiPlugin.SPEECH)) TranscriptCard(path, ai, sharedPlayer = player)
            TagsCard(path, ai)
            if (AiPlugin.entries.none(ready)) {
                Text(
                    stringResource(R.string.ai_panel_no_plugins),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
    }
}
