package com.navaneeth.filer.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.navaneeth.filer.data.search.FilterKind
import com.navaneeth.filer.ui.viewmodel.SearchNotice
import com.navaneeth.filer.ui.viewmodel.UnderstoodFilter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.model.FileCategory
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.model.labelRes
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.navigation.Motion
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.util.highlightTerms
import com.navaneeth.filer.ui.util.highlightableTerms
import com.navaneeth.filer.ui.viewmodel.SearchHit
import com.navaneeth.filer.ui.viewmodel.SearchViewModel
import kotlin.math.roundToInt

/**
 * The collapsed search bar on Browse. It only opens [SearchOverlay], so Browse itself never
 * has to animate or re-lay out when search opens.
 */
@Composable
fun SearchPill(
    smartSearchReady: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = SearchBarDefaults.inputFieldShape,
        color = SearchBarDefaults.colors().containerColor,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(SearchBarDefaults.InputFieldHeight)
            .semantics { traversalIndex = -1f }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.Rounded.Search, contentDescription = null)
            Text(
                text = stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            )
            if (smartSearchReady) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.cd_smart_search_on),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/**
 * Full-screen search shared by the Browse search bar and the Search tab. It sits above every
 * screen, so closing it returns to exactly where the user was.
 */
@Composable
fun SearchOverlay(
    visible: Boolean,
    viewModel: SearchViewModel,
    smartSearchReady: Boolean,
    onClose: () -> Unit,
    onOpenResult: (SearchHit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = Motion.EmphasizedDecelerate)) +
            slideInVertically(tween(300, easing = Motion.EmphasizedDecelerate)) { -it / 24 },
        exit = fadeOut(tween(150, easing = Motion.EmphasizedAccelerate))
    ) {
        val query by viewModel.query.collectAsState()
        val searching by viewModel.isSearching.collectAsState()
        val keyboard = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }
        var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }

        fun close() {
            keyboard?.hide()
            viewModel.clear()
            onClose()
        }

        BackHandler { close() }
        // Coming back from a result's details: keep the keyboard down over the results.
        LaunchedEffect(Unit) { if (viewModel.query.value.isBlank()) focusRequester.requestFocus() }

        // The field owns its text and cursor. It never re-reads the query (a lagging value would
        // overwrite fresh keystrokes); text set by an action (a suggestion, Clear) goes through setText.
        var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
        fun setText(text: String) {
            field = TextFieldValue(text, TextRange(text.length))
            viewModel.onQueryChange(text)
        }

        // Same anatomy as the M3 full-screen search view: input field on top, results below. The
        // field is a pill on the page surface, like the collapsed bar it grows from.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TextField(
                    value = field,
                    onValueChange = {
                        field = it
                        viewModel.onQueryChange(it.text)
                    },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = {
                        IconButton(onClick = ::close) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_close_search))
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Work of unknown length: the expressive loader, small, beside Clear.
                            AnimatedVisibility(visible = searching, enter = fadeIn(), exit = fadeOut()) {
                                ExpressiveLoader(size = 24.dp)
                            }
                            if (field.text.isNotEmpty()) {
                                IconButton(onClick = { setText("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_clear_search))
                                }
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    shape = ExpressiveShapes.Pill,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(SearchBarDefaults.InputFieldHeight)
                        .focusRequester(focusRequester)
                )
                SearchResults(
                    viewModel = viewModel,
                    smartSearchReady = smartSearchReady,
                    onPickSuggestion = ::setText,
                    onOpenResult = { keyboard?.hide(); onOpenResult(it) },
                    onFileActions = { keyboard?.hide(); sheetItem = it }
                )
            }
        }

        sheetItem?.let { item -> FileActionsSheet(item = item, onDismiss = { sheetItem = null }) }
    }
}

