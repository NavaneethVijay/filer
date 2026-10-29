package com.navaneeth.filer.data.browser

import android.net.Uri
import com.navaneeth.filer.data.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One entry in a folder on local storage. */
data class BrowserItem(
    val uri: Uri,
    val path: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val isDirectory: Boolean,
    val childCount: Int
)

/** Lists folders on local storage for the file browser. */
class FolderBrowser {

    /** Null when the folder can't be read (missing, or access was revoked). */
    suspend fun list(path: String): List<BrowserItem>? = withContext(Dispatchers.IO) {
        val dir = File(path)
        val children = dir.listFiles() ?: return@withContext null
        children.filterNot { it.name.startsWith(".") }.map { file ->
            val isDir = file.isDirectory
            BrowserItem(
                uri = Uri.fromFile(file),
                path = file.path,
                name = file.name,
                mimeType = if (isDir) "" else Storage.mimeTypeOf(file.name),
                sizeBytes = if (isDir) 0L else file.length(),
                modifiedAt = file.lastModified(),
                isDirectory = isDir,
                childCount = if (isDir) file.list()?.count { !it.startsWith(".") } ?: 0 else 0
            )
        }
    }
}
