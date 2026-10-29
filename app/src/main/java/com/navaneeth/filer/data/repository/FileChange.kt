package com.navaneeth.filer.data.repository

/**
 * What a file operation in Filer did, so the index can follow it directly instead of re-scanning
 * every smart-search folder. Paths are absolute; folders include everything inside them.
 */
sealed interface FileChange {
    /** Renamed or moved: the index keeps its passages and vectors under the new path. */
    data class Moved(val from: String, val to: String) : FileChange

    /** Copied: the copy reuses the original's passages and vectors. */
    data class Copied(val from: String, val to: String) : FileChange

    /** Deleted for good, or moved into the Locked folder (nothing about it stays in the index). */
    data class Removed(val path: String) : FileChange

    /**
     * Moved to the Recycle bin at [to]: hidden from search, but indexed files keep their vectors so
     * Undo/Restore (a [Moved] back) needs no model work.
     */
    data class Parked(val from: String, val to: String) : FileChange

    /** Appeared from outside smart search (restored from the bin, unlocked): queued for embedding. */
    data class Added(val path: String) : FileChange
}
