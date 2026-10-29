package com.navaneeth.filer.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.LruCache
import android.util.Size
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color as ComposeColor

data class KindColors(val container: ComposeColor, val content: ComposeColor)

val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.FOLDER -> Icons.Rounded.Folder
        FileKind.IMAGE -> Icons.Rounded.Image
        FileKind.VIDEO -> Icons.Rounded.VideoFile
        FileKind.AUDIO -> Icons.Rounded.AudioFile
        FileKind.PDF -> Icons.Rounded.PictureAsPdf
        FileKind.DOCUMENT -> Icons.Rounded.Description
        FileKind.SPREADSHEET -> Icons.Rounded.TableChart
        FileKind.PRESENTATION -> Icons.Rounded.Slideshow
        FileKind.CODE -> Icons.Rounded.Code
        FileKind.ARCHIVE -> Icons.Rounded.FolderZip
        FileKind.APK -> Icons.Rounded.Android
        FileKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }

@get:StringRes
val FileKind.label: Int
    get() = when (this) {
        FileKind.FOLDER -> R.string.kind_folder
        FileKind.IMAGE -> R.string.kind_image
        FileKind.VIDEO -> R.string.kind_video
        FileKind.AUDIO -> R.string.kind_audio
        FileKind.PDF -> R.string.kind_pdf
        FileKind.DOCUMENT -> R.string.kind_document
        FileKind.SPREADSHEET -> R.string.kind_spreadsheet
        FileKind.PRESENTATION -> R.string.kind_presentation
        FileKind.CODE -> R.string.kind_code
        FileKind.ARCHIVE -> R.string.kind_archive
        FileKind.APK -> R.string.kind_apk
        FileKind.OTHER -> R.string.kind_other
    }

/**
 * Type tile colours, tonal like the rest of the app: folders on the muted accent tint, files on a
 * neutral tile with the type icon in the theme colour. The icon tells the type, not a hue.
 */
@Composable
fun FileKind.colors(): KindColors = when (this) {
    FileKind.FOLDER -> Accent.BLUE.colors().let { KindColors(it.container, it.strong) }
    FileKind.OTHER -> KindColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    else -> KindColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.secondary)
}


/**
 * A file's visual, like the Files and Gallery apps: the photo itself for images, a frame with a play
 * badge for videos, and — when [preview] is on (the default) — the first page of a PDF, a song's album
 * art and an APK's app icon. The tinted type icon is always drawn underneath; previews fade in over it,
 * so nothing pops and files without a preview (or that fail to load) simply keep the icon.
 */
@Composable
fun FileThumbnail(
    item: FileUiItem,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    iconSize: Dp = 24.dp,
    preview: Boolean = true,
    previewWidthPx: Int = 360
) {
    val kind = item.kind
    val colors = kind.colors()
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (kind == FileKind.FOLDER) ComposeColor.Transparent else colors.container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(if (kind == FileKind.FOLDER) iconSize * 1.25f else iconSize)
        )
        when (kind) {
            FileKind.IMAGE -> {
                val context = LocalContext.current
                // Locked-folder files live in app-private storage: never let them reach a disk cache.
                val private = remember(item.uri) { isAppPrivate(context, item.pathOrNull()) }
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(previewWidthPx)
                        .crossfade(true)
                        .apply { if (private) diskCachePolicy(CachePolicy.DISABLED) }
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            FileKind.VIDEO -> LoadedPreview(item, previewWidthPx, colors.container)
            FileKind.PDF, FileKind.AUDIO, FileKind.APK -> if (preview) LoadedPreview(item, previewWidthPx, colors.container)
            else -> Unit
        }
    }
}

