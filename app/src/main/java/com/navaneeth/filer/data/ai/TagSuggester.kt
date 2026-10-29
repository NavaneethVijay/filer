package com.navaneeth.filer.data.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.navaneeth.filer.data.analysis.FileSource
import com.navaneeth.filer.data.analysis.PathRules
import com.navaneeth.filer.data.db.FilerDatabase
import com.navaneeth.filer.data.db.entity.EmbeddingKind
import com.navaneeth.filer.data.inference.EmbedderEngine
import com.navaneeth.filer.data.inference.EngineTag
import com.navaneeth.filer.data.inference.FileVectors
import com.navaneeth.filer.data.places.PhotoPlaces
import com.navaneeth.filer.data.repository.SearchRepository
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.data.vector.QueryStats
import com.navaneeth.filer.data.vector.VectorIndex
import com.navaneeth.filer.ui.model.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Tags are search words: a file gets a tag when a search for that word would make it stand out.
 * Each label ([TagLabels] plus the user's own tags) is embedded once as a search query with the
 * smart-search model and kept in a small file; a file's tags come from its vectors in the index
 * (or, for a file outside smart search, vectors made just for this), scored exactly like search
 * ([VectorIndex.stats]). Where it lives and where a photo was taken add a few more.
 *
 * No language model: instant for indexed files, and photos get tags from the picture itself.
 */
class TagSuggester(private val context: Context) {

    /** What the model and the file's metadata say about it. */
    data class Suggestion(
        val tags: List<String>,
        /** The best document-type label ("invoice"), for a suggested name. */
        val documentType: String?,
        /** The best label of any kind ("beach"), for a photo's suggested name. */
        val topLabel: String?,
        val place: String?
    )

    private class FileVector(val kind: Int, val vector: FloatArray)

    private val db = FilerDatabase.getInstance(context)
    private val embedder = EmbedderEngine(context)
    private val cacheFile = File(context.filesDir, "tags/labels.bin")

    /**
     * Ranks labels for [file]. [text] is used only when the file isn't in the index (its passages
     * are embedded just for this, not saved). [beforeEachRun] goes before every model run (heat).
     * Null when the smart-search model isn't there.
     */
    suspend fun suggest(file: File, text: String?, beforeEachRun: suspend () -> Unit): Suggestion? {
        if (!embedder.isModelLoaded()) return null
        val userTags = PluginData.getInstance(context).userTags()
        val labels = (TagLabels.all + userTags.map(TagLabels::queryFor)).distinct()
        val labelVectors = labelVectors(labels, beforeEachRun)
        val fileVectors = indexedVectors(file) ?: vectorsNow(file, text, beforeEachRun)

        val ranked = if (fileVectors.isEmpty()) emptyList() else rank(labels, labelVectors, fileVectors)
        val meaningTags = ranked.take(AiText.MAX_TAGS).mapNotNull { AiText.normalizeTag(it.first) }
        val place = if (FileKind.of(file.name, "") == FileKind.IMAGE) placeOf(file) else null
        val metadataTags = listOfNotNull(sourceTag(file), place?.let(AiText::normalizeTag))
        return Suggestion(
            tags = (metadataTags + meaningTags).distinct(),
            documentType = ranked.firstOrNull { it.first in TagLabels.documentTypes }?.first,
            topLabel = ranked.firstOrNull()?.first,
            place = place
        )
    }

    /** Label vectors already made, without running the model; labels not made yet are left out. */
    suspend fun cachedLabelVectors(labels: List<String>): Map<String, FloatArray> = lock.withLock {
        if (!loaded) { load(); loaded = true }
        labels.mapNotNull { label -> vectors[label]?.let { label to it } }.toMap()
    }

    /** Makes the vectors of any of [labels] not made yet (one model run each). For indexing passes only. */
    suspend fun prepareLabels(labels: List<String>, beforeEachRun: suspend () -> Unit) {
        if (embedder.isModelLoaded()) labelVectors(labels, beforeEachRun)
    }

    /** Labels whose z clears [TAG_Z], best first, keeping only those near the best. */
    private suspend fun rank(labels: List<String>, labelVectors: Map<String, FloatArray>, fileVectors: List<FileVector>): List<Pair<String, Double>> {
        val usable = labels.filter { it in labelVectors }
        val stats = statsFor(usable, labelVectors) ?: return emptyList()
        val scored = usable.mapIndexed { i, label ->
            val query = labelVectors.getValue(label)
            label to fileVectors.maxOf { v -> stats[i].z(dot(query, v.vector), v.kind, SearchRepository.IMAGE_COS_FLOOR, SearchRepository.Z_GATE) }
        }.filter { it.second >= TAG_Z }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.second ?: return emptyList()
        return scored.filter { it.second >= best - TAG_MARGIN }
    }

    // Label vectors (embedded once, kept in a file)

    private val lock = Mutex()
    private val vectors = HashMap<String, FloatArray>()
    private var loaded = false
    private var stats: List<QueryStats>? = null
    private var statsKey: Pair<Int, List<String>>? = null

    private suspend fun labelVectors(labels: List<String>, beforeEachRun: suspend () -> Unit): Map<String, FloatArray> = lock.withLock {
        if (!loaded) { load(); loaded = true }
        val missing = labels.filterNot { it in vectors }
        if (missing.isNotEmpty()) {
            for (label in missing) {
                beforeEachRun()
                // Only real vectors: a label the model couldn't embed is tried again next time.
                embedder.embedFormattedOnPhoneOnly(EmbedderEngine.formatQuery(label))?.let { vectors[label] = it }
            }
            save()
        }
        labels.mapNotNull { label -> vectors[label]?.let { label to it } }.toMap()
    }

    /** Per-label stats over the index, reused until the index changes or the labels do. */
    private suspend fun statsFor(labels: List<String>, labelVectors: Map<String, FloatArray>): List<QueryStats>? = lock.withLock {
        val key = VectorIndex.version to labels
        if (statsKey != key || stats == null) {
            stats = withContext(Dispatchers.Default) { VectorIndex.stats(db.embeddingDao(), labels.map { labelVectors.getValue(it) }) }
            // The matrix may have been (re)built inside stats(): key on the version it used.
            statsKey = VectorIndex.version to labels
        }
        stats
    }

    private fun load() {
        runCatching {
            if (!cacheFile.exists()) return
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                if (input.readUTF() != EmbedderEngine.MODEL_VERSION) return
                repeat(input.readInt()) {
                    val label = input.readUTF()
                    vectors[label] = FloatArray(VectorIndex.DIM) { input.readFloat() }
                }
            }
        }.onFailure { Log.w(TAG, "Tag label cache unreadable: ${it.javaClass.simpleName}"); vectors.clear() }
    }

    private fun save() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeUTF(EmbedderEngine.MODEL_VERSION)
                out.writeInt(vectors.size)
                vectors.forEach { (label, vector) ->
                    out.writeUTF(label)
                    vector.forEach(out::writeFloat)
                }
            }
            tmp.renameTo(cacheFile)
        }.onFailure { Log.w(TAG, "Tag label cache not saved: ${it.javaClass.simpleName}") }
    }

    // The file's vectors

    /** The file's real vectors in the index (photo, passages, plugin text); null if it isn't indexed. */
    private suspend fun indexedVectors(file: File): List<FileVector>? = withContext(Dispatchers.IO) {
        val row = db.fileDao().getFileByUri(Uri.fromFile(file).toString()) ?: return@withContext null
        db.embeddingDao().forFile(row.id)
            .filter { it.engine != EngineTag.FALLBACK && it.vector.size == VectorIndex.DIM * 2 }
            .map { FileVector(it.kind, VectorIndex.decode(it.vector)) }
            .takeIf { it.isNotEmpty() }
    }

    /** Vectors made now for a file outside smart search: the photo, or its first passages. */
    private suspend fun vectorsNow(file: File, text: String?, beforeEachRun: suspend () -> Unit): List<FileVector> {
        val out = ArrayList<FileVector>()
        if (FileKind.of(file.name, "") == FileKind.IMAGE) {
            val bitmap = withContext(Dispatchers.IO) { embedder.decodeForEmbedding(Uri.fromFile(file)) }
            if (bitmap != null) {
                beforeEachRun()
                (embedder.embedPhoto(bitmap, useMac = false) as? FileVectors.Ready)?.embeddings?.forEach {
                    out += FileVector(EmbeddingKind.IMAGE.ordinal, it.vector)
                }
            }
        }
        val passages = text?.let(AiText::passagesForTags).orEmpty()
        if (passages.isNotEmpty()) {
            (embedder.embedDocuments(passages, useMac = false, beforeEachOnPhone = beforeEachRun) as? FileVectors.Ready)?.embeddings?.forEach {
                out += FileVector(EmbeddingKind.TEXT.ordinal, it.vector)
            }
        }
        return out
    }

    // Metadata

    /** Where it came from, when that's something people search by. */
    private fun sourceTag(file: File): String? {
        val volume = Storage.volumeFor(context, file.path) ?: return null
        val rel = file.path.removePrefix(volume.root.path).trimStart('/').lowercase()
        return when (PathRules.source(rel)) {
            FileSource.WHATSAPP -> "whatsapp"
            FileSource.TELEGRAM -> "telegram"
            FileSource.SCREENSHOTS -> "screenshot"
            FileSource.SCREEN_RECORDINGS -> "screen-recording"
            else -> null
        }
    }

    private suspend fun placeOf(file: File): String? = runCatching {
        PhotoPlaces.getInstance(context).placeOrRead(file)?.let { it.city ?: it.region.takeIf(String::isNotEmpty) }
    }.getOrNull()

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }

    companion object {
        private const val TAG = "TagSuggester"
        /**
         * Stricter than the search gate (2.0): a tag should be something the file is plainly about.
         * Starting values; tune on real files (Developer mode's Mac embedder runs the same model).
         */
        const val TAG_Z = 2.5
        /** Labels further than this below the best one are left out. */
        const val TAG_MARGIN = 1.5
    }
}
