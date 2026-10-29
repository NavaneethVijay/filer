package com.navaneeth.filer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import com.navaneeth.filer.R
import com.navaneeth.filer.data.media.MediaCategory
import com.navaneeth.filer.ui.model.FileKind

val MediaCategory.label: Int
    get() = when (this) {
        MediaCategory.IMAGES -> R.string.category_images
        MediaCategory.VIDEOS -> R.string.category_videos
        MediaCategory.AUDIO -> R.string.category_audio
        MediaCategory.DOCUMENTS -> R.string.category_documents
        MediaCategory.DOWNLOADS -> R.string.category_downloads
        MediaCategory.APKS -> R.string.category_apks
    }

val MediaCategory.icon: ImageVector
    get() = when (this) {
        MediaCategory.IMAGES -> Icons.Rounded.Image
        MediaCategory.VIDEOS -> Icons.Rounded.VideoFile
        MediaCategory.AUDIO -> Icons.Rounded.AudioFile
        MediaCategory.DOCUMENTS -> Icons.Rounded.Description
        MediaCategory.DOWNLOADS -> Icons.Rounded.Download
        MediaCategory.APKS -> Icons.Rounded.Android
    }

/** The file-type tint each category borrows. */
val MediaCategory.fileKind: FileKind
    get() = when (this) {
        MediaCategory.IMAGES -> FileKind.IMAGE
        MediaCategory.VIDEOS -> FileKind.VIDEO
        MediaCategory.AUDIO -> FileKind.AUDIO
        MediaCategory.DOCUMENTS -> FileKind.DOCUMENT
        MediaCategory.DOWNLOADS -> FileKind.ARCHIVE
        MediaCategory.APKS -> FileKind.APK
    }
