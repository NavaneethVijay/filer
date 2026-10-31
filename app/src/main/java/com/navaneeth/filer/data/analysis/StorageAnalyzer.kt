package com.navaneeth.filer.data.analysis

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.data.repository.IndexRepository
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.data.storage.StorageVolumeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the Storage tab shows. [reports] keep the last finished scan while a new one runs. */
data class AnalyzerState(
    val reports: List<VolumeReport> = emptyList(),
    /** Null when no scan is running. */
    val stage: ScanStage? = null
)

/**
 * Scans storage for the Storage tab (on IO, one scan at a time) and keeps the result for the app's
 * lifetime. After Filer changes files, results drop what's gone at once and a fresh scan follows.
 */
@OptIn(FlowPreview::class)
class StorageAnalyzer private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val duplicateFinder = DuplicateFinder()
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(AnalyzerState())
    val state: StateFlow<AnalyzerState> = _state.asStateFlow()

    init {
        scope.launch {
            FileManager.getInstance(context).changes
                .onEach { dropMissingFiles() }
                .debounce(RESCAN_DELAY_MS)
                .collect { if (_state.value.reports.isNotEmpty()) scan(restart = true) }
        }
        // Smart-search insights wait for the kind words, made at the end of an indexing pass.
        scope.launch {
            IndexRepository.passFinished.collect {
                val waiting = _state.value.reports.any {
                    it.contents.status == ContentReport.Status.PREPARING || it.contents.status == ContentReport.Status.NOT_ENOUGH
                }
                if (waiting) scan()
            }
        }
    }

    /** Scans unless a scan is already running; set [restart] to cancel it and start over. */
    fun scan(restart: Boolean = false) {
        if (scanJob?.isActive == true && !restart) return
        scanJob?.cancel()
        // Show the scan at once, before the walk reports its first files.
        setStage(ScanStage.Walking(0))
        scanJob = scope.launch {
            try {
                val reports = Storage.volumes(context).map { volume -> scanVolume(volume) }
                _state.update { it.copy(reports = reports) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Storage scan failed: ${e.javaClass.simpleName}")
            } finally {
                // A scan cancelled by a restart leaves the stage to the new one.
                if (scanJob === coroutineContext[Job]) _state.update { it.copy(stage = null) }
            }
        }
    }

    /** Walks the volume, shows that result straight away, then adds duplicates when they're known. */
    private suspend fun scanVolume(volume: StorageVolumeInfo): VolumeReport {
        val walk = StorageScanner().scan(volume) { seen -> setStage(ScanStage.Walking(seen)) }
        val existingReport = _state.value.reports.find { it.volume.root == volume.root }
        val preservedDuplicates = existingReport?.duplicates
            ?.map { group -> group.copy(copies = group.copies.filter { File(it.path).exists() }) }
            ?.filter { it.copies.size > 1 }
        val walked = walk.report.copy(
            cleanups = walk.report.cleanups + (CleanupKind.VIDEOS_4K to videos4K(volume)),
            duplicates = preservedDuplicates
        )
        // Show the walk at once with the last smart-search results, then with fresh ones.
        publish(ContentInsights.withContents(walked, existingReport?.contents ?: ContentReport.NONE, existingReport?.installedApkPaths.orEmpty()))
        val withVideos = ContentInsights.withContents(walked, contentsOf(volume), installedApks(walked.cleanups[CleanupKind.INSTALLERS].orEmpty()))
        publish(withVideos)

        duplicateFinder.retainOnly(walk.duplicateCandidates.mapTo(HashSet()) { it.path })
        val duplicates = duplicateFinder.find(walk.duplicateCandidates, volume.root.path) { done, total ->
            setStage(ScanStage.Duplicates(done, total))
        }
        return withVideos.copy(duplicates = duplicates).also(::publish)
    }

    /** Replaces (or adds) one volume's report, keeping the others. */
    private fun publish(report: VolumeReport) = _state.update { state ->
        val others = state.reports.filterNot { it.volume.root == report.volume.root }
        state.copy(reports = (others + report).sortedByDescending { it.volume.isPrimary })
    }

    private fun setStage(stage: ScanStage) = _state.update { it.copy(stage = stage) }

    /** Videos 3840 px or more on their long side, from the media index (which knows resolutions). */
    private fun videos4K(volume: StorageVolumeInfo): List<FoundFile> {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        val selection = "${MediaStore.Video.Media.WIDTH} >= ? OR ${MediaStore.Video.Media.HEIGHT} >= ?"
        val args = arrayOf(UHD_LONG_SIDE.toString(), UHD_LONG_SIDE.toString())
        val found = ArrayList<FoundFile>()
        runCatching {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0) ?: continue
                    if (!path.startsWith(volume.root.path + "/")) continue
                    val rel = path.removePrefix(volume.root.path + "/").lowercase()
                    if (!PathRules.isReviewable(rel)) continue
                    if (!File(path).exists()) continue
                    found += FoundFile(path, cursor.getLong(1), cursor.getLong(2) * 1000)
                }
            }
        }.onFailure { Log.w(TAG, "4K video query failed: ${it.javaClass.simpleName}") }
        return found.sortedByDescending { it.sizeBytes }
    }

    /** What smart search says about the volume's files; never fails the scan. */
    private suspend fun contentsOf(volume: StorageVolumeInfo): ContentReport =
        try {
            ContentAnalyzer.getInstance(context).analyse(volume.root.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Content insights failed: ${e.javaClass.simpleName}")
            ContentReport.NONE
        }

    /** Installers whose app is already installed at the same or a newer version. */
    private fun installedApks(installers: List<FoundFile>): Set<String> {
        val pm = context.packageManager
        return installers
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .mapNotNullTo(HashSet()) { file ->
                runCatching {
                    val archive = pm.getPackageArchiveInfo(file.path, 0) ?: return@runCatching null
                    val installed = pm.getPackageInfo(archive.packageName, 0)
                    file.path.takeIf { installed.longVersionCode >= archive.longVersionCode }
                }.getOrNull()
            }
    }

    /** Right after a delete or move, lists shouldn't still offer files that are gone. */
    fun dropMissingFiles(knownGone: Set<String>? = null) {
        if (knownGone != null) {
            // Pure in-memory update: drops only known-gone paths without any blocking disk I/O.
            _state.update { state ->
                state.copy(reports = state.reports.map { report ->
                    val updatedCleanups = report.cleanups.mapValues { (_, files) ->
                        files.filter { it.path !in knownGone }
                    }
                    val updatedDuplicates = report.duplicates
                        ?.map { group ->
                            group.copy(copies = group.copies.filter { it.path !in knownGone })
                        }
                        ?.filter { it.copies.size > 1 }
                    val contents = report.contents
                    val updatedContents = contents.copy(
                        similar = contents.similar
                            .map { group -> group.copy(others = group.others.filter { it.path !in knownGone }) }
                            .filter { it.others.isNotEmpty() && it.keep.path !in knownGone },
                        forwards = contents.forwards.filter { it.path !in knownGone },
                        sensitive = contents.sensitive.filter { it.path !in knownGone }
                    )
                    report.copy(
                        contents = updatedContents,
                        cleanups = updatedCleanups,
                        duplicates = updatedDuplicates,
                        installedApkPaths = report.installedApkPaths.filterTo(HashSet()) { it !in knownGone }
                    )
                })
            }
        } else {
            // General disk check: run strictly on IO dispatcher so the UI thread is never blocked.
            scope.launch(Dispatchers.IO) {
                _state.update { state ->
                    state.copy(reports = state.reports.map { report ->
                        val updatedCleanups = report.cleanups.mapValues { (_, files) ->
                            files.filter { File(it.path).exists() }
                        }
                        val updatedDuplicates = report.duplicates
                            ?.map { group ->
                                group.copy(copies = group.copies.filter { File(it.path).exists() })
                            }
                            ?.filter { it.copies.size > 1 }
                        fun FoundFile.isGone() = !File(path).exists()
                        val contents = report.contents
                        val updatedContents = contents.copy(
                            similar = contents.similar
                                .map { group -> group.copy(others = group.others.filterNot { it.isGone() }) }
                                .filter { it.others.isNotEmpty() && !it.keep.isGone() },
                            forwards = contents.forwards.filterNot { it.isGone() },
                            sensitive = contents.sensitive.filterNot { it.isGone() }
                        )
                        report.copy(
                            contents = updatedContents,
                            cleanups = updatedCleanups,
                            duplicates = updatedDuplicates,
                            installedApkPaths = report.installedApkPaths.filterTo(HashSet()) { File(it).exists() }
                        )
                    })
                }
            }
        }
    }

    companion object {
        private const val TAG = "StorageAnalyzer"
        private const val RESCAN_DELAY_MS = 1_500L
        private const val UHD_LONG_SIDE = 3840

        @Volatile
        private var INSTANCE: StorageAnalyzer? = null

        fun getInstance(context: Context): StorageAnalyzer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StorageAnalyzer(context.applicationContext).also { INSTANCE = it }
            }
    }
}
