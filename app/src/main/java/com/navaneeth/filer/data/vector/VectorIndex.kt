package com.navaneeth.filer.data.vector

import android.util.Half
import com.navaneeth.filer.data.db.dao.EmbeddingDao
import com.navaneeth.filer.data.db.entity.EmbeddingKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** One vector's meaning score for a query. */
data class MeaningMatch(val fileId: Long, val chunkId: Long?, val z: Double)

/** How one query scores across the index: per-kind means and the shared spread (see [VectorIndex.search]). */
class QueryStats(private val kindMean: DoubleArray, private val spread: Double) {
    /** The z [VectorIndex.search] would give a vector of [kind] with similarity [score]. */
    fun z(score: Float, kind: Int, imageCosFloor: Float, floorZ: Double): Double =
        VectorIndex.zOf(score, kind, kindMean, spread, imageCosFloor, floorZ)
}

/**
 * Vectors live in SQLite as float16 (see [EmbeddingDao]); this keeps a compact float16 matrix in
 * memory for scoring, built on the first search and rebuilt only after the index changes.
 *
 * Scoring: cosine against every vector, centred per kind (text vs image, so photos
 * aren't judged against prose), divided by one spread across all vectors (floored), giving a
 * z-score. A file counts as a meaning match when its best z clears the gate.
 */
object VectorIndex {
    const val DIM = 768

    private val lock = Mutex()
    @Volatile private var dirty = true

    private var fileIds = LongArray(0)
    private var chunkIds = LongArray(0) // -1 for whole-file (image) vectors
    private var kinds = ByteArray(0)
    private var matrix = ShortArray(0)  // row-major float16 bits, DIM per row
    /** Bumped each time the matrix is rebuilt, so callers can tell when cached [stats] went stale. */
    @Volatile var version = 0
        private set

    /** Float16 bits -> float, precomputed so scoring is a table lookup per component. */
    private val halfToFloat: FloatArray by lazy { FloatArray(65536) { Half.toFloat(it.toShort()) } }

    /** Call after any write to the embeddings table. */
    fun invalidate() {
        dirty = true
    }

    /** Frees the matrix (e.g. when the app goes to the background); rebuilt on the next search. */
    suspend fun release() = lock.withLock {
        fileIds = LongArray(0); chunkIds = LongArray(0); kinds = ByteArray(0); matrix = ShortArray(0)
        dirty = true
    }

    /**
     * Returns every vector with z ≥ [minZ], plus photos whose raw similarity reaches [imageCosFloor].
     * The floor matters when a folder is mostly one subject (e.g. 98 kayaking photos out of 151):
     * then nothing "stands out" by z, yet each photo plainly matches. Such photos get an effective
     * z of at least [floorZ], growing with similarity, so they rank like any confident match.
     */
    suspend fun search(
        dao: EmbeddingDao,
        query: FloatArray,
        minZ: Double,
        imageCosFloor: Float,
        floorZ: Double
    ): List<MeaningMatch> = lock.withLock {
        if (dirty) load(dao)
        val n = fileIds.size
        if (n == 0 || query.size != DIM) return@withLock emptyList()

        val table = halfToFloat
        val scores = FloatArray(n)
        for (row in 0 until n) {
            var dot = 0f
            val base = row * DIM
            for (d in 0 until DIM) dot += query[d] * table[matrix[base + d].toInt() and 0xFFFF]
            scores[row] = dot
        }

        // Per-kind means (only for groups large enough to be stable), one shared spread.
        val globalMean = scores.average()
        val kindMean = DoubleArray(EmbeddingKind.entries.size) { globalMean }
        for (k in EmbeddingKind.entries.indices) {
            var sum = 0.0
            var count = 0
            for (row in 0 until n) if (kinds[row].toInt() == k) { sum += scores[row]; count++ }
            if (count >= MIN_GROUP_FOR_OWN_MEAN) kindMean[k] = sum / count
        }
        var sq = 0.0
        for (row in 0 until n) {
            val diff = scores[row] - kindMean[kinds[row].toInt()]
            sq += diff * diff
        }
        val spread = max(sqrt(sq / n), MIN_SPREAD)

        val out = ArrayList<MeaningMatch>()
        for (row in 0 until n) {
            val z = zOf(scores[row], kinds[row].toInt(), kindMean, spread, imageCosFloor, floorZ)
            if (z >= minZ) out += MeaningMatch(fileIds[row], chunkIds[row].takeIf { it >= 0 }, z)
        }
        out
    }

