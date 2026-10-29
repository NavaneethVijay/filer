package com.navaneeth.filer.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.ai.AiPlugin
import com.navaneeth.filer.data.ai.OcrScript
import com.navaneeth.filer.data.ai.PackState
import com.navaneeth.filer.data.ai.Unsupported
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.AccentColors
import com.navaneeth.filer.ui.components.BoldLargeTopBar
import com.navaneeth.filer.ui.components.ExpressiveSectionHeader
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.GroupDivider
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup
import com.navaneeth.filer.ui.components.ShapedIcon
import com.navaneeth.filer.ui.components.SwitchRow
import com.navaneeth.filer.ui.components.WavyProgressIndicator
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.AiViewModel
import com.navaneeth.filer.ui.viewmodel.PluginRow
import java.util.Locale

/** How each plugin is drawn: name, description, model, icon, colour, chip shape. */
private data class PluginLook(val name: Int, val body: Int, val model: Int, val icon: ImageVector, val accent: Accent, val shape: Shape)

private val AiPlugin.look: PluginLook
    get() = when (this) {
        AiPlugin.PHOTO_TEXT -> PluginLook(R.string.plugin_photo_text, R.string.plugin_photo_text_body, R.string.plugin_model_photo_text, Icons.Rounded.DocumentScanner, Accent.TEAL, RoundedCornerShape(50, 50, 50, 18))
        AiPlugin.SPEECH -> PluginLook(R.string.plugin_speech, R.string.plugin_speech_body, R.string.plugin_model_speech, Icons.Rounded.Mic, Accent.SKY, ExpressiveShapes.Pill)
    }

private val OcrScript.label: Int
    get() = when (this) {
        OcrScript.DEVANAGARI -> R.string.script_devanagari
        OcrScript.TAMIL -> R.string.script_tamil
        OcrScript.TELUGU -> R.string.script_telugu
    }

/** Languages offered for transcripts (Whisper knows more; these cover India and common others). */
private val SPEECH_LANGUAGES = listOf(
    "en", "hi", "bn", "mr", "ta", "te", "kn", "ml", "gu", "pa", "ur", "ne", "as", "sa",
    "ar", "zh", "fr", "de", "id", "ja", "ko", "pt", "ru", "es"
)

