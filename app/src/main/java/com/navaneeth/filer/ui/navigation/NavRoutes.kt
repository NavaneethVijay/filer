package com.navaneeth.filer.ui.navigation

import kotlinx.serialization.Serializable

sealed interface Screen {
    // First launch
    @Serializable data object Welcome : Screen
    @Serializable data object AllFilesAccess : Screen
    @Serializable data object SmartFoldersSetup : Screen

    // Top-level destinations (bottom navigation)
    @Serializable data object Browse : Screen
    @Serializable data object Storage : Screen
    /** The Insights tab (shares the Storage scan). */
    @Serializable data object StorageInsights : Screen

    // Browsing
    /** [path] is an absolute path on local storage. */
    @Serializable data class Folder(val path: String) : Screen
    /** [kind] is a [com.navaneeth.filer.data.media.MediaCategory] name. */
    @Serializable data class Collection(val kind: String) : Screen
    @Serializable data object Favorites : Screen
    @Serializable data object RecycleBin : Screen
    @Serializable data object LockedFolder : Screen
    /** All recent files ("See all" on Browse). */
    @Serializable data object Recent : Screen
    /** One day's files; [dayStart] is local midnight in millis. */
    @Serializable data class RecentDay(val dayStart: Long) : Screen

    // Storage
    /** [kind] is a [com.navaneeth.filer.data.analysis.CleanupKind] name, or null for everything. */
    @Serializable data class StorageReview(val kind: String? = null) : Screen
    @Serializable data object StorageDuplicates : Screen
    /** [path] is the folder to start in, or null for the volume root. */
    @Serializable data class SpaceMap(val path: String? = null) : Screen

    // Shown only with "Show indexing details" on
    @Serializable data object IndexStatus : Screen
    /** [status] is an [com.navaneeth.filer.data.model.IndexStatus] name, or null for all files. */
    @Serializable data class IndexedFiles(val status: String? = null) : Screen
    @Serializable data object ModelSettings : Screen

    /**
     * One file's details. [fileId] for an indexed file (-1 if none), else [uri] (any file on the phone).
     * [fromSearch] adds "Why it matched" from the current search results.
     */
    @Serializable data class FileDetails(val fileId: Long = -1, val uri: String? = null, val fromSearch: Boolean = false) : Screen

    /** Opened from the gear on Browse's header. */
    @Serializable data object Settings : Screen
    @Serializable data object PrivacyAbout : Screen

    // AI plugins
    @Serializable data object AiPlugins : Screen
    /** All files with [tag] (no leading #). */
    @Serializable data class TagFiles(val tag: String) : Screen
    /** All tags: rename, delete, remove suggested ones. */
    @Serializable data object ManageTags : Screen
    /** Filer's viewer for a PDF, text file or recording; [plugins] false for bin/Locked files. */
    @Serializable data class Viewer(val path: String, val plugins: Boolean = true) : Screen
}
