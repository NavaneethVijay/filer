package com.navaneeth.filer.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.navaneeth.filer.R
import android.net.Uri
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.media.MediaFile
import com.navaneeth.filer.data.media.MediaLibrary
import com.navaneeth.filer.data.db.entity.FileEntity
import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.data.media.MediaFilter
import com.navaneeth.filer.data.media.TypeFilter
import com.navaneeth.filer.data.places.Gazetteer
import com.navaneeth.filer.data.ai.AiText
import com.navaneeth.filer.data.ai.PluginData
import com.navaneeth.filer.data.ai.TextSource
import com.navaneeth.filer.data.places.PhotoPlaces
import com.navaneeth.filer.data.places.PlaceScan
import com.navaneeth.filer.data.places.PlaceScanWorker
import com.navaneeth.filer.data.search.DateField
import com.navaneeth.filer.data.search.FilterKind
import com.navaneeth.filer.data.search.ParsedQuery
import com.navaneeth.filer.data.search.QueryParser
import com.navaneeth.filer.ui.model.labelRes
import com.navaneeth.filer.ui.model.pathOrNull
import android.text.format.Formatter
import com.navaneeth.filer.data.db.entity.SourceEntity
import com.navaneeth.filer.data.model.FileTypeFilter
import com.navaneeth.filer.data.model.SearchResultItem
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.data.repository.SearchRepository
import com.navaneeth.filer.ui.model.FileCategory
import com.navaneeth.filer.ui.model.FileUiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** One search result as the UI shows it: the file, the passage that matched, and its score. */
@Immutable
data class SearchHit(
    val item: FileUiItem,
    val snippet: String,
    val score: Float,
    /** Best-matching passages, strongest first (indexed text files only). */
    val matchedChunkIds: List<Long> = emptyList(),
    /** Query words found in the file. */
    val matchedTerms: List<String> = emptyList(),
    /** Matched by meaning, not only by words. */
    val meaningMatch: Boolean = false
)

@Immutable
data class CategoryCount(val category: FileCategory?, val count: Int)

/** One part of how a natural search was read ("PDFs", "Added 2 days ago"); the user can remove it. */
@Immutable
data class UnderstoodFilter(val kind: FilterKind, val label: String)

enum class SearchNotice { AROUND_DATE, NO_WORD_MATCH }

