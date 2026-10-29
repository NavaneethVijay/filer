package com.navaneeth.filer.data.analysis

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Finds files with identical contents. Only files of equal size can match, so most files are never
 * read. Same-size files are first told apart by their first and last few KB, and only files that
 * still match are read in full.
 *
 * Hashes are remembered by path, size and date, so a rescan after deleting something is quick.
 */
internal class DuplicateFinder {

    private data class HashKey(val path: String, val size: Long, val modifiedAt: Long)

    private val fullHashes = HashMap<HashKey, String>()

    /**
     * Groups of identical files, biggest saving first. In each group the copy to keep comes first
     * (see [keepOrder]); [volumeRoot] is used to tell where each copy lives.
     */
    suspend fun find(
        candidates: List<FoundFile>,
        volumeRoot: String,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit
    ): List<DuplicateGroup> {
        val sameSize = candidates.groupBy { it.sizeBytes }.values.filter { it.size > 1 }
        val total = sameSize.sumOf { group -> group.sumOf { it.sizeBytes } }
        var done = 0L
        onProgress(done, total)

        val groups = ArrayList<DuplicateGroup>()
        for (group in sameSize) {
            // Files that can't be read get a null hash and are left out, never grouped together.
            val bySample = group.groupBy { sampleHash(it) }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (similar in bySample) {
                val identical = similar.groupBy { fullHash(it) }.filterKeys { it != null }.values.filter { it.size > 1 }
                identical.forEach { copies ->
                    groups += DuplicateGroup(copies.first().sizeBytes, copies.sortedWith(keepOrder(volumeRoot)))
                }
            }
            done += group.sumOf { it.sizeBytes }
            onProgress(done, total)
        }
        return groups.sortedByDescending { it.extraBytes }
    }

    /** Forgets hashes of files that are gone, so memory doesn't grow across rescans. */
    fun retainOnly(paths: Set<String>) {
        fullHashes.keys.retainAll { it.path in paths }
    }

    private fun sampleHash(file: FoundFile): String? = runCatching {
        RandomAccessFile(file.path, "r").use { raf ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(SAMPLE_BYTES)
            val head = raf.read(buffer)
            if (head > 0) digest.update(buffer, 0, head)
            if (file.sizeBytes > SAMPLE_BYTES * 2L) {
                raf.seek(file.sizeBytes - SAMPLE_BYTES)
                val tail = raf.read(buffer)
                if (tail > 0) digest.update(buffer, 0, tail)
            }
            digest.digest().toHex()
        }
    }.getOrNull()

    private suspend fun fullHash(file: FoundFile): String? {
        val key = HashKey(file.path, file.sizeBytes, file.modifiedAt)
        fullHashes[key]?.let { return it }
        val hash = runCatching {
            FileInputStream(file.path).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(READ_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().toHex()
            }
        }.getOrNull() ?: return null
        fullHashes[key] = hash
        return hash
    }

    /**
     * The copy to keep: one in the camera or the user's own folders over one in Download or a chat
     * app, then the oldest (likely the original), then the shortest path.
     */
    private fun keepOrder(volumeRoot: String): Comparator<FoundFile> {
        fun place(file: FoundFile): Int {
            val rel = file.path.removePrefix(volumeRoot).trimStart('/').lowercase()
            return when (PathRules.source(rel)) {
                FileSource.CAMERA -> 0
                FileSource.OTHER, FileSource.SCREENSHOTS, FileSource.SCREEN_RECORDINGS -> 1
                FileSource.DOWNLOADS -> 2
                FileSource.WHATSAPP, FileSource.TELEGRAM -> 3
            }
        }
        return compareBy<FoundFile>({ place(it) }, { it.modifiedAt }, { it.path.length })
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val SAMPLE_BYTES = 64 * 1024
        const val READ_BUFFER = 256 * 1024
    }
}
