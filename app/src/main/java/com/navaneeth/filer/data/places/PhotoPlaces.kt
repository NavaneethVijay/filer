package com.navaneeth.filer.data.places

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.util.Log
import com.navaneeth.filer.data.files.FileManagerDatabase
import com.navaneeth.filer.data.files.PhotoPlaceEntity
import com.navaneeth.filer.data.media.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.cos

/** Progress of reading photo locations: [done] of [total] photos still to read. */
data class PlaceScan(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/**
 * Where each photo was taken, for "photos from Goa". Reads the GPS position from each photo's
 * EXIF once (no model, nothing leaves the phone), names it with the offline [Gazetteer], and only
 * re-reads photos that changed. Needs the media-location permission: without it Android hides
 * the position from every app.
 */
class PhotoPlaces private constructor(private val context: Context) {

    private val dao = FileManagerDatabase.getInstance(context).photoPlaces()
    private val media = MediaLibrary(context)
    private val lock = Mutex()

    private val _progress = MutableStateFlow(PlaceScan())
    val progress: StateFlow<PlaceScan> = _progress.asStateFlow()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Reads new or changed photos and forgets deleted ones. Cheap when nothing changed. */
    suspend fun scan() = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext
        lock.withLock {
            val photos = media.photos()
            val known = dao.scanned().associate { it.path to it.modifiedAt }
            known.keys.filterNot { java.io.File(it).exists() }.chunked(BATCH).forEach { dao.delete(it) }

            val todo = photos.filter { (path, modified) -> known[path] != modified }
            if (todo.isEmpty()) return@withLock
            val gazetteer = Gazetteer.get(context)
            _progress.value = PlaceScan(running = true, done = 0, total = todo.size)
            try {
                todo.chunked(BATCH).forEachIndexed { index, batch ->
                    dao.upsert(batch.map { (path, modified) -> read(path, modified, gazetteer) })
                    _progress.value = PlaceScan(running = true, done = minOf((index + 1) * BATCH, todo.size), total = todo.size)
                    yield()
                }
            } finally {
                _progress.value = PlaceScan()
            }
        }
    }

    private fun read(path: String, modified: Long, gazetteer: Gazetteer): PhotoPlaceEntity {
        val latLong = try {
            val out = FloatArray(2)
            if (ExifInterface(path).getLatLong(out)) doubleArrayOf(out[0].toDouble(), out[1].toDouble()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read photo metadata: ${e.javaClass.simpleName}")
            null
        }?.takeUnless { it[0] == 0.0 && it[1] == 0.0 } // 0,0 is a camera with no fix, not the Atlantic
        val place = latLong?.let { gazetteer.resolve(it[0], it[1]) }
        return PhotoPlaceEntity(
            path = path,
            modifiedAt = modified,
            lat = latLong?.get(0),
            lon = latLong?.get(1),
            city = place?.city,
            region = place?.region,
            country = place?.country
        )
    }

    /** Paths of photos taken in [place]. */
    suspend fun pathsIn(place: PlaceQuery): Set<String> = withContext(Dispatchers.IO) {
        when (place) {
            is PlaceQuery.Country -> dao.inCountry(place.country).toHashSet()
            is PlaceQuery.Region -> dao.inRegion(place.region, place.country).toHashSet()
            is PlaceQuery.City -> place.points.flatMapTo(HashSet()) { point ->
                val dLat = point.radiusKm / 111.0
                val dLon = point.radiusKm / (111.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.01))
                dao.inBox(point.lat - dLat, point.lat + dLat, point.lon - dLon, point.lon + dLon)
                    .filter { row ->
                        Gazetteer.distanceKm(point.lat, point.lon, row.lat ?: return@filter false, row.lon ?: return@filter false) <= point.radiusKm
                    }
                    .map { it.path }
            }
        }
    }

    /** "Panaji, Goa, India" for a photo, if known. */
    suspend fun placeOf(path: String): ResolvedPlace? = withContext(Dispatchers.IO) {
        dao.get(path)?.takeIf { it.country != null }?.let { ResolvedPlace(it.city, it.region.orEmpty(), it.country!!) }
    }

    /** Where one photo was taken: the saved answer, else read from the photo now and cache it. */
    suspend fun placeOrRead(file: java.io.File): ResolvedPlace? = placeOf(file.path) ?: withContext(Dispatchers.IO) {
        val row = read(file.path, file.lastModified(), Gazetteer.get(context))
        if (row.country != null) {
            dao.upsert(listOf(row))
            ResolvedPlace(row.city, row.region.orEmpty(), row.country)
        } else null
    }

    companion object {
        private const val TAG = "PhotoPlaces"
        private const val BATCH = 200

        @Volatile
        private var INSTANCE: PhotoPlaces? = null

        fun getInstance(context: Context): PhotoPlaces =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhotoPlaces(context.applicationContext).also { INSTANCE = it }
            }
    }
}
