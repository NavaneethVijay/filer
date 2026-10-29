package com.navaneeth.filer.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.navaneeth.filer.data.storage.IndexKind
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("filer_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_LAST_SCAN_TIME = "last_scan_time"
        private const val KEY_ACCELERATOR = "accelerator_mode"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_INDEXED_KINDS = "indexed_kinds"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_VECTOR_INDEX_VERSION = "vector_index_version"
        private const val KEY_SHOW_ADVANCED = "show_advanced"
        private const val KEY_SMART_SEARCH_BANNER_DISMISSED = "smart_search_banner_dismissed"
        private const val KEY_FOLDER_SORT = "folder_sort"
        private const val KEY_FOLDER_GRID = "folder_grid"
        private const val KEY_FULL_REINDEX_PENDING = "full_reindex_pending"
        private const val KEY_RECENT_GRID = "recent_grid"
        private const val KEY_COLLECTION_GRID = "collection_grid"
        private const val KEY_BUILT_IN_VIEWER = "built_in_viewer"
        private const val KEY_PHONE_COLORS = "phone_colors"
        private const val KEY_INDEXING_PAUSED_BY_USER = "indexing_paused_by_user"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_INDEX_WITH_MAC = "index_with_mac"
        private const val KEY_PHOTO_TEXT_BACKGROUND = "photo_text_background"
        private const val KEY_SUGGEST_NAMES = "suggest_names"
        private const val KEY_SPEECH_LANGUAGE = "speech_language"
        const val ACCELERATOR_AUTO = "AUTO"
        const val ACCELERATOR_CPU = "CPU"
        // 2 = Google EmbeddingGemma 2 multimodal weights; 3 = EmbeddingGemma 2 task prompts
        const val CURRENT_INDEX_VERSION = 3
    }

    var vectorIndexVersion: Int
        get() = prefs.getInt(KEY_VECTOR_INDEX_VERSION, 0)
        set(value) {
            prefs.edit().putInt(KEY_VECTOR_INDEX_VERSION, value).commit()
        }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).commit()
        }

    /**
     * Determines whether the app setup (model download + source config) is completed.
     * If the model file is already present on disk (485MB) or onboarding was marked completed,
     * the app will bypass onboarding directly to HomeScreen.
     */
    val isAppSetupReady: Boolean
        get() {
            if (isOnboardingCompleted) return true
            val modelFile = File(context.filesDir, "models/embeddinggemma-2-740m.litertlm")
            if (modelFile.exists() && modelFile.length() > 50 * 1024 * 1024L) {
                isOnboardingCompleted = true
                return true
            }
            return false
        }

    var lastScanTime: Long
        get() = prefs.getLong(KEY_LAST_SCAN_TIME, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SCAN_TIME, value).commit()
        }

    var acceleratorMode: String
        get() = if (prefs.getString(KEY_ACCELERATOR, ACCELERATOR_AUTO) == ACCELERATOR_CPU) ACCELERATOR_CPU else ACCELERATOR_AUTO
        set(value) {
            prefs.edit().putString(KEY_ACCELERATOR, value).commit()
        }

    /** File kinds read by smart search (default: all). Others are found by name only. */
    var indexedKinds: Set<IndexKind>
        get() = prefs.getStringSet(KEY_INDEXED_KINDS, null)
            ?.mapNotNull { name -> IndexKind.entries.firstOrNull { it.name == name } }?.toSet()
            ?: IndexKind.entries.toSet()
        set(value) {
            prefs.edit().putStringSet(KEY_INDEXED_KINDS, value.mapTo(HashSet()) { it.name }).commit()
        }

    var isChargingOnly: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CHARGING_ONLY, value).commit()
        }

    var isWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).commit()
        }

    /** Reveals indexing status, logs, model controls and match scores for power users. */
    var showAdvanced: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ADVANCED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_ADVANCED, value).apply()
        }

    var isSmartSearchBannerDismissed: Boolean
        get() = prefs.getBoolean(KEY_SMART_SEARCH_BANNER_DISMISSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SMART_SEARCH_BANNER_DISMISSED, value).apply()
        }

    var folderSort: String
        get() = prefs.getString(KEY_FOLDER_SORT, "NAME") ?: "NAME"
        set(value) {
            prefs.edit().putString(KEY_FOLDER_SORT, value).apply()
        }

    /** Set when every file must be re-embedded (new model); cleared once the reset is done. */
    var isFullReindexPending: Boolean
        get() = prefs.getBoolean(KEY_FULL_REINDEX_PENDING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FULL_REINDEX_PENDING, value).commit()
        }

    var isIndexingPausedByUser: Boolean
        get() = prefs.getBoolean(KEY_INDEXING_PAUSED_BY_USER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INDEXING_PAUSED_BY_USER, value).commit()
        }

    val indexingPausedByUserFlow: Flow<Boolean> get() = observe(KEY_INDEXING_PAUSED_BY_USER) { isIndexingPausedByUser }

    /** Unlocked by tapping the version 7 times in Privacy & about. */
    var isDeveloperMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEVELOPER_MODE, value).commit()
        }

    /** Developer mode: indexing runs the model on a USB-connected Mac when it's reachable. */
    var isIndexWithMac: Boolean
        get() = prefs.getBoolean(KEY_INDEX_WITH_MAC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INDEX_WITH_MAC, value).commit()
        }

    /** Both switches on: indexing should try the Mac. */
    val wantsMacIndexing: Boolean get() = isDeveloperMode && isIndexWithMac

    val developerModeFlow: Flow<Boolean> get() = observe(KEY_DEVELOPER_MODE) { isDeveloperMode }
    val indexWithMacFlow: Flow<Boolean> get() = observe(KEY_INDEX_WITH_MAC) { isIndexWithMac }

    var isFolderGridView: Boolean
        get() = prefs.getBoolean(KEY_FOLDER_GRID, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FOLDER_GRID, value).apply()
        }

    var isRecentGridView: Boolean
        get() = prefs.getBoolean(KEY_RECENT_GRID, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RECENT_GRID, value).apply()
        }

    /** Photos open in Filer's own viewer (default) instead of the phone's default app. */
    var isBuiltInViewer: Boolean
        get() = prefs.getBoolean(KEY_BUILT_IN_VIEWER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BUILT_IN_VIEWER, value).apply()
        }

    val builtInViewerFlow: Flow<Boolean> get() = observe(KEY_BUILT_IN_VIEWER) { isBuiltInViewer }

    /** Colours from the phone's wallpaper (Material You); off keeps Filer's own blue. */
    var usePhoneColors: Boolean
        get() = prefs.getBoolean(KEY_PHONE_COLORS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PHONE_COLORS, value).apply()
        }

    val phoneColorsFlow: Flow<Boolean> get() = observe(KEY_PHONE_COLORS) { usePhoneColors }

    var isCollectionGridView: Boolean
        get() = prefs.getBoolean(KEY_COLLECTION_GRID, false)
        set(value) {
            prefs.edit().putBoolean(KEY_COLLECTION_GRID, value).apply()
        }

    val fullReindexPendingFlow: Flow<Boolean> get() = observe(KEY_FULL_REINDEX_PENDING) { isFullReindexPending }
    val recentGridViewFlow: Flow<Boolean> get() = observe(KEY_RECENT_GRID) { isRecentGridView }
    val collectionGridViewFlow: Flow<Boolean> get() = observe(KEY_COLLECTION_GRID) { isCollectionGridView }
    val showAdvancedFlow: Flow<Boolean> get() = observe(KEY_SHOW_ADVANCED) { showAdvanced }
    val smartSearchBannerDismissedFlow: Flow<Boolean> get() = observe(KEY_SMART_SEARCH_BANNER_DISMISSED) { isSmartSearchBannerDismissed }
    val indexedKindsFlow: Flow<Set<IndexKind>> get() = observe(KEY_INDEXED_KINDS) { indexedKinds }
    val chargingOnlyFlow: Flow<Boolean> get() = observe(KEY_CHARGING_ONLY) { isChargingOnly }
    val wifiOnlyFlow: Flow<Boolean> get() = observe(KEY_WIFI_ONLY) { isWifiOnly }
    val acceleratorModeFlow: Flow<String> get() = observe(KEY_ACCELERATOR) { acceleratorMode }
    val folderSortFlow: Flow<String> get() = observe(KEY_FOLDER_SORT) { folderSort }
    val folderGridViewFlow: Flow<Boolean> get() = observe(KEY_FOLDER_GRID) { isFolderGridView }

    // AI plugins

    /** A plugin the user switched off keeps its model but does nothing until switched on again. */
    fun isPluginEnabled(name: String): Boolean = prefs.getBoolean("plugin_on_$name", true)

    fun setPluginEnabled(name: String, enabled: Boolean) {
        prefs.edit().putBoolean("plugin_on_$name", enabled).apply()
    }

    fun pluginEnabledFlow(name: String): Flow<Boolean> = observe("plugin_on_$name") { isPluginEnabled(name) }

    /** Text in photos: read photos already on the phone while it charges (off by default). */
    var readPhotosInBackground: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_TEXT_BACKGROUND, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PHOTO_TEXT_BACKGROUND, value).apply()
        }
    val readPhotosInBackgroundFlow: Flow<Boolean> get() = observe(KEY_PHOTO_TEXT_BACKGROUND) { readPhotosInBackground }

    /** Language model: suggest clearer names for files named like IMG_1234 (on by default). */
    var suggestNames: Boolean
        get() = prefs.getBoolean(KEY_SUGGEST_NAMES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SUGGEST_NAMES, value).apply()
        }
    val suggestNamesFlow: Flow<Boolean> get() = observe(KEY_SUGGEST_NAMES) { suggestNames }

    /** Speech: language code ("hi", "en"…) or empty to detect it per recording. */
    var speechLanguage: String
        get() = prefs.getString(KEY_SPEECH_LANGUAGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SPEECH_LANGUAGE, value).apply()
        }
    val speechLanguageFlow: Flow<String> get() = observe(KEY_SPEECH_LANGUAGE) { speechLanguage }

    private fun <T> observe(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