@Composable
private fun SearchResults(
    viewModel: SearchViewModel,
    smartSearchReady: Boolean,
    onPickSuggestion: (String) -> Unit,
    onOpenResult: (SearchHit) -> Unit,
    onFileActions: (FileUiItem) -> Unit
) {
    val query by viewModel.query.collectAsState()
    val hits by viewModel.hits.collectAsState()
    val counts by viewModel.categoryCounts.collectAsState()
    val selected by viewModel.category.collectAsState()
    val searching by viewModel.isSearching.collectAsState()
    val hasSearched by viewModel.hasSearched.collectAsState()
    val showScores by viewModel.showScores.collectAsState()
    val interpretation by viewModel.interpretation.collectAsState()
    val scan by viewModel.placeScan.collectAsState()
    val haptics = rememberHaptics()
    val showFilters = query.isNotBlank() && interpretation.filters.isNotEmpty()
    var isGrid by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TypeChips(
                    selected = selected,
                    counts = if (query.isNotBlank() && hasSearched) counts.associate { it.category to it.count } else null,
                    onSelect = { haptics.tick(); viewModel.onCategoryChange(it) }
                )
            }
            if (hits.isNotEmpty()) {
                IconButton(
                    onClick = { haptics.tick(); isGrid = !isGrid },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Crossfade(targetState = isGrid, label = "searchLayoutToggle") { grid ->
                        if (grid) {
                            Icon(Icons.AutoMirrored.Rounded.ViewList, contentDescription = stringResource(R.string.cd_list_view))
                        } else {
                            Icon(Icons.Rounded.GridView, contentDescription = stringResource(R.string.cd_grid_view))
                        }
                    }
                }
            }
        }

        if (showFilters) {
            UnderstoodChips(interpretation.filters, onRemove = { haptics.tick(); viewModel.removeFilter(it) })
        }
        if (showFilters && interpretation.needsPlacePermission) {
            PlacePermissionCard(onResult = viewModel::onPlacePermissionResult)
        } else if (showFilters && scan.running && interpretation.filters.any { it.kind == FilterKind.PLACE }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.place_scan_progress, scan.done, scan.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WavyProgressIndicator(progress = if (scan.total > 0) scan.done / scan.total.toFloat() else 0f, modifier = Modifier.fillMaxWidth())
            }
        }
        if (showFilters && hits.isNotEmpty()) {
            when (interpretation.notice) {
                SearchNotice.AROUND_DATE -> NoticeText(stringResource(R.string.search_notice_around))
                SearchNotice.NO_WORD_MATCH -> NoticeText(stringResource(R.string.search_notice_no_words, interpretation.words))
                null -> Unit
            }
        }

        when {
            query.isBlank() && selected == null -> SearchSuggestions(smartSearchReady, onPick = onPickSuggestion)
            interpretation.needsPlacePermission && showFilters -> Unit
            searching && hits.isEmpty() -> LoadingState()
            hasSearched && hits.isEmpty() -> EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(if (query.isBlank()) R.string.search_type_empty_title else R.string.search_empty_title),
                body = stringResource(if (query.isBlank()) R.string.search_type_empty_body else R.string.search_empty_body)
            )
            isGrid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(hits, key = { it.item.uri }) { hit ->
                    SearchHitGridItem(
                        hit = hit,
                        showScore = showScores,
                        onOpen = { onOpenResult(hit) },
                        onActions = { onFileActions(hit.item) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(hits, key = { it.item.uri }) { hit ->
                    SearchHitRow(
                        hit = hit,
                        query = query,
                        showScore = showScores,
                        onOpen = { onOpenResult(hit) },
                        onActions = { onFileActions(hit.item) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

/**
 * One chip per file type, always shown so a type can be picked before typing. With results, each
 * chip shows its count ([counts], null while there's no query) and types with none are hidden.
 */
@Composable
private fun TypeChips(selected: FileCategory?, counts: Map<FileCategory?, Int>?, onSelect: (FileCategory?) -> Unit) {
    val chips = remember(counts, selected) {
        (listOf<FileCategory?>(null) + FileCategory.entries).filter { cat ->
            cat == null || cat == selected || counts == null || (counts[cat] ?: 0) > 0
        }
    }
    val blue = Accent.BLUE.colors()
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = blue.container,
        selectedLabelColor = blue.onContainer,
        selectedLeadingIconColor = blue.onContainer
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(chips, key = { it?.name ?: "ALL" }) { cat ->
            val isSelected = cat == selected
            val label = stringResource(cat.labelRes)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else cat) },
                label = { Text(counts?.get(cat)?.let { "$label $it" } ?: label) },
                leadingIcon = when {
                    isSelected -> {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    }
                    cat != null -> {
                        { Icon(cat.kind.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    }
                    else -> null
                },
                shape = ExpressiveShapes.Pill,
                colors = chipColors,
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun SearchHitGridItem(
    hit: SearchHit,
    showScore: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        FileGridItem(
            item = hit.item,
            onClick = onOpen,
            onMore = onActions
        )
        if (showScore && hit.score > 0f) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Text(
                    text = "${(hit.score * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    query: String,
    showScore: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val highlight = Accent.BLUE.colors().container
    val snippet = remember(hit.snippet, query, highlight) {
        hit.snippet.takeIf { it.isNotBlank() }?.let { highlightTerms(it, highlightableTerms(query), SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold)) }
    }
    val supporting = listOfNotNull(hit.item.location, formatModified(hit.item.modifiedAt).takeIf { it.isNotEmpty() })
        .joinToString(" · ")
    FileListItem(
        item = hit.item,
        supportingText = supporting,
        onClick = onOpen,
        onMore = onActions,
        snippet = snippet,
        modifier = modifier,
        trailingBadge = if (showScore && hit.score > 0f) {
            {
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(
                        text = "${(hit.score * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        } else {
            null
        }
    )
}

/** How a natural search was read: one chip per filter; ✕ searches again without it. */
@Composable
private fun UnderstoodChips(filters: List<UnderstoodFilter>, onRemove: (FilterKind) -> Unit) {
    val teal = Accent.TEAL.colors()
    val chipColors = InputChipDefaults.inputChipColors(
        selectedContainerColor = teal.container,
        selectedLabelColor = teal.onContainer,
        selectedLeadingIconColor = teal.onContainer,
        selectedTrailingIconColor = teal.onContainer
    )
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters, key = { it.kind.name }) { filter ->
            val remove = stringResource(R.string.cd_remove_filter, filter.label)
            InputChip(
                selected = true,
                onClick = { onRemove(filter.kind) },
                label = { Text(filter.label, maxLines = 1) },
                leadingIcon = { Icon(filter.kind.icon, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = remove, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                shape = ExpressiveShapes.Pill,
                colors = chipColors,
                border = null,
                modifier = Modifier.animateItem()
            )
        }
    }
}

private val FilterKind.icon: ImageVector
    get() = when (this) {
        FilterKind.TYPE -> Icons.Rounded.Category
        FilterKind.DATE -> Icons.Rounded.CalendarMonth
        FilterKind.FOLDER -> Icons.Rounded.Folder
        FilterKind.SIZE -> Icons.Rounded.Straighten
        FilterKind.PLACE -> Icons.Rounded.Place
        FilterKind.TAG -> Icons.AutoMirrored.Rounded.Label
    }

@Composable
private fun NoticeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/**
 * Asks for the media-location permission (Android hides where photos were taken without it).
 * If the system won't show the prompt any more, the button opens Filer's app settings.
 */
@Composable
private fun PlacePermissionCard(onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        onResult(granted)
    }
    val sky = Accent.SKY.colors()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapedIcon(Icons.Rounded.Place, ExpressiveShapes.Flower, sky.container, sky.onContainer, size = 44.dp, iconSize = 22.dp)
                Text(
                    stringResource(R.string.place_permission_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Text(
                stringResource(if (denied) R.string.place_permission_denied else R.string.place_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    if (denied) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    } else {
                        launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text(stringResource(if (denied) R.string.action_open_settings else R.string.action_allow)) }
        }
    }
}

@Composable
private fun SearchSuggestions(smartSearchReady: Boolean, onPick: (String) -> Unit) {
    val examples = listOf(
        stringResource(R.string.search_example_1),
        stringResource(R.string.search_example_2),
        stringResource(R.string.search_example_3),
        stringResource(R.string.search_example_4)
    )
    val filterExamples = listOf(
        stringResource(R.string.search_example_5),
        stringResource(R.string.search_example_6),
        stringResource(R.string.search_example_7),
        stringResource(R.string.search_example_8)
    )
    val blue = Accent.BLUE.colors()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item(key = "tip") {
            // The one emphasised card here: how search works right now.
            Surface(shape = RoundedCornerShape(28.dp), color = blue.container, contentColor = blue.onContainer) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ShapedIcon(Icons.Rounded.AutoAwesome, ExpressiveShapes.Cookie, blue.strong, blue.onStrong, size = 44.dp, iconSize = 22.dp)
                    Text(
                        stringResource(if (smartSearchReady) R.string.search_tip_smart else R.string.search_tip_basic),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item(key = "try") { SuggestionTitle(stringResource(R.string.search_try)) }
        item(key = "examples") { SuggestionGroup(examples, Icons.Rounded.Search, onPick, RoundedCornerShape(28.dp, 28.dp, 10.dp, 28.dp)) }
        item(key = "try_filters") { SuggestionTitle(stringResource(R.string.search_try_filters)) }
        item(key = "filter_examples") { SuggestionGroup(filterExamples, Icons.Rounded.FilterList, onPick, RoundedCornerShape(28.dp)) }
    }
}

@Composable
private fun SuggestionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

/** Example searches as clean list rows, grouped on one neutral card. */
@Composable
private fun SuggestionGroup(examples: List<String>, icon: ImageVector, onPick: (String) -> Unit, shape: Shape) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            examples.forEach { example ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(example) },
                    modifier = Modifier.clickable { onPick(example) }
                )
            }
        }
    }
}

/** The file type whose icon a chip borrows. */
private val FileCategory.kind: FileKind
    get() = when (this) {
        FileCategory.IMAGES -> FileKind.IMAGE
        FileCategory.VIDEOS -> FileKind.VIDEO
        FileCategory.AUDIO -> FileKind.AUDIO
        FileCategory.DOCUMENTS -> FileKind.DOCUMENT
        FileCategory.PDFS -> FileKind.PDF
        FileCategory.CODE -> FileKind.CODE
        FileCategory.ARCHIVES -> FileKind.ARCHIVE
        FileCategory.APKS -> FileKind.APK
    }
