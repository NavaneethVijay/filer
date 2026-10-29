package com.navaneeth.filer.data.analysis

import android.content.Context
import android.net.Uri
import com.navaneeth.filer.data.ai.TagSuggester
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.repository.SearchRepository
import com.navaneeth.filer.data.vector.VectorIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * What a file is about, as smart search sees it. Each kind is a few search words, scored against
 * the file's vectors exactly like suggested tags; a file belongs to the kind of its best word.
 */
enum class ContentKind(val labels: List<String>) {
    SCENERY(listOf("beach", "mountains", "nature", "sunset", "travel", "river", "lake", "city", "monument", "hiking", "snow", "flowers", "garden")),
    PEOPLE(listOf("selfie", "group photo", "family", "kids", "baby", "wedding", "party", "birthday")),
    FOOD(listOf("food", "drinks", "restaurant")),
    PETS(listOf("pet", "dog", "cat")),
    GREETINGS(listOf("meme", "quote", "good morning wishes", "festival greetings")),
    CHATS(listOf("chat")),
    DOCUMENTS(listOf("notes", "letter", "form", "certificate", "marksheet", "prescription", "medical report", "contract", "presentation", "whiteboard", "handwriting", "ticket", "boarding pass")),
    RECEIPTS(listOf("receipt", "bill", "invoice", "electricity bill")),
    IDS(listOf("id card", "passport", "aadhaar", "pan card", "driving licence", "bank statement", "salary slip", "visa"));

    companion object {
        /** Every word, in a fixed order (the order label vectors are scored in). */
        val allLabels: List<String> = entries.flatMap { it.labels }.distinct()

        fun of(label: String): ContentKind? = entries.firstOrNull { label in it.labels }
    }
}

/** Near-identical photos: [keep] is the one to keep, [others] can go. */
data class SimilarGroup(val keep: FoundFile, val others: List<FoundFile>) {
    val extraBytes: Long get() = others.sumOf { it.sizeBytes }
}

/** What smart search says about the files on a volume, for the Storage tab. */
data class ContentReport(
    val status: Status,
    /** Space per kind; files with no clear kind are in [otherBytes]. */
    val bytes: Map<ContentKind, Long> = emptyMap(),
    val otherBytes: Long = 0,
    /** How many indexed files this covers, to say how much of the phone it speaks for. */
    val photoCount: Int = 0,
    val documentCount: Int = 0,
    val similar: List<SimilarGroup> = emptyList(),
    val forwards: List<FoundFile> = emptyList(),
    val sensitive: List<FoundFile> = emptyList()
) {
    enum class Status {
        /** Smart search has nothing indexed on this volume. */
        OFF,
        /** Too few vectors to tell what stands out. */
        NOT_ENOUGH,
        /** The kind words haven't been embedded yet (done at the end of the next indexing pass). */
        PREPARING,
        READY
    }

    companion object {
        val NONE = ContentReport(Status.OFF)
    }
}

/** Pure parts of the content insights, unit-tested. */
object ContentInsights {

    /** Photos taken this close together can be the same shot. */
    const val SIMILAR_WINDOW_MS = 3 * 60_000L
    /** Image-vector cosine for "the same shot". Starting value; tune on real photos. */
    const val SIMILAR_MIN_COS = 0.93f

    class PhotoVector(val file: FoundFile, val vector: FloatArray)

    /**
     * Runs of photos taken within [windowMs] of the previous one whose vectors match the run's
     * first photo by at least [minCos]. The biggest file of each run (usually the sharpest) is kept.
     */
    fun similarGroups(photos: List<PhotoVector>, windowMs: Long = SIMILAR_WINDOW_MS, minCos: Float = SIMILAR_MIN_COS): List<SimilarGroup> {
        val groups = ArrayList<SimilarGroup>()
        var run = ArrayList<PhotoVector>()
        fun close() {
            if (run.size > 1) {
                val keep = run.maxWith(compareBy<PhotoVector> { it.file.sizeBytes }.thenBy { it.file.modifiedAt }).file
                groups += SimilarGroup(keep, run.map { it.file }.filter { it !== keep }.sortedByDescending { it.sizeBytes })
            }
        }
        for (photo in photos.sortedBy { it.file.modifiedAt }) {
            val last = run.lastOrNull()
            val joins = last != null &&
                photo.file.modifiedAt - last.file.modifiedAt <= windowMs &&
                cosine(run.first().vector, photo.vector) >= minCos
            if (!joins) {
                close()
                run = ArrayList()
            }
            run += photo
        }
        close()
        return groups.sortedByDescending { it.extraBytes }
    }

    /** Space per kind, and the rest. */
    fun spaceByKind(files: List<Pair<FoundFile, ContentKind?>>): Pair<Map<ContentKind, Long>, Long> {
        val bytes = HashMap<ContentKind, Long>()
        var other = 0L
        for ((file, kind) in files) {
            if (kind == null) other += file.sizeBytes else bytes[kind] = (bytes[kind] ?: 0L) + file.sizeBytes
        }
        return bytes to other
    }

