package com.navaneeth.filer.ui.screens

import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.Accent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DataArray
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.rounded.Place
import com.navaneeth.filer.data.places.PhotoPlaces
import com.navaneeth.filer.ui.model.pathOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.db.entity.ChunkEntity
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.FileThumbnail
import com.navaneeth.filer.ui.components.GroupDivider
import com.navaneeth.filer.ui.components.GroupTitle
import com.navaneeth.filer.ui.components.IndexStatusChip
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup
import com.navaneeth.filer.ui.components.formatRelative
import com.navaneeth.filer.ui.components.indexProblemText
import com.navaneeth.filer.data.extractor.UnreadableFileException
import com.navaneeth.filer.ui.components.label
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.util.FileActions
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.excerptAround
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.highlightTerms
import com.navaneeth.filer.ui.util.highlightableTerms
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.FileIndexDetails
import com.navaneeth.filer.ui.viewmodel.IndexViewModel
import com.navaneeth.filer.ui.viewmodel.SearchHit
import androidx.compose.runtime.collectAsState
import com.navaneeth.filer.data.ai.AiPlugin
import com.navaneeth.filer.data.ai.PackState
import com.navaneeth.filer.data.ai.PluginIndex
import com.navaneeth.filer.ui.components.NameSuggestionCard
import com.navaneeth.filer.ui.components.PhotoTextCard
import com.navaneeth.filer.ui.components.TagsCard
import com.navaneeth.filer.ui.components.TranscriptCard
import com.navaneeth.filer.ui.viewmodel.AiViewModel
import kotlinx.coroutines.launch

private const val PASSAGE_PREVIEW_COUNT = 3

/**
 * One file: preview, actions, and, when opened from search, why it matched (the passages that
 * matched, with the query's words highlighted). Index internals show only with "Show indexing details".
 */
