package com.navaneeth.filer.ui.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.viewer.LocalPhotoViewer
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat

/** The activity behind a Compose context (it may be wrapped, e.g. for theming). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** App-wide snackbar host so any screen can report a failed action. */
val LocalSnackbarHostState = compositionLocalOf { SnackbarHostState() }

/**
 * Opens a PDF, text file or recording in Filer's own viewer (with the AI plugins); the Boolean
 * is whether plugins may run on it. Null where the viewer isn't reachable.
 */
val LocalOpenViewer = staticCompositionLocalOf<((FileUiItem, Boolean) -> Unit)?> { null }

/** Opens a file's details (plugins, tags, info); null where details aren't reachable. */
val LocalOpenDetails = staticCompositionLocalOf<((FileUiItem) -> Unit)?> { null }

/** Open, open-with and share, all through the system so the user picks the app. */
object FileActions {

    /** Opens with the user's default app; Android shows its own chooser when there is none. */
    fun open(context: Context, item: FileUiItem): Boolean =
        start(context, Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, item), resolveMime(context, item)))

    /** Always asks which app to use, even when a default is set. */
    fun openWith(context: Context, item: FileUiItem): Boolean {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, item), resolveMime(context, item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(view, context.getString(R.string.action_open_with)))
    }

    fun share(context: Context, item: FileUiItem): Boolean {
        val send = Intent(Intent.ACTION_SEND)
            .setType(resolveMime(context, item))
            .putExtra(Intent.EXTRA_STREAM, shareableUri(context, item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(send, null))
    }

    /** Shares several files at once (folders can't be shared). */
    fun shareAll(context: Context, items: List<FileUiItem>): Boolean {
        val files = items.filterNot { it.isDirectory }
        if (files.isEmpty()) return false
        if (files.size == 1) return share(context, files.first())
        val uris = ArrayList(files.map { shareableUri(context, it) })
        val mimes = files.map { resolveMime(context, it) }.distinct()
        val type = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(send, null))
    }

    /** Local files are exposed through our FileProvider; other apps can't read file:// URIs. */
    private fun shareableUri(context: Context, item: FileUiItem): Uri {
        val uri = Uri.parse(item.uri)
        if (uri.scheme != ContentResolver.SCHEME_FILE) return uri
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(uri.path)))
        }.getOrDefault(uri)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun resolveMime(context: Context, item: FileUiItem): String {
        val stored = item.mimeType
        if (stored.isNotBlank() && stored != "application/octet-stream") return stored
        return runCatching { context.contentResolver.getType(Uri.parse(item.uri)) }.getOrNull() ?: "*/*"
    }
}

/** Localized "1.2 MB". */
fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** Time for today's files, otherwise a short date, the way the system Files app does it. */
fun formatModified(millis: Long): String =
    if (millis <= 0L) "" else DateUtils.formatSameDayTime(millis, System.currentTimeMillis(), DateFormat.MEDIUM, DateFormat.SHORT).toString()

/**
 * Returns a function that opens a file: photos in Filer's viewer (swiping through the photos in
 * [siblings], the list on screen) unless the user chose their default app in Settings; everything
 * else in its default app, reporting failure with a snackbar. [manageable] false keeps the viewer
 * to view/share/open-with (files in the Recycle bin or Locked folder).
 */
@Composable
fun rememberFileOpener(siblings: List<FileUiItem> = emptyList(), manageable: Boolean = true): (FileUiItem) -> Unit {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val viewer = LocalPhotoViewer.current
    val documentViewer = LocalOpenViewer.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val message = stringResource(R.string.error_no_app)
    val prefs = remember(context) { PreferencesManager(context) }
    val latestSiblings by rememberUpdatedState(siblings)
    return remember(context, snackbar, message, viewer, documentViewer) {
        { item ->
            if ((item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO) && prefs.isBuiltInViewer) {
                viewer.open(item, latestSiblings, manageable)
            } else if (documentViewer != null && prefs.isBuiltInViewer && item.pathOrNull() != null && com.navaneeth.filer.ui.viewer.FileViewer.canShow(item)) {
                documentViewer(item, manageable)
            } else if (!FileActions.open(context, item)) {
                haptics.reject()
                scope.launch { snackbar.showSnackbar(message) }
            }
        }
    }
}