    /** The report's cleanups with the smart-search kinds added; chat media leaves out forwards so nothing is counted twice. */
    fun withContents(report: VolumeReport, contents: ContentReport, installedApkPaths: Set<String>): VolumeReport {
        val forwardPaths = contents.forwards.mapTo(HashSet()) { it.path }
        val cleanups = report.cleanups.toMutableMap()
        cleanups[CleanupKind.CHAT_MEDIA] = report.cleanups[CleanupKind.CHAT_MEDIA].orEmpty().filter { it.path !in forwardPaths }
        cleanups[CleanupKind.SIMILAR_PHOTOS] = contents.similar.flatMap { it.others }.sortedByDescending { it.sizeBytes }
        cleanups[CleanupKind.FORWARDS] = contents.forwards.sortedByDescending { it.sizeBytes }
        cleanups[CleanupKind.SENSITIVE] = contents.sensitive.sortedByDescending { it.sizeBytes }
        return report.copy(cleanups = cleanups, contents = contents, installedApkPaths = installedApkPaths)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until minOf(a.size, b.size)) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / sqrt(na * nb)
    }
}

/**
 * Works out a [ContentReport] from the vectors smart search already stored. It never runs the
 * model: the kind words' vectors come from the tag cache, made at the end of an indexing pass.
 * The result is kept until the index or the words change.
 */
class ContentAnalyzer private constructor(private val context: Context) {

    private val db = FilerDatabase.getInstance(context)
    private val lock = Mutex()
    private var cacheKey: Any? = null
    private var cached: ContentReport? = null

    suspend fun analyse(volumeRoot: String): ContentReport = lock.withLock {
        val rootPrefix = "$volumeRoot/"
        val indexed = withContext(Dispatchers.IO) { db.fileDao().getAllIndexedFiles() }
            .mapNotNull { row ->
                val path = Uri.parse(row.uri).path ?: return@mapNotNull null
                if (!path.startsWith(rootPrefix)) return@mapNotNull null
                if (!PathRules.isReviewable(path.removePrefix(rootPrefix).lowercase())) return@mapNotNull null
                row.id to FoundFile(path, row.sizeBytes, row.modifiedAt)
            }.toMap()
        if (indexed.isEmpty()) return@withLock ContentReport.NONE

        val labels = ContentKind.allLabels
        val labelVectors = TagSuggester(context).cachedLabelVectors(labels)
        if (labelVectors.size < labels.size) return@withLock ContentReport(ContentReport.Status.PREPARING)
        val queries = labels.map { labelVectors.getValue(it) }

        val dao = db.embeddingDao()
        val stats = withContext(Dispatchers.Default) { VectorIndex.stats(dao, queries) }
            ?: return@withLock ContentReport(ContentReport.Status.NOT_ENOUGH)
        val key = Triple(VectorIndex.version, indexed.size, volumeRoot)
        cached?.takeIf { cacheKey == key }?.let { return@withLock it }

        val report = withContext(Dispatchers.Default) {
            val best = VectorIndex.bestQueryPerFile(dao, queries, stats, SearchRepository.IMAGE_COS_FLOOR, SearchRepository.Z_GATE)
            val labelled = ArrayList<Pair<FoundFile, ContentKind?>>()
            val forwards = ArrayList<FoundFile>()
            val sensitive = ArrayList<FoundFile>()
            var photos = 0
            var documents = 0
            for ((id, file) in indexed) {
                val match = best[id] ?: continue
                if (match.hasImage) photos++ else documents++
                val kind = ContentKind.of(labels[match.query])?.takeIf { match.z >= TagSuggester.TAG_Z }
                labelled += file to kind
                val rel = file.path.removePrefix(rootPrefix).lowercase()
                if (kind == ContentKind.GREETINGS && PathRules.isReceivedChatMedia(rel)) forwards += file
                if (kind == ContentKind.IDS && match.z >= SENSITIVE_Z) sensitive += file
            }
            val (bytes, other) = ContentInsights.spaceByKind(labelled)

            val cameraIds = indexed.filter { (_, file) -> PathRules.source(file.path.removePrefix(rootPrefix).lowercase()) == FileSource.CAMERA }.keys
            val photoVectors = VectorIndex.imageVectors(dao, cameraIds).mapNotNull { (id, vector) ->
                indexed[id]?.let { ContentInsights.PhotoVector(it, vector) }
            }
            ContentReport(
                status = ContentReport.Status.READY,
                bytes = bytes,
                otherBytes = other,
                photoCount = photos,
                documentCount = documents,
                similar = ContentInsights.similarGroups(photoVectors),
                forwards = forwards,
                sensitive = sensitive
            )
        }
        cacheKey = key
        cached = report
        report
    }

    companion object {
        /** Stricter than a tag: calling something an ID card should be a sure thing. */
        private const val SENSITIVE_Z = 3.0

        @Volatile
        private var INSTANCE: ContentAnalyzer? = null

        fun getInstance(context: Context): ContentAnalyzer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContentAnalyzer(context.applicationContext).also { INSTANCE = it }
            }
    }
}