@Composable
fun FileDetailsScreen(
    ai: AiViewModel,
    fileId: Long?,
    uri: String?,
    match: SearchHit?,
    query: String?,
    showAdvanced: Boolean,
    viewModel: IndexViewModel,
    onBack: () -> Unit,
    onShowInFolder: (path: String) -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val open = rememberFileOpener()
    var details by remember { mutableStateOf<FileIndexDetails?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var showAllPassages by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val noAppMessage = stringResource(R.string.error_no_app)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val terms = remember(query) { query?.let(::highlightableTerms).orEmpty() }

    // After "Rename" from a suggested name, the same screen shows the renamed file.
    var renamedUri by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(fileId, uri, renamedUri) {
        details = renamedUri?.let { viewModel.loadDetails(null, it) } ?: viewModel.loadDetails(fileId, uri)
        loaded = true
    }
    val plugins by ai.plugins.collectAsState()
    fun ready(plugin: AiPlugin) = plugins.any { it.plugin == plugin && it.state == PackState.Installed && it.enabled && it.unsupported == null }


    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.file_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        val current = details
        when {
            !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            current == null -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
                title = stringResource(R.string.file_missing_title),
                body = stringResource(R.string.file_missing_body),
                modifier = Modifier.padding(padding)
            )
            else -> {
                val item = current.item
                // Where a photo was taken, if Filer has read it (photo locations permission).
                val photoPlace by produceState<String?>(null, item.uri) {
                    val path = item.pathOrNull() ?: return@produceState
                    value = PhotoPlaces.getInstance(context).placeOrRead(java.io.File(path))
                        ?.let { listOfNotNull(it.city, it.region.takeIf(String::isNotEmpty), it.country).distinct().joinToString(", ") }
                }
                val file = current.file
                val byId = remember(current.passages) { current.passages.associateBy { it.id } }
                val matched = remember(match, byId) { match?.matchedChunkIds.orEmpty().mapNotNull { byId[it] } }
                val passages = (if (showAllPassages) current.passages else current.passages.take(PASSAGE_PREVIEW_COUNT))
                    .filterNot { PluginIndex.isPluginPage(it.pageNumber) }
                val path = item.pathOrNull()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp)
                ) {
                    // Header: preview, name, quick actions
                    item(key = "header") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            val previewHeight = when (item.kind) {
                                FileKind.IMAGE -> 280.dp
                                FileKind.PDF -> 220.dp
                                else -> 140.dp
                            }
                            FileThumbnail(
                                item = item,
                                preview = true,
                                previewWidthPx = 720,
                                iconSize = 56.dp,
                                shape = MaterialTheme.shapes.extraLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(previewHeight)
                                    .clickable(role = Role.Button) { open(item) }
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    listOf(formatSize(context, item.sizeBytes), stringResource(item.kind.label), formatModified(item.modifiedAt))
                                        .filter { it.isNotEmpty() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { open(item) }) {
                                    Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.action_open), modifier = Modifier.padding(start = 8.dp))
                                }
                                FilledTonalButton(onClick = {
                                    if (!FileActions.openWith(context, item)) scope.launch { snackbar.showSnackbar(noAppMessage) }
                                }) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.action_open_with), modifier = Modifier.size(18.dp))
                                }
                                FilledTonalButton(onClick = { FileActions.share(context, item) }) {
                                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // AI plugins: each card shows only when its plugin is installed and on.
                    if (path != null) {
                        item(key = "ai_name") {
                            NameSuggestionCard(path, item.modifiedAt, ai) { newPath ->
                                renamedUri = android.net.Uri.fromFile(java.io.File(newPath)).toString()
                            }
                        }
                        if (ready(AiPlugin.PHOTO_TEXT) && item.kind == FileKind.IMAGE) {
                            item(key = "ai_photo_text") { PhotoTextCard(path, ai) }
                        }
                        if (ready(AiPlugin.SPEECH) && item.kind == FileKind.AUDIO) {
                            item(key = "ai_transcript") {
                                TranscriptCard(path, ai)
                            }
                        }
                        item(key = "ai_tags") { TagsCard(path, ai) }
                    }

                    // Why it matched (only when opened from a search)
                    if (match != null && !query.isNullOrBlank()) {
                        item(key = "match_title") { GroupTitle(stringResource(R.string.match_title)) }
                        item(key = "match_reasons") {
                            SettingsGroup {
                                val reasons = buildList {
                                    if (match.meaningMatch) {
                                        val text = if (item.kind == FileKind.IMAGE) R.string.match_meaning_photo else R.string.match_meaning
                                        add(Icons.Rounded.AutoAwesome to stringResource(text, query.trim()))
                                    }
                                    if (match.matchedTerms.isNotEmpty()) {
                                        add(Icons.Rounded.TextFields to stringResource(R.string.match_words, match.matchedTerms.joinToString(", ") { "“$it”" }))
                                    }
                                    if (isEmpty()) add(Icons.Rounded.TextFields to stringResource(R.string.match_name, query.trim()))
                                }
                                reasons.forEachIndexed { index, (icon, text) ->
                                    if (index > 0) GroupDivider()
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                                        headlineContent = { Text(text) }
                                    )
                                }
                            }
                        }
                        itemsIndexed(matched, key = { _, passage -> "match_${passage.id}" }) { index, passage ->
                            MatchedPassage(
                                passage = passage,
                                label = if (index == 0) stringResource(R.string.match_best) else stringResource(R.string.match_passage, index + 1),
                                terms = terms
                            )
                        }
                    }

                    // Text the index read from this file
                    if (current.passages.isNotEmpty()) {
                        item(key = "text_title") { GroupTitle(stringResource(R.string.indexed_text_title)) }
                        itemsIndexed(passages, key = { _, passage -> passage.id }) { _, passage ->
                            PassageCard(passage = passage, terms = terms, modifier = Modifier.animateItem())
                        }
                        if (current.passages.size > PASSAGE_PREVIEW_COUNT) {
                            item(key = "text_more") {
                                TextButton(
                                    onClick = { haptics.tick(); showAllPassages = !showAllPassages },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    Text(
                                        if (showAllPassages) stringResource(R.string.show_less)
                                        else stringResource(R.string.show_all_passages, current.passages.size)
                                    )
                                }
                            }
                        }
                    }

                    // Where it lives and whether it's searchable by meaning
                    item(key = "info_title") { GroupTitle(stringResource(R.string.section_file_info)) }
                    item(key = "info") {
                        SettingsGroup {
                            current.folderLabel?.let { label ->
                                SettingRow(
                                    Icons.Rounded.Folder,
                                    stringResource(R.string.detail_location),
                                    subtitle = label,
                                    trailing = current.folderPath?.let { path ->
                                        @Composable { TextButton(onClick = { onShowInFolder(path) }) { Text(stringResource(R.string.action_show_in_folder)) } }
                                    }
                                )
                                GroupDivider()
                            }
                            photoPlace?.let { place ->
                                SettingRow(Icons.Rounded.Place, stringResource(R.string.detail_place), subtitle = place)
                                GroupDivider()
                            }
                            SettingRow(Icons.Rounded.Straighten, stringResource(R.string.detail_size), subtitle = formatSize(context, item.sizeBytes))
                            GroupDivider()
                            SettingRow(Icons.Rounded.Schedule, stringResource(R.string.detail_modified), subtitle = formatModified(item.modifiedAt))
                            GroupDivider()
                            SettingRow(
                                Icons.Rounded.AutoAwesome,
                                stringResource(R.string.detail_smart_search),
                                subtitle = stringResource(
                                    when {
                                        file?.error == UnreadableFileException.PROTECTED -> R.string.smart_status_protected
                                        else -> when (file?.status) {
                                            IndexStatus.INDEXED -> R.string.smart_status_ready
                                            IndexStatus.PENDING -> R.string.smart_status_waiting
                                            IndexStatus.FAILED -> R.string.smart_status_failed
                                            IndexStatus.SKIPPED -> R.string.smart_status_skipped
                                            else -> R.string.smart_status_none
                                        }
                                    }
                                )
                            )
                        }
                    }

                    // Index internals (Settings › Show indexing details)
                    if (showAdvanced && file != null) {
                        item(key = "index_title") { GroupTitle(stringResource(R.string.search_index_title)) }
                        item(key = "index") {
                            SettingsGroup {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    leadingContent = { Icon(Icons.Rounded.FindInPage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    headlineContent = { Text(stringResource(R.string.detail_status)) },
                                    trailingContent = { IndexStatusChip(file.status) }
                                )
                                indexProblemText(file.error)?.takeIf { file.status != IndexStatus.INDEXED }?.let { problem ->
                                    GroupDivider()
                                    SettingRow(Icons.Rounded.ErrorOutline, stringResource(R.string.detail_error), subtitle = problem, destructive = true)
                                }
                                GroupDivider()
                                SettingRow(
                                    Icons.Rounded.Schedule,
                                    stringResource(R.string.detail_indexed),
                                    subtitle = file.indexedAt?.let(::formatRelative) ?: stringResource(R.string.detail_not_yet)
                                )
                                GroupDivider()
                                SettingRow(
                                    Icons.Rounded.DataArray,
                                    stringResource(R.string.detail_embedding),
                                    subtitle = when {
                                        current.embeddingDimensions != null && item.kind == FileKind.IMAGE ->
                                            stringResource(R.string.detail_embedding_image, current.embeddingDimensions)
                                        current.embeddingDimensions != null ->
                                            stringResource(R.string.detail_embedding_text, current.embeddedCount, current.embeddingDimensions)
                                        else -> stringResource(R.string.detail_embedding_none)
                                    }
                                )
                            }
                        }
                        item(key = "remove") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
                                OutlinedButton(
                                    onClick = { confirmRemove = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text(stringResource(R.string.remove_from_index)) }
                            }
                        }
                    }
                }
            }
        }
    }

    val removable = details?.file?.item?.fileId
    if (confirmRemove && removable != null) {
        ConfirmDialog(
            title = stringResource(R.string.remove_from_index_title),
            body = stringResource(R.string.remove_from_index_body),
            confirm = stringResource(R.string.action_remove),
            onConfirm = {
                haptics.confirm()
                confirmRemove = false
                viewModel.removeFromIndex(removable)
                onBack()
            },
            onDismiss = { confirmRemove = false }
        )
    }
}