/** How the current query was read, and what the results needed. */
@Immutable
data class Interpretation(
    val filters: List<UnderstoodFilter> = emptyList(),
    val notice: SearchNotice? = null,
    /** Words that matched nothing (for [SearchNotice.NO_WORD_MATCH]). */
    val words: String = "",
    /** The query names a place but Filer may not read photo locations yet. */
    val needsPlacePermission: Boolean = false
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val searchRepo = SearchRepository(application)
    private val db = FilerDatabase.getInstance(application)
    private val media = MediaLibrary(application)
    private val prefs = PreferencesManager(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null means "All". */
    private val _category = MutableStateFlow<FileCategory?>(null)
    val category: StateFlow<FileCategory?> = _category.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())

    /** True once a search for the current query has finished, so "no results" isn't shown while typing. */
    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _interpretation = MutableStateFlow(Interpretation())
    val interpretation: StateFlow<Interpretation> = _interpretation.asStateFlow()

    /** Filters the user removed from the current query; cleared when the query changes. */
    private var removedFilters: Set<FilterKind> = emptySet()

    private val places = PhotoPlaces.getInstance(application)
    private val pluginData = PluginData.getInstance(application)

    /** Progress while photo locations are being read (for "photos from Goa"). */
    val placeScan: StateFlow<PlaceScan> = places.progress

    val showScores: StateFlow<Boolean> = prefs.showAdvancedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), prefs.showAdvanced)

    val categoryCounts: StateFlow<List<CategoryCount>> = _results.map { hits ->
        val counts = FileCategory.entries.map { cat -> CategoryCount(cat, hits.count { cat.matches(it.item.kind) }) }
        listOf(CategoryCount(null, hits.size)) + counts
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hits: StateFlow<List<SearchHit>> = combine(_results, _category) { hits, cat ->
        if (cat == null) hits else hits.filter { cat.matches(it.item.kind) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    /** Meaning results for the last query, so switching type chips doesn't run the model again. */
    private var meaningCache: Pair<String, List<SearchHit>>? = null

    init {
        // A file renamed, moved or deleted from the results: search again so the list is current.
        viewModelScope.launch {
            FileManager.getInstance(application).changes.collect {
                meaningCache = null
                launchSearch(delayMillis = 0)
            }
        }
        // Photo locations finished reading: a place search can now be answered fully.
        viewModelScope.launch {
            places.progress.map { it.running }.distinctUntilChanged().collect { running ->
                if (!running && _interpretation.value.filters.any { it.kind == FilterKind.PLACE }) launchSearch(delayMillis = 0)
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        if (newQuery == _query.value) return
        _query.value = newQuery
        removedFilters = emptySet()
        launchSearch(delayMillis = 250) // Debounce typing
    }

    /** Drops one part of how the query was read ("2 days ago"), and searches again without it. */
    fun removeFilter(kind: FilterKind) {
        removedFilters = removedFilters + kind
        launchSearch(delayMillis = 0)
    }

    /** After the media-location permission prompt. */
    fun onPlacePermissionResult(granted: Boolean) {
        if (!granted) return
        PlaceScanWorker.enqueue(getApplication())
        launchSearch(delayMillis = 0)
    }

    /** A type chip: filters results, or with no query lists the newest files of that type. */
    fun onCategoryChange(category: FileCategory?) {
        if (category == _category.value) return
        _category.value = category
        launchSearch(delayMillis = 0)
    }

    private fun launchSearch(delayMillis: Long) {
        val query = _query.value
        val category = _category.value
        searchJob?.cancel()
        _hasSearched.value = false

        if (query.isBlank() && category == null) {
            _results.value = emptyList()
            _interpretation.value = Interpretation()
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(delayMillis)
            _isSearching.value = true
            // "#receipts coffee": the tags narrow the results; the rest is searched as usual.
            val (foundTags, rest) = AiText.extractTags(query)
            val tags = if (FilterKind.TAG in removedFilters) emptyList() else foundTags
            val text = if (foundTags.isEmpty()) query else rest
            val (hits, interpretation) = if (tags.isEmpty()) {
                results(text, category)
            } else {
                val tagged = pluginData.pathsWithAllTags(tags)
                val (found, read) = if (text.isBlank()) {
                    tagged.map(::File).filter { it.isFile }.sortedByDescending { it.lastModified() }.map { it.toHit() } to Interpretation()
                } else {
                    results(text, null)
                }
                val inTag = found.filter { it.item.pathOrNull() in tagged && (category == null || category.matches(it.item.kind)) }
                val label = getApplication<Application>().getString(R.string.filter_tagged, tags.joinToString(" ") { "#$it" })
                inTag to read.copy(filters = listOf(UnderstoodFilter(FilterKind.TAG, label)) + read.filters)
            }
            _results.value = hits
            _interpretation.value = interpretation
            _isSearching.value = false
            _hasSearched.value = true
        }
    }

    /** Results for [query] (no tags) with the type [category] chip. */
    private suspend fun results(query: String, category: FileCategory?): Pair<List<SearchHit>, Interpretation> {
        if (query.isBlank()) {
            if (category == null) return emptyList<SearchHit>() to Interpretation()
            return media.ofType(category.typeFilter, BROWSE_LIMIT).map { it.toHit() }.filter { category.matches(it.item.kind) } to Interpretation()
        }
        val parsed = parse(query)
        if (parsed.kinds.isNotEmpty()) return filteredSearch(parsed, category)
        // Meaning matches from smart-search folders, words the AI plugins read (text in photos,
        // transcripts), then name matches from anywhere on the phone.
        val meaning = meaningCache?.takeIf { it.first == query }?.second
            ?: toHits(searchRepo.search(query, FileTypeFilter.ALL)).also { meaningCache = query to it }
        val seen = meaning.mapTo(HashSet()) { it.item.uri }
        val inText = pluginHits(parsed.terms).filter { seen.add(it.item.uri) }
        // With a type chip on, also search names of that type only, so it isn't crowded out.
        val byName = (media.searchByName(query, NAME_MATCH_LIMIT) +
            (category?.let { media.searchByName(query, NAME_MATCH_LIMIT, it.typeFilter) } ?: emptyList()))
            .map { it.toHit() }
            .filter { seen.add(it.item.uri) }
        return (meaning + inText + byName) to Interpretation()
    }

    /** Files whose photo text or transcript contains all of [terms]; works without smart search. */
    private suspend fun pluginHits(terms: List<String>): List<SearchHit> {
        if (terms.isEmpty()) return emptyList()
        val app = getApplication<Application>()
        return pluginData.search(terms).map { match ->
            val label = if (match.source == TextSource.TRANSCRIPT) R.string.search_transcript_match else R.string.search_photo_text_match
            File(match.path).toHit().copy(snippet = app.getString(label, match.snippet), matchedTerms = terms)
        }
    }

    /**
     * Reads the query as filters + words. A small town name that matches no photo is treated as an
     * ordinary word ("photos from holiday" isn't Holiday, Florida).
     */
    private suspend fun parse(query: String): ParsedQuery {
        val gazetteer = runCatching { Gazetteer.get(getApplication()) }.getOrNull()
        var parsed = QueryParser.parse(query, gazetteer)
        val place = parsed.place
        if (place != null && parsed.placeIsWeak && places.hasPermission() && places.pathsIn(place).isEmpty()) {
            parsed = QueryParser.parse(query, gazetteer, allowWeakPlace = false)
        }
        return parsed.without(removedFilters)
    }

    /**
     * Answers "pdfs downloaded 2 days ago" from Android's media index (no model): files matching
     * the filters, narrowed by any leftover words through names and smart search.
     */
    private suspend fun filteredSearch(parsed: ParsedQuery, chip: FileCategory?): Pair<List<SearchHit>, Interpretation> {
        var interpretation = Interpretation(filters = describe(parsed))

        val place = parsed.place
        val placePaths: Set<String>? = when {
            place == null -> null
            !places.hasPermission() -> return emptyList<SearchHit>() to interpretation.copy(needsPlacePermission = true)
            else -> {
                PlaceScanWorker.enqueue(getApplication())
                places.pathsIn(place)
            }
        }

        val categories = chip?.let(::setOf) ?: parsed.categories
        val types = categories.map { it.typeFilter } +
            (if (chip == null && parsed.extensions.isNotEmpty()) listOf(TypeFilter(extensions = parsed.extensions)) else emptyList())
        fun typeOk(item: FileUiItem) = (categories.isEmpty() && parsed.extensions.isEmpty()) ||
            categories.any { it.matches(item.kind) } ||
            (chip == null && item.name.substringAfterLast('.', "").lowercase() in parsed.extensions)

        var range = parsed.date?.let { it.start to it.end }
        val base = MediaFilter(
            types = types,
            dateRange = range,
            dateField = parsed.dateField,
            folders = parsed.folders.map { it.needle to it.topLevel },
            minBytes = parsed.size?.minBytes,
            maxBytes = parsed.size?.maxBytes
        )
        // A place is matched against the photo list, so fetch more rows before narrowing.
        val limit = if (placePaths != null) PLACE_LIMIT else FILTERED_LIMIT
        suspend fun fetch(filter: MediaFilter) = media.filtered(filter, limit)
            .filter { placePaths == null || it.path in placePaths }
            .map { it.toHit() }
            .filter { typeOk(it.item) }

        var matching = fetch(base)
        val around = parsed.date?.around
        if (matching.isEmpty() && around != null) {
            range = around
            matching = fetch(base.copy(dateRange = around))
            if (matching.isNotEmpty()) interpretation = interpretation.copy(notice = SearchNotice.AROUND_DATE)
        }
        if (parsed.terms.isEmpty()) return matching.take(FILTERED_LIMIT) to interpretation

        // Leftover words ("invoice"): names that contain them, and smart-search matches within the filters.
        fun accepts(item: FileUiItem): Boolean {
            val path = item.pathOrNull() ?: return false
            return typeOk(item) &&
                (range == null || item.modifiedAt in range!!.first..range!!.second) &&
                (parsed.folders.isEmpty() || parsed.folders.any { f ->
                    if (f.topLevel) path.contains("/${f.needle}/", ignoreCase = true) else path.contains(f.needle, ignoreCase = true)
                }) &&
                (parsed.size?.minBytes?.let { item.sizeBytes >= it } ?: true) &&
                (parsed.size?.maxBytes?.let { item.sizeBytes <= it } ?: true) &&
                (placePaths == null || path in placePaths)
        }
        val text = parsed.text
        val meaning = (meaningCache?.takeIf { it.first == text }?.second
            ?: toHits(searchRepo.search(text, FileTypeFilter.ALL)).also { meaningCache = text to it })
            .filter { accepts(it.item) }
        val seen = meaning.mapTo(HashSet()) { it.item.uri }
        val inText = pluginHits(parsed.terms).filter { accepts(it.item) && seen.add(it.item.uri) }
        val byName = media.filtered(base.copy(dateRange = range, nameTerms = parsed.terms), limit)
            .filter { placePaths == null || it.path in placePaths }
            .map { it.toHit() }
            .filter { typeOk(it.item) && seen.add(it.item.uri) }
        val found = meaning + inText + byName
        if (found.isNotEmpty()) return found to interpretation
        // The words matched nothing: show what fits the rest, and say so.
        return matching.take(FILTERED_LIMIT) to interpretation.copy(notice = SearchNotice.NO_WORD_MATCH, words = text)
    }

    /** Chip labels for each part of the query that was understood. */
    private fun describe(parsed: ParsedQuery): List<UnderstoodFilter> {
        val app = getApplication<Application>()
        fun cap(text: String) = text.replaceFirstChar { it.uppercase() }
        return buildList {
            if (parsed.hasType) {
                val labels = parsed.categories.map { app.getString(it.labelRes) } + parsed.extensions.map { it.uppercase() }
                add(UnderstoodFilter(FilterKind.TYPE, labels.joinToString(", ")))
            }
            parsed.date?.let { date ->
                val label = when (parsed.dateField) {
                    DateField.ADDED -> app.getString(R.string.filter_date_added, date.phrase)
                    DateField.TAKEN -> app.getString(R.string.filter_date_taken, date.phrase)
                    DateField.MODIFIED -> app.getString(R.string.filter_date_modified, date.phrase)
                    DateField.ANY -> cap(date.phrase)
                }
                add(UnderstoodFilter(FilterKind.DATE, label))
            }
            if (parsed.folders.isNotEmpty()) add(UnderstoodFilter(FilterKind.FOLDER, parsed.folders.joinToString(", ") { it.label }))
            parsed.size?.let { size ->
                val min = size.minBytes?.let { Formatter.formatShortFileSize(app, it) }
                val max = size.maxBytes?.let { Formatter.formatShortFileSize(app, it) }
                val label = when {
                    min != null && max != null -> app.getString(R.string.filter_size_between, min, max)
                    min != null -> app.getString(R.string.filter_size_over, min)
                    else -> app.getString(R.string.filter_size_under, max)
                }
                add(UnderstoodFilter(FilterKind.SIZE, label))
            }
            parsed.place?.let { add(UnderstoodFilter(FilterKind.PLACE, it.label)) }
        }
    }

    /** Clears the query and the type chip (search closed). */
    fun clear() {
        _category.value = null
        _query.value = ""
        removedFilters = emptySet()
        meaningCache = null
        launchSearch(delayMillis = 0)
    }

    /** The current result for a file, so its details screen can explain why it matched. */
    fun hitFor(uri: String): SearchHit? = _results.value.firstOrNull { it.item.uri == uri }

    private suspend fun toHits(raw: List<SearchResultItem>): List<SearchHit> {
        if (raw.isEmpty()) return emptyList()
        val entities = db.fileDao().getFilesByIds(raw.map { it.fileId }).associateBy(FileEntity::id)
        val sources = db.sourceDao().getActiveSources().associateBy(SourceEntity::id)
        return raw.map { result ->
            val source = entities[result.fileId]?.let { sources[it.sourceId] }
            SearchHit(
                item = FileUiItem(
                    uri = result.uriString,
                    name = result.displayName,
                    mimeType = result.mimeType,
                    sizeBytes = result.sizeBytes,
                    modifiedAt = result.modifiedAt,
                    location = source?.let(::sourceLabel),
                    fileId = result.fileId
                ),
                snippet = result.matchSnippet.takeUnless { it.startsWith("On-device") }.orEmpty(),
                score = result.similarityScore,
                matchedChunkIds = result.matchedChunkIds,
                matchedTerms = result.matchedTerms,
                meaningMatch = result.meaningMatch
            )
        }
    }

    private fun File.toHit() = SearchHit(
        item = FileUiItem(
            uri = Uri.fromFile(this).toString(),
            name = name,
            mimeType = com.navaneeth.filer.data.storage.Storage.mimeTypeOf(name),
            sizeBytes = length(),
            modifiedAt = lastModified(),
            location = parentFile?.name
        ),
        snippet = "",
        score = 0f
    )

    private fun MediaFile.toHit() = SearchHit(
        item = FileUiItem(
            uri = Uri.fromFile(File(path)).toString(),
            name = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            location = File(path).parentFile?.name
        ),
        snippet = "",
        score = 0f
    )

    private companion object {
        const val NAME_MATCH_LIMIT = 100
        const val BROWSE_LIMIT = 300
        const val FILTERED_LIMIT = 500
        const val PLACE_LIMIT = 20_000
    }

    private fun sourceLabel(source: SourceEntity): String =
        source.displayName
}