/** A preview from [ThumbCache], fading in over the type icon, with the badge its type uses. */
@Composable
private fun LoadedPreview(item: FileUiItem, widthPx: Int, container: ComposeColor) {
    val thumb = rememberThumb(item, widthPx)
    AnimatedVisibility(visible = thumb != null, enter = fadeIn(tween(220)), modifier = Modifier.fillMaxSize()) {
        thumb?.let { t ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val big = maxWidth >= 96.dp
                if (item.kind == FileKind.APK) {
                    // App icons keep their own shape, centred on the type colour (covers the placeholder).
                    Box(Modifier.fillMaxSize().background(container), contentAlignment = Alignment.Center) {
                        Image(t.image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.72f))
                    }
                } else {
                    Image(
                        bitmap = t.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = if (item.kind == FileKind.PDF) Alignment.TopCenter else Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                when (item.kind) {
                    FileKind.VIDEO -> if (big) {
                        Surface(
                            shape = CircleShape,
                            color = ComposeColor.Black.copy(alpha = 0.55f),
                            contentColor = ComposeColor.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        ) {
                            Row(
                                Modifier.padding(start = 4.dp, end = if (t.durationMs != null) 8.dp else 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                t.durationMs?.let { ms ->
                                    Text(DateUtils.formatElapsedTime(ms / 1000), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else {
                        RoundBadge(Icons.Rounded.PlayArrow, Modifier.align(Alignment.Center))
                    }
                    // A page or cover can pass for a photo on big tiles, so say what it is.
                    FileKind.PDF, FileKind.AUDIO -> if (big) {
                        RoundBadge(item.kind.icon, Modifier.align(Alignment.BottomStart).padding(6.dp), iconSize = 12.dp)
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun RoundBadge(icon: ImageVector, modifier: Modifier, iconSize: Dp = 14.dp) {
    Surface(
        shape = CircleShape,
        color = ComposeColor.Black.copy(alpha = 0.5f),
        contentColor = ComposeColor.White,
        modifier = modifier.size(20.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun rememberThumb(item: FileUiItem, widthPx: Int): Thumb? {
    val context = LocalContext.current.applicationContext
    val key = ThumbCache.key(item, widthPx)
    var thumb by remember(key) { mutableStateOf(ThumbCache.cached(key)) }
    LaunchedEffect(key) {
        if (thumb == null) thumb = withContext(Dispatchers.IO) { ThumbCache.load(context, item, widthPx) }
    }
    return thumb
}

private class Thumb(val image: ImageBitmap, val durationMs: Long?, val bytes: Int)

private fun Bitmap.toThumb(durationMs: Long? = null) = Thumb(asImageBitmap(), durationMs, byteCount)

/** True for files in Filer's own private storage (the Locked folder): no MediaStore, no disk caches. */
private fun isAppPrivate(context: Context, path: String?): Boolean {
    if (path == null) return false
    val roots = listOfNotNull(context.dataDir.path, context.filesDir.parent)
    return roots.any { path.startsWith("$it/") }
}

/**
 * Previews for every type that has one: video frames, PDF first pages, album art and APK icons. One
 * memory-only LRU (nothing is written to disk), 3 loads at a time, cancelled with the composable, and
 * files that gave nothing (no art, password-protected or broken PDF, unparsable APK) are remembered so
 * scrolling past them again doesn't retry. Keys include the modified time, so edited files refresh.
 */
private object ThumbCache {
    private val cache = object : LruCache<String, Thumb>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Thumb) = value.bytes
    }
    private val failed = object : LruCache<String, Boolean>(1024) {}
    private val permits = Semaphore(3)

    fun key(item: FileUiItem, widthPx: Int) = "${item.kind}|${item.uri}|${item.modifiedAt}@$widthPx"
    private fun failKey(item: FileUiItem) = "${item.kind}|${item.uri}|${item.modifiedAt}"

    fun cached(key: String): Thumb? = cache.get(key)

    suspend fun load(context: Context, item: FileUiItem, widthPx: Int): Thumb? {
        val key = key(item, widthPx)
        cache.get(key)?.let { return it }
        if (failed.get(failKey(item)) != null) return null
        return permits.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val signal = CancellationSignal()
            val handle = currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }
            try {
                val thumb = runCatching { decode(context, item, widthPx, signal) }.getOrNull()
                currentCoroutineContext().ensureActive()
                if (thumb != null) cache.put(key, thumb) else failed.put(failKey(item), true)
                thumb
            } finally {
                handle.dispose()
            }
        }
    }

    private fun decode(context: Context, item: FileUiItem, widthPx: Int, signal: CancellationSignal): Thumb? {
        val uri = Uri.parse(item.uri)
        val path = item.pathOrNull() ?: mediaStorePath(context, uri)
        val private = isAppPrivate(context, path)
        val size = Size(widthPx, widthPx)
        return when (item.kind) {
            FileKind.VIDEO -> video(context, uri, path, private, size, signal)
            FileKind.PDF -> pdfFirstPage(context, uri, widthPx, signal)
            FileKind.AUDIO -> albumArt(context, uri, path, private, size, signal)
            FileKind.APK -> path?.let { apkIcon(context, it, widthPx) }
            else -> null
        }
    }

    // ---- Video: MediaStore's cached thumbnail when indexed, else a decoded frame ----

    private fun video(context: Context, uri: Uri, path: String?, private: Boolean, size: Size, signal: CancellationSignal): Thumb? {
        val media = mediaRow(context, uri, path, private, videosUri)
        if (media != null) {
            runCatching { context.contentResolver.loadThumbnail(media.first, size, signal) }.getOrNull()?.let {
                return it.toThumb(media.second)
            }
        }
        signal.throwIfCanceled()
        val bitmap = when {
            path != null -> ThumbnailUtils.createVideoThumbnail(File(path), size, signal)
            else -> MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.width, size.height)
            }
        } ?: return null
        val duration = media?.second ?: runCatching {
            MediaMetadataRetriever().use { retriever ->
                if (path != null) retriever.setDataSource(path) else retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
        return bitmap.toThumb(duration)
    }

    // ---- PDF: first page, top part, on white ----

    private fun pdfFirstPage(context: Context, uri: Uri, widthPx: Int, signal: CancellationSignal): Thumb? {
        // PdfRenderer takes ownership of the descriptor and closes it with the renderer.
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        // Throws for password-protected or broken files; the caller remembers that and shows the icon.
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
        return renderer.use {
            if (renderer.pageCount == 0) return null
            signal.throwIfCanceled()
            renderer.openPage(0).use { page ->
                if (page.width <= 0 || page.height <= 0) return null
                val scale = widthPx / page.width.toFloat()
                // Very tall pages: only the top is shown anyway, so don't render (or keep) the rest.
                val height = (page.height * scale).roundToInt().coerceIn(1, widthPx * 3 / 2)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.toThumb()
            }
        }
    }

    // ---- Audio: album art ----

    private fun albumArt(context: Context, uri: Uri, path: String?, private: Boolean, size: Size, signal: CancellationSignal): Thumb? {
        mediaRow(context, uri, path, private, audioUri)?.let { media ->
            runCatching { context.contentResolver.loadThumbnail(media.first, size, signal) }.getOrNull()?.let {
                return it.toThumb()
            }
        }
        signal.throwIfCanceled()
        val art = MediaMetadataRetriever().use { retriever ->
            if (path != null) retriever.setDataSource(path) else retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } ?: return null
        return decodeSampled(art, size.width)?.toThumb()
    }

    /** Decodes [bytes] at the smallest power-of-two scale that still covers [targetPx]. */
    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- APK: the app's icon ----

    @Suppress("DEPRECATION") // The int-flags overload is fine for flags = 0 on every supported API.
    private fun apkIcon(context: Context, path: String, widthPx: Int): Thumb? {
        val pm = context.packageManager
        val app = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
        // Without these the icon resources can't be read from the archive.
        app.sourceDir = path
        app.publicSourceDir = path
        val drawable = app.loadIcon(pm)
        val side = widthPx.coerceIn(48, 256)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, side, side)
        drawable.draw(Canvas(bitmap))
        return bitmap.toThumb()
    }

    // ---- MediaStore lookups ----

    private val videosUri: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val audioUri: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun isMediaStore(uri: Uri) = uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY

    /** The MediaStore row (and length) for this file, or null if it isn't indexed — never for private files. */
    private fun mediaRow(context: Context, uri: Uri, path: String?, private: Boolean, collection: Uri): Pair<Uri, Long?>? = when {
        isMediaStore(uri) -> uri to mediaDuration(context, uri)
        path != null && !private -> mediaStoreByPath(context, collection, path)
        else -> null
    }

    @Suppress("DEPRECATION") // DATA is still the way to look a path up with all-files access.
    private fun mediaStoreByPath(context: Context, collection: Uri, path: String): Pair<Uri, Long?>? = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DURATION),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val duration = if (c.isNull(1)) null else c.getLong(1).takeIf { it > 0 }
            ContentUris.withAppendedId(collection, c.getLong(0)) to duration
        }
    }.getOrNull()

    /** The file path behind a MediaStore URI (APKs can only be parsed from a path). */
    @Suppress("DEPRECATION")
    private fun mediaStorePath(context: Context, uri: Uri): String? = if (!isMediaStore(uri)) null else runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    private fun mediaDuration(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DURATION), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()
}