/** A passage that matched the search: opens on the matching words; tap to read all of it. */
@Composable
private fun MatchedPassage(passage: ChunkEntity, label: String, terms: List<String>) {
    val haptics = rememberHaptics()
    var expanded by rememberSaveable(passage.id) { mutableStateOf(false) }
    val highlight = Accent.BLUE.colors().strong.copy(alpha = 0.22f)
    val style = SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold)
    val excerpt = remember(passage.id, terms) { excerptAround(passage.textSnippet, terms) }
    val full = remember(passage.id) { passage.textSnippet.replace(Regex("\\s+"), " ").trim() }
    val text = remember(expanded, excerpt, full, terms, highlight) { highlightTerms(if (expanded) full else excerpt, terms, style) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Accent.BLUE.colors().container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize()
            .clickable(role = Role.Button) { haptics.tick(); expanded = !expanded }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listOfNotNull(label, passageLabel(passage.pageNumber)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (!expanded && excerpt.length < full.length) {
                Text(stringResource(R.string.show_more), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** One passage of the file's text, as the index read it. */
@Composable
private fun PassageCard(passage: ChunkEntity, terms: List<String>, modifier: Modifier = Modifier) {
    val highlight = Accent.BLUE.colors().strong.copy(alpha = 0.22f)
    val text = remember(passage.id, terms, highlight) {
        highlightTerms(passage.textSnippet.trim(), terms, SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold))
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            passageLabel(passage.pageNumber)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Page 3", or where a plugin's passage came from (text in a photo, a transcript). */
@Composable
private fun passageLabel(page: Int?): String? = when {
    page == null -> null
    page == PluginIndex.PAGE_OCR -> stringResource(R.string.ai_photo_text_title)
    page == PluginIndex.PAGE_TRANSCRIPT -> stringResource(R.string.ai_transcript_title)
    else -> stringResource(R.string.page_number, page)
}
