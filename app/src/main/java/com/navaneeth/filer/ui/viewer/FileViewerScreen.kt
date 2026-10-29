package com.navaneeth.filer.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.ai.AiPlugin
import com.navaneeth.filer.data.ai.AiTask
import com.navaneeth.filer.data.ai.PackState
import com.navaneeth.filer.data.ai.TextSource
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.ui.components.AiButton
import com.navaneeth.filer.ui.components.AiPanelSheet
import com.navaneeth.filer.ui.components.rememberPluginReady
import com.navaneeth.filer.ui.components.AudioPlayer
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.ExpressiveLoader
import com.navaneeth.filer.ui.components.FileThumbnail
import com.navaneeth.filer.ui.components.NameSuggestionCard
import com.navaneeth.filer.ui.components.PhotoTextCard
import com.navaneeth.filer.ui.components.TagsCard
import com.navaneeth.filer.ui.components.TranscriptCard
import com.navaneeth.filer.ui.components.rememberAudioPlayer
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.FileActions
import com.navaneeth.filer.ui.util.LocalOpenDetails
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.AiViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Which files Filer's own viewer shows (the others open in their default app). */
object FileViewer {
    private val TEXT = setOf("txt", "md", "csv", "tsv", "log", "json", "xml", "yaml", "yml", "ini", "conf", "html", "htm", "srt", "vtt")
    const val MAX_TEXT_BYTES = 512 * 1024

    fun canShow(item: FileUiItem): Boolean {
        if (item.isDirectory) return false
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return when (item.kind) {
            FileKind.PDF, FileKind.AUDIO, FileKind.CODE -> true
            else -> ext in TEXT
        }
    }
}

/**
 * Filer's viewer for PDFs, text and code files, and recordings: the file itself, with the AI
 * plugins one tap away (tags, a clearer name, a transcript).
 * [plugins] false for files in the Recycle bin or Locked folder: view only, nothing is read or kept.
 */
@Composable
fun FileViewerScreen(path: String, plugins: Boolean, ai: AiViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val openDetails = LocalOpenDetails.current
    val noAppMessage = stringResource(R.string.error_no_app)
    var currentPath by remember { mutableStateOf(path) }
    val file = remember(currentPath) { File(currentPath) }
    val item = remember(currentPath) {
        FileUiItem(
            uri = android.net.Uri.fromFile(file).toString(),
            name = file.name,
            mimeType = Storage.mimeTypeOf(file.name),
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            location = file.parentFile?.name,
            documentId = file.path
        )
    }
    val pluginReady = rememberPluginReady(ai)
    fun ready(plugin: AiPlugin) = plugins && pluginReady(plugin)
    var showAi by remember { mutableStateOf(false) }
    val player = if (item.kind == FileKind.AUDIO) rememberAudioPlayer(currentPath) else null


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (!FileActions.openWith(context, item)) scope.launch { snackbar.showSnackbar(noAppMessage) }
                    }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.action_open_with)) }
                    IconButton(onClick = { FileActions.share(context, item) }) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    if (plugins && openDetails != null) {
                        IconButton(onClick = { openDetails(item) }) {
                            Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.action_details))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // Always offered (tags need no plugin), except for bin/Locked files.
            if (plugins && file.isFile) AiButton(currentPath, ai) { haptics.tick(); showAi = true }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !file.isFile -> EmptyState(
                    icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
                    title = stringResource(R.string.file_missing_title),
                    body = stringResource(R.string.file_missing_body)
                )
                item.kind == FileKind.PDF -> PdfPages(file)
                item.kind == FileKind.AUDIO && player != null -> AudioView(item, player, ai, ready(AiPlugin.SPEECH))
                else -> TextView(file, monospace = item.kind == FileKind.CODE || file.extension.lowercase() in setOf("csv", "tsv", "json", "xml", "log"))
            }
        }
    }

    if (showAi) {
        AiPanelSheet(item = item, ai = ai, player = player, onRenamed = { newPath -> currentPath = newPath }, onDismiss = { showAi = false })
    }
}

// region PDF

