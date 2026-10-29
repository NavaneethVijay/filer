package com.navaneeth.filer.ui.screens.storage

import androidx.compose.ui.graphics.lerp
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.CleanupKind
import com.navaneeth.filer.data.analysis.ContentKind
import com.navaneeth.filer.data.analysis.FileSource
import com.navaneeth.filer.data.analysis.FoundFile
import com.navaneeth.filer.data.analysis.SpaceType
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.model.FileUiItem
import java.io.File

// How each storage concept looks and reads: labels, icons, colours and shapes in one place.

/** How a kind of clutter is drawn on its tile, review header and list rows. */
class CleanupLook(
    @StringRes val title: Int,
    @PluralsRes val body: Int,
    val icon: ImageVector,
    val accent: Accent,
    val shape: Shape
)

val CleanupKind.look: CleanupLook
    get() = when (this) {
        CleanupKind.LARGE_FILES -> CleanupLook(R.string.cleanup_large, R.plurals.cleanup_large_body, Icons.Rounded.PhotoSizeSelectLarge, Accent.BLUE, ExpressiveShapes.Squircle)
        CleanupKind.DUPLICATES -> CleanupLook(R.string.cleanup_duplicates, R.plurals.cleanup_duplicates_body, Icons.Rounded.ContentCopy, Accent.SKY, ExpressiveShapes.Cookie)
        CleanupKind.OLD_DOWNLOADS -> CleanupLook(R.string.cleanup_old_downloads, R.plurals.cleanup_old_downloads_body, Icons.Rounded.Download, Accent.AMBER, ExpressiveShapes.Flower)
        CleanupKind.CHAT_MEDIA -> CleanupLook(R.string.cleanup_chat_media, R.plurals.cleanup_chat_media_body, Icons.Rounded.Forum, Accent.BLUE, ExpressiveShapes.Squircle)
        CleanupKind.INSTALLERS -> CleanupLook(R.string.cleanup_installers, R.plurals.cleanup_installers_body, Icons.Rounded.Android, Accent.TEAL, ExpressiveShapes.Pill)
        CleanupKind.OLD_SCREENSHOTS -> CleanupLook(R.string.cleanup_old_screenshots, R.plurals.cleanup_old_screenshots_body, Icons.Rounded.Screenshot, Accent.SLATE, ExpressiveShapes.Cookie)
        CleanupKind.JUNK -> CleanupLook(R.string.cleanup_junk, R.plurals.cleanup_junk_body, Icons.Rounded.CleaningServices, Accent.GREEN, ExpressiveShapes.Flower)
        CleanupKind.SIMILAR_PHOTOS -> CleanupLook(R.string.cleanup_similar_photos, R.plurals.cleanup_similar_photos_body, Icons.Rounded.BurstMode, Accent.SKY, ExpressiveShapes.Flower)
        CleanupKind.FORWARDS -> CleanupLook(R.string.cleanup_forwards, R.plurals.cleanup_forwards_body, Icons.Rounded.Celebration, Accent.BLUE, ExpressiveShapes.Cookie)
        CleanupKind.VIDEOS_4K -> CleanupLook(R.string.cleanup_videos_4k, R.plurals.cleanup_videos_4k_body, Icons.Rounded.HighQuality, Accent.TEAL, ExpressiveShapes.Cookie)
        CleanupKind.SENSITIVE -> CleanupLook(R.string.cleanup_sensitive, R.plurals.cleanup_sensitive_body, Icons.Rounded.Lock, Accent.AMBER, ExpressiveShapes.Squircle)
    }

/** Kinds worked out by smart search (they only cover indexed files), marked as such. */
val CleanupKind.fromSmartSearch: Boolean
    get() = this == CleanupKind.SIMILAR_PHOTOS || this == CleanupKind.FORWARDS || this == CleanupKind.SENSITIVE

@get:StringRes
val ContentKind.label: Int
    get() = when (this) {
        ContentKind.SCENERY -> R.string.content_scenery
        ContentKind.PEOPLE -> R.string.content_people
        ContentKind.FOOD -> R.string.content_food
        ContentKind.PETS -> R.string.content_pets
        ContentKind.GREETINGS -> R.string.content_greetings
        ContentKind.CHATS -> R.string.content_chats
        ContentKind.DOCUMENTS -> R.string.content_documents
        ContentKind.RECEIPTS -> R.string.content_receipts
        ContentKind.IDS -> R.string.content_ids
    }

@get:StringRes
val SpaceType.label: Int
    get() = when (this) {
        SpaceType.VIDEOS -> R.string.space_videos
        SpaceType.PHOTOS -> R.string.space_photos
        SpaceType.AUDIO -> R.string.space_audio
        SpaceType.DOCUMENTS -> R.string.space_documents
        SpaceType.OTHER -> R.string.space_other
        SpaceType.APPS_AND_SYSTEM -> R.string.space_apps
    }

/**
 * Ring and legend colours: steps of one tonal family from the theme, so neighbours differ in
 * lightness rather than hue (and follow phone colours when those are on).
 */
@Composable
fun SpaceType.color(): Color {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        SpaceType.VIDEOS -> scheme.primary
        SpaceType.PHOTOS -> lerp(scheme.secondary, scheme.surface, 0.55f)
        SpaceType.AUDIO -> scheme.secondary
        SpaceType.DOCUMENTS -> lerp(scheme.primary, scheme.onSurface, 0.5f)
        SpaceType.OTHER -> lerp(scheme.secondary, scheme.surface, 0.78f)
        SpaceType.APPS_AND_SYSTEM -> scheme.outline
    }
}

@get:StringRes
val FileSource.label: Int
    get() = when (this) {
        FileSource.CAMERA -> R.string.source_camera
        FileSource.WHATSAPP -> R.string.source_whatsapp
        FileSource.TELEGRAM -> R.string.source_telegram
        FileSource.DOWNLOADS -> R.string.source_downloads
        FileSource.SCREENSHOTS -> R.string.source_screenshots
        FileSource.SCREEN_RECORDINGS -> R.string.source_screen_recordings
        FileSource.OTHER -> R.string.source_other
    }

/** A found file as a row the shared file UI understands (thumbnails, opening). */
fun FoundFile.toUiItem(): FileUiItem {
    // Empty folders are listed as junk with size 0; nothing else is a folder here.
    val isFolder = sizeBytes == 0L && File(path).isDirectory
    return FileUiItem(
        uri = Uri.fromFile(File(path)).toString(),
        name = name,
        mimeType = if (isFolder) "" else Storage.mimeTypeOf(name),
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        location = parentPath.substringAfterLast('/'),
        isDirectory = isFolder,
        documentId = path
    )
}

/** "11.6 GB": short, as on every card. */
fun Context.bytes(value: Long): String = Formatter.formatShortFileSize(this, value)
