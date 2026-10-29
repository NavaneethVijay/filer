package com.navaneeth.filer.data.crawler

import android.net.Uri
import com.navaneeth.filer.data.storage.IndexKind
import com.navaneeth.filer.data.storage.IndexRules
import com.navaneeth.filer.data.storage.Storage
import java.io.File

data class DiscoveredFile(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val sourceId: Long
)

/**
 * Walks a smart-search folder on local storage. Direct file access (all-files permission) is far
 * faster than going through the Storage Access Framework, so even large folders list in seconds.
 */
class LocalFolderCrawler {

    fun crawl(sourceId: Long, root: File): List<DiscoveredFile> = walk(sourceId, root, null)

    /**
     * Every file the indexer can embed on the given storage volumes ("Whole phone"). Unlike folders,
     * other files (or kinds turned off in [kinds]) aren't recorded: the whole device is already
     * name-searchable through MediaStore.
     */
    fun crawlVolumes(sourceId: Long, roots: List<File>, kinds: Set<IndexKind>): List<DiscoveredFile> =
        roots.flatMap { walk(sourceId, it, kinds) }

    /** [onlyKinds] null: a folder, every file recorded. Otherwise a whole volume, only those kinds. */
    private fun walk(sourceId: Long, root: File, onlyKinds: Set<IndexKind>?): List<DiscoveredFile> {
        val wholeVolume = onlyKinds != null
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<DiscoveredFile>()
        // Iterative walk: deep trees can't overflow the stack.
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (file in children) {
                if (file.isDirectory) {
                    val appData = wholeVolume && dir == root && file.name == "Android"
                    if (!appData && !IndexRules.isSkippedDirectory(file)) stack.add(file)
                } else if (!file.name.startsWith(".")) {
                    val mimeType = Storage.mimeTypeOf(file.name)
                    val size = file.length()
                    if (onlyKinds != null && !IndexRules.isIndexable(file.name, mimeType, size, onlyKinds)) continue
                    out += DiscoveredFile(
                        uriString = Uri.fromFile(file).toString(),
                        displayName = file.name,
                        mimeType = mimeType,
                        sizeBytes = size,
                        modifiedAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                        sourceId = sourceId
                    )
                }
            }
        }
        return out
    }
}