    /**
     * The same per-kind means and spread [search] computes, for each of [queries], from an even
     * sample of at most [MAX_STATS_ROWS] vectors (tags score one file against many queries; the
     * full matrix per query would be slow). Null when the index is too small to say what stands out.
     */
    suspend fun stats(dao: EmbeddingDao, queries: List<FloatArray>): List<QueryStats>? = lock.withLock {
        if (dirty) load(dao)
        val n = fileIds.size
        if (n < MIN_ROWS_FOR_STATS) return@withLock null
        val step = max(1, n / MAX_STATS_ROWS)
        val rows = (0 until n step step).toList().toIntArray()
        val table = halfToFloat
        val kindCount = EmbeddingKind.entries.size
        queries.map { query ->
            val scores = FloatArray(rows.size)
            for ((i, row) in rows.withIndex()) {
                var dot = 0f
                val base = row * DIM
                for (d in 0 until DIM) dot += query[d] * table[matrix[base + d].toInt() and 0xFFFF]
                scores[i] = dot
            }
            val globalMean = scores.average()
            val kindMean = DoubleArray(kindCount) { globalMean }
            for (k in 0 until kindCount) {
                var sum = 0.0
                var count = 0
                for ((i, row) in rows.withIndex()) if (kinds[row].toInt() == k) { sum += scores[i]; count++ }
                if (count * step >= MIN_GROUP_FOR_OWN_MEAN) kindMean[k] = sum / count
            }
            var sq = 0.0
            for ((i, row) in rows.withIndex()) {
                val diff = scores[i] - kindMean[kinds[row].toInt()]
                sq += diff * diff
            }
            QueryStats(kindMean, max(sqrt(sq / rows.size), MIN_SPREAD))
        }
    }

    /** A file's best query: its index in the list, the z it scored, and whether the file has a photo vector. */
    class FileBest(val query: Int, val z: Double, val hasImage: Boolean)

    /**
     * For every file, which of [queries] it scores best for (its best vector, z as in [search],
     * using [stats] from [VectorIndex.stats] for the same queries). Used to sort files into kinds.
     */
    suspend fun bestQueryPerFile(
        dao: EmbeddingDao,
        queries: List<FloatArray>,
        stats: List<QueryStats>,
        imageCosFloor: Float,
        floorZ: Double
    ): Map<Long, FileBest> = lock.withLock {
        if (dirty) load(dao)
        val table = halfToFloat
        val imageKind = EmbeddingKind.IMAGE.ordinal
        val row = FloatArray(DIM)
        val out = HashMap<Long, FileBest>()
        for (r in fileIds.indices) {
            val base = r * DIM
            for (d in 0 until DIM) row[d] = table[matrix[base + d].toInt() and 0xFFFF]
            val kind = kinds[r].toInt()
            var bestQuery = -1
            var bestZ = Double.NEGATIVE_INFINITY
            for ((q, query) in queries.withIndex()) {
                var dot = 0f
                for (d in 0 until DIM) dot += query[d] * row[d]
                val z = stats[q].z(dot, kind, imageCosFloor, floorZ)
                if (z > bestZ) { bestZ = z; bestQuery = q }
            }
            val id = fileIds[r]
            val previous = out[id]
            val hasImage = kind == imageKind || previous?.hasImage == true
            out[id] = if (previous == null || bestZ > previous.z) FileBest(bestQuery, bestZ, hasImage)
            else FileBest(previous.query, previous.z, hasImage)
        }
        out
    }

    /** The photo vector of each of [wanted] that has one, for comparing photos with each other. */
    suspend fun imageVectors(dao: EmbeddingDao, wanted: Set<Long>): Map<Long, FloatArray> = lock.withLock {
        if (dirty) load(dao)
        val table = halfToFloat
        val imageKind = EmbeddingKind.IMAGE.ordinal.toByte()
        val out = HashMap<Long, FloatArray>()
        for (r in fileIds.indices) {
            if (kinds[r] != imageKind || fileIds[r] !in wanted) continue
            val base = r * DIM
            out[fileIds[r]] = FloatArray(DIM) { d -> table[matrix[base + d].toInt() and 0xFFFF] }
        }
        out
    }

    /**
     * z for one vector: distance from its kind's mean in shared spreads. A photo whose raw similarity
     * reaches [imageCosFloor] gets at least [floorZ], growing with similarity (see [search]).
     */
    internal fun zOf(score: Float, kind: Int, kindMean: DoubleArray, spread: Double, imageCosFloor: Float, floorZ: Double): Double {
        var z = (score - kindMean[kind]) / spread
        if (kind == EmbeddingKind.IMAGE.ordinal && score >= imageCosFloor) {
            z = max(z, floorZ + (score - imageCosFloor) / FLOOR_SLOPE)
        }
        return z
    }

    private suspend fun load(dao: EmbeddingDao) {
        val rows = dao.all().filter { it.vector.size == DIM * 2 }
        val n = rows.size
        fileIds = LongArray(n)
        chunkIds = LongArray(n)
        kinds = ByteArray(n)
        matrix = ShortArray(n * DIM)
        rows.forEachIndexed { i, row ->
            fileIds[i] = row.fileId
            chunkIds[i] = row.chunkId ?: -1
            kinds[i] = row.kind.toByte()
            ByteBuffer.wrap(row.vector).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(matrix, i * DIM, DIM)
        }
        dirty = false
        version++
    }

    fun decode(bytes: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { Half.toFloat(shorts.get(it)) }
    }

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) buffer.putShort(Half.toHalf(v))
        return buffer.array()
    }

    private const val MIN_GROUP_FOR_OWN_MEAN = 20
    /** Below this many vectors nothing reliably "stands out". */
    private const val MIN_ROWS_FOR_STATS = 50
    private const val MAX_STATS_ROWS = 3000
    private const val MIN_SPREAD = 0.02
    /** Each 0.05 of similarity above the floor adds 1 to the effective z. */
    private const val FLOOR_SLOPE = 0.05
}