/** Settings › AI plugins: download, switch on/off and remove each plugin, and its few settings. */
@Composable
fun AiPluginsScreen(ai: AiViewModel, onOpenManageTags: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val plugins by ai.plugins.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var sheet by remember { mutableStateOf<AiPlugin?>(null) }
    var confirmClearSuggestions by remember { mutableStateOf(false) }
    val barSpace = LocalBottomBarSpace.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { BoldLargeTopBar(title = stringResource(R.string.ai_plugins_title), onBack = onBack, scrollBehavior = scrollBehavior) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = barSpace + 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "subtitle") {
                Text(
                    stringResource(R.string.ai_plugins_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item(key = "private") { PrivacyHero() }
            item(key = "header") {
                ExpressiveSectionHeader(stringResource(R.string.ai_plugins_section)) {
                    Text(
                        stringResource(R.string.ai_plugins_free, Formatter.formatShortFileSize(context, ai.freeSpace())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
            items(plugins, key = { it.plugin.name }) { row ->
                PluginCard(
                    row = row,
                    onOpen = { haptics.tick(); sheet = row.plugin },
                    onDownload = { haptics.confirm(); ai.download(row.plugin) },
                    onPause = { haptics.tick(); ai.pause(row.plugin.folder) },
                    onCancel = { haptics.tick(); ai.cancel(row.plugin.folder) }
                )
            }
            // Tags work with or without plugins; Suggest tags uses the smart-search model.
            item(key = "tags_header") { ExpressiveSectionHeader(stringResource(R.string.section_tags)) }
            item(key = "tags") {
                val tags by ai.tagCounts.collectAsState()
                val suggestNames by ai.suggestNames.collectAsState()
                SettingsGroup {
                    SettingRow(
                        icon = Icons.AutoMirrored.Rounded.Label,
                        title = stringResource(R.string.manage_tags_title),
                        subtitle = androidx.compose.ui.res.pluralStringResource(R.plurals.manage_tags_count, tags.size, tags.size),
                        chevron = true,
                        onClick = { haptics.tick(); onOpenManageTags() }
                    )
                    GroupDivider()
                    SwitchRow(
                        icon = Icons.Rounded.DriveFileRenameOutline,
                        title = stringResource(R.string.language_suggest_names),
                        subtitle = stringResource(R.string.language_suggest_names_body),
                        checked = suggestNames,
                        onCheckedChange = { haptics.toggle(it); ai.setSuggestNames(it) }
                    )
                    val suggested by ai.suggestedTagCount.collectAsState()
                    if (suggested > 0) {
                        GroupDivider()
                        SettingRow(
                            icon = Icons.Rounded.DeleteOutline,
                            title = stringResource(R.string.plugin_clear_tags),
                            subtitle = pluralStringResource(R.plurals.plugin_clear_tags_count, suggested, suggested),
                            onClick = { haptics.tick(); confirmClearSuggestions = true }
                        )
                    }
                }
                Text(
                    stringResource(R.string.tags_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (confirmClearSuggestions) {
        ConfirmDialog(
            title = stringResource(R.string.plugin_clear_tags_title),
            body = stringResource(R.string.plugin_clear_tags_body),
            confirm = stringResource(R.string.plugin_clear),
            onConfirm = {
                haptics.confirm()
                confirmClearSuggestions = false
                ai.clearSuggestions()
            },
            onDismiss = { confirmClearSuggestions = false }
        )
    }

    sheet?.let { plugin ->
        plugins.firstOrNull { it.plugin == plugin }?.let { row ->
            PluginSheet(row = row, ai = ai, onDismiss = { sheet = null })
        }
    }
}

@Composable
private fun PrivacyHero() {
    val blue = Accent.BLUE.colors()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = blue.container,
        contentColor = blue.onContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapedIcon(Icons.Rounded.Shield, ExpressiveShapes.Cookie, blue.strong, blue.onStrong, size = 44.dp, iconSize = 22.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ai_plugins_private_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.ai_plugins_private_body), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PluginCard(row: PluginRow, onOpen: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val look = row.plugin.look
    val colors = look.accent.colors()
    val installed = row.state == PackState.Installed
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(role = Role.Button, onClick = onOpen)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShapedIcon(look.icon, look.shape, colors.container, colors.onContainer, size = 44.dp, iconSize = 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(look.name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(look.body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val sizeLine = stringResource(R.string.plugin_size_line, stringResource(look.model), Formatter.formatShortFileSize(context, row.plugin.totalBytes))
            Column(Modifier.padding(start = 58.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val unsupported = row.unsupported
                when {
                    unsupported != null -> StatusNote(
                        stringResource(if (unsupported == Unsupported.NOT_64_BIT) R.string.plugin_unsupported_64 else R.string.plugin_unsupported_memory),
                        Accent.AMBER.colors()
                    )
                    installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pill = if (row.enabled) Accent.GREEN.colors() else Accent.SLATE.colors()
                        Surface(shape = ExpressiveShapes.Pill, color = pill.container, contentColor = pill.onContainer) {
                            Text(
                                stringResource(if (row.enabled) R.string.plugin_ready else R.string.plugin_off),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Text(sizeLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> DownloadControls(row.state, sizeLine, colors, onDownload, onPause, onCancel)
                }
            }
        }
    }
}

/** Download / progress / paused / failed, for a plugin or a script pack. */
@Composable
private fun DownloadControls(
    state: PackState,
    sizeLine: String,
    colors: AccentColors,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is PackState.Downloading -> {
                WavyProgressIndicator(state.fraction, Modifier.fillMaxWidth(), color = colors.strong, trackColor = colors.strong.copy(alpha = 0.24f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.plugin_downloading, size(state.bytes), size(state.total)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onPause) { Text(stringResource(R.string.plugin_pause)) }
                }
            }
            is PackState.Paused -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.plugin_paused, size(state.bytes), size(state.total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.plugin_cancel_download)) }
                Button(onClick = onDownload) { Text(stringResource(R.string.plugin_resume)) }
            }
            else -> {
                if (state is PackState.Failed) {
                    StatusNote(
                        stringResource(
                            when (state.reason) {
                                PackState.Reason.NO_SPACE -> R.string.plugin_failed_space
                                PackState.Reason.NETWORK -> R.string.plugin_failed_network
                                PackState.Reason.BAD_FILE -> R.string.plugin_failed_file
                            }
                        ),
                        Accent.AMBER.colors()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sizeLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Button(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.plugin_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusNote(text: String, colors: AccentColors) {
    Surface(shape = RoundedCornerShape(12.dp), color = colors.container, contentColor = colors.onContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One plugin's details: on/off, its settings, and Remove. */
@Composable
private fun PluginSheet(row: PluginRow, ai: AiViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val look = row.plugin.look
    val colors = look.accent.colors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    val installed = row.state == PackState.Installed

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ShapedIcon(look.icon, look.shape, colors.container, colors.onContainer, size = 56.dp, iconSize = 28.dp)
                Column {
                    Text(stringResource(look.name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.plugin_size_line, stringResource(look.model), Formatter.formatShortFileSize(context, row.plugin.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                stringResource(look.body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (!installed) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    DownloadControls(
                        state = row.state,
                        sizeLine = Formatter.formatShortFileSize(context, row.plugin.totalBytes),
                        colors = colors,
                        onDownload = { haptics.confirm(); ai.download(row.plugin) },
                        onPause = { ai.pause(row.plugin.folder) },
                        onCancel = { ai.cancel(row.plugin.folder) }
                    )
                }
            } else {
                SettingsGroup {
                    SwitchRow(
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = stringResource(R.string.plugin_use),
                        subtitle = stringResource(R.string.plugin_use_body),
                        checked = row.enabled,
                        onCheckedChange = { haptics.toggle(it); ai.setEnabled(row.plugin, it) }
                    )
                    when (row.plugin) {
                        AiPlugin.PHOTO_TEXT -> PhotoTextSettings(ai)
                        AiPlugin.SPEECH -> {
                            GroupDivider()
                            val language by ai.speechLanguage.collectAsState()
                            SettingRow(
                                icon = Icons.Rounded.Translate,
                                title = stringResource(R.string.speech_language),
                                subtitle = if (language.isEmpty()) stringResource(R.string.speech_language_auto) else languageName(language),
                                chevron = true,
                                onClick = { pickLanguage = true }
                            )
                        }
                    }
                    ClearResultsRow(row.plugin, ai, onClick = { haptics.tick(); confirmClear = true })
                }
                Text(
                    stringResource(
                        when (row.plugin) {
                            AiPlugin.SPEECH -> R.string.speech_never_background
                            AiPlugin.PHOTO_TEXT -> R.string.photo_text_background_body
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                if (row.plugin == AiPlugin.PHOTO_TEXT) ScriptPacks(ai)
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.plugin_remove))
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.plugin_remove_title, stringResource(look.name)),
            body = stringResource(R.string.plugin_remove_body),
            confirm = stringResource(R.string.plugin_remove),
            onConfirm = {
                haptics.confirm()
                confirmRemove = false
                ai.remove(row.plugin)
                onDismiss()
            },
            onDismiss = { confirmRemove = false }
        )
    }

    if (confirmClear) {
        val clear = clearTexts(row.plugin)
        val background by ai.readPhotosInBackground.collectAsState()
        // The background reader would read the cleared photos again on the next charge.
        val rereads = row.plugin == AiPlugin.PHOTO_TEXT && background
        ConfirmDialog(
            title = stringResource(clear.title),
            body = stringResource(clear.body) + if (rereads) "\n\n" + stringResource(R.string.plugin_clear_text_background) else "",
            confirm = stringResource(R.string.plugin_clear),
            onConfirm = {
                haptics.confirm()
                confirmClear = false
                ai.clearResults(row.plugin)
            },
            onDismiss = { confirmClear = false }
        )
    }

    if (pickLanguage) {
        val current by ai.speechLanguage.collectAsState()
        AlertDialog(
            onDismissRequest = { pickLanguage = false },
            title = { Text(stringResource(R.string.speech_language)) },
            text = {
                LazyColumn {
                    items(listOf("") + SPEECH_LANGUAGES) { code ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton) {
                                    haptics.tick()
                                    ai.setSpeechLanguage(code)
                                    pickLanguage = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = code == current, onClick = null)
                            Spacer(Modifier.size(12.dp))
                            Text(if (code.isEmpty()) stringResource(R.string.speech_language_auto) else languageName(code))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLanguage = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun PhotoTextSettings(ai: AiViewModel) {
    val haptics = rememberHaptics()
    val background by ai.readPhotosInBackground.collectAsState()
    val scan by ai.photoTextScan.collectAsState()
    val read by ai.photosRead.collectAsState()
    GroupDivider()
    SwitchRow(
        icon = Icons.Rounded.PhotoLibrary,
        title = stringResource(R.string.photo_text_background),
        subtitle = if (scan.running && scan.total > 0) {
            stringResource(R.string.photo_text_progress, scan.done, scan.total)
        } else {
            stringResource(R.string.photo_text_read_count, read)
        },
        checked = background,
        onCheckedChange = { haptics.toggle(it); ai.setReadPhotosInBackground(it) }
    )
    if (scan.running && scan.total > 0) {
        WavyProgressIndicator(
            scan.done.toFloat() / scan.total,
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 24.dp, bottom = 12.dp)
        )
    }
}

private data class ClearTexts(val row: Int, val count: Int, val title: Int, val body: Int)

private fun clearTexts(plugin: AiPlugin) = when (plugin) {
    AiPlugin.PHOTO_TEXT -> ClearTexts(R.string.plugin_clear_text, R.plurals.plugin_clear_text_count, R.string.plugin_clear_text_title, R.string.plugin_clear_text_body)
    AiPlugin.SPEECH -> ClearTexts(R.string.plugin_clear_transcripts, R.plurals.plugin_clear_transcripts_count, R.string.plugin_clear_transcripts_title, R.string.plugin_clear_transcripts_body)
}

/** Clears what the plugin made so far (shown only when there's something to clear). */
@Composable
private fun ClearResultsRow(plugin: AiPlugin, ai: AiViewModel, onClick: () -> Unit) {
    val count by when (plugin) {
        AiPlugin.PHOTO_TEXT -> ai.photosRead
        AiPlugin.SPEECH -> ai.transcriptCount
    }.collectAsState()
    if (count == 0) return
    val texts = clearTexts(plugin)
    GroupDivider()
    SettingRow(
        icon = Icons.Rounded.DeleteOutline,
        title = stringResource(texts.row),
        subtitle = pluralStringResource(texts.count, count, count),
        onClick = onClick
    )
}

/** Optional readers for Indian scripts, each downloaded on its own. */
@Composable
private fun ScriptPacks(ai: AiViewModel) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scripts by ai.scripts.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.photo_text_scripts),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)
        )
        Text(
            stringResource(R.string.photo_text_scripts_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.size(4.dp))
        SettingsGroup {
            OcrScript.entries.forEachIndexed { index, script ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                val state = scripts[script] ?: PackState.NotInstalled
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(16.dp))
                        Text(stringResource(script.label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (state == PackState.Installed) {
                            TextButton(onClick = { haptics.tick(); ai.removeScript(script) }) { Text(stringResource(R.string.plugin_remove)) }
                        }
                    }
                    if (state != PackState.Installed) {
                        Column(Modifier.padding(start = 40.dp)) {
                            DownloadControls(
                                state = state,
                                sizeLine = Formatter.formatShortFileSize(context, script.modelBytes + script.dictBytes),
                                colors = Accent.TEAL.colors(),
                                onDownload = { haptics.confirm(); ai.download(script) },
                                onPause = { ai.pause(script.folder) },
                                onCancel = { ai.cancel(script.folder) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