/** A PDF's pages, rendered as they scroll into view; a few are kept in memory. */
@Composable
private fun PdfPages(file: File) {
    val density = LocalDensity.current
    val widthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }.coerceAtMost(1440)
    val document by produceState<PdfDocument?>(null, file) {
        value = withContext(Dispatchers.IO) { PdfDocument.open(file) }
    }
    val doc = document
    // Close the document this effect was started for (reading the state here would close the new one).
    DisposableEffect(doc) { onDispose { doc?.close() } }
    when {
        doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ExpressiveLoader(size = 56.dp) }
        doc.pageCount == 0 -> EmptyState(
            icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
            title = stringResource(R.string.viewer_cant_preview),
            body = stringResource(R.string.viewer_cant_preview_body)
        )
        else -> LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(doc.pageCount) { index ->
                val page by produceState<Bitmap?>(doc.cached(index), doc, index) {
                    if (value == null) value = doc.render(index, widthPx)
                }
                Surface(shape = RoundedCornerShape(4.dp), color = Color.White, shadowElevation = 1.dp) {
                    val bitmap = page
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.viewer_page, index + 1, doc.pageCount),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(doc.aspect(index)), contentAlignment = Alignment.Center) {
                            ExpressiveLoader(size = 32.dp)
                        }
                    }
                }
            }
        }
    }
}

/** PdfRenderer allows one open page at a time, so rendering is serialised; recent pages are cached. */
private class PdfDocument(private val fd: ParcelFileDescriptor?, private val renderer: PdfRenderer?) {

    private val lock = Mutex()
    private val cache = object : LruCache<Int, Bitmap>(6) {}
    private val aspects = HashMap<Int, Float>()
    val pageCount: Int = renderer?.pageCount ?: 0

    fun cached(index: Int): Bitmap? = cache.get(index)
    fun aspect(index: Int): Float = aspects[index] ?: (1f / 1.414f)

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            cache.get(index)?.let { return@withLock it }
            val r = renderer ?: return@withLock null
            runCatching {
                r.openPage(index).use { page ->
                    aspects[index] = page.width.toFloat() / page.height
                    val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(index, bitmap)
                    }
                }
            }.getOrNull()
        }
    }

    fun close() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
    }

    companion object {
        /** A PDF that can't be opened (damaged or password-protected). */
        val UNREADABLE = PdfDocument(null, null)

        fun open(file: File): PdfDocument {
            val fd = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() ?: return UNREADABLE
            return runCatching { PdfDocument(fd, PdfRenderer(fd)) }.getOrElse {
                fd.close()
                UNREADABLE
            }
        }
    }
}

// endregion

// region Text

@Composable
private fun TextView(file: File, monospace: Boolean) {
    val content by produceState<Pair<String, Boolean>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // readNBytes needs Android 13; read up to the cap by hand.
                val bytes = file.inputStream().use { input ->
                    val buffer = ByteArray(minOf(file.length(), FileViewer.MAX_TEXT_BYTES.toLong()).toInt())
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
                String(bytes, Charsets.UTF_8) to (file.length() > FileViewer.MAX_TEXT_BYTES)
            }.getOrDefault("" to false)
        }
    }
    val text = content
    if (text == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ExpressiveLoader(size = 56.dp) }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .then(if (monospace) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
    ) {
        SelectionContainer {
            Text(
                text.first,
                style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge
            )
        }
        if (text.second) {
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.viewer_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// endregion

// region Audio

@Composable
private fun AudioView(item: FileUiItem, player: AudioPlayer, ai: AiViewModel, speechReady: Boolean) {
    val haptics = rememberHaptics()
    LaunchedEffect(player) { player.prepare() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FileThumbnail(
            item = item,
            preview = true,
            previewWidthPx = 720,
            iconSize = 72.dp,
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier.padding(24.dp).size(240.dp)
        )
        Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.size(16.dp))
        var dragging by remember { mutableStateOf<Float?>(null) }
        val duration = player.durationMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo((it * duration).toLong()) }
                dragging = null
            },
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            Text(DateUtils.formatElapsedTime(player.positionMs / 1000), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(DateUtils.formatElapsedTime(player.durationMs / 1000), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.size(8.dp))
        FilledIconButton(
            onClick = { haptics.tick(); player.toggle() },
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors()
        ) {
            Icon(
                if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (player.playing) R.string.ai_pause else R.string.ai_play),
                modifier = Modifier.size(36.dp)
            )
        }
        // With speech installed, the transcript sits right under the player.
        if (speechReady) {
            Spacer(Modifier.size(16.dp))
            TranscriptCard(path = item.documentId!!, ai = ai, sharedPlayer = player)
        }
    }
}

// endregion
