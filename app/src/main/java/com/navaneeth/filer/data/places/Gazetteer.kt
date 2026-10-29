package com.navaneeth.filer.data.places

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a photo was taken, in words. [city] is null when no town is close by. */
data class ResolvedPlace(val city: String?, val region: String, val country: String)

/** A place named in a search: a town (with every town of that name), a state/region, or a country. */
sealed interface PlaceQuery {
    val label: String

    data class City(override val label: String, val points: List<Point>) : PlaceQuery
    data class Region(override val label: String, val region: String, val country: String) : PlaceQuery
    data class Country(override val label: String, val country: String) : PlaceQuery

    /** A town's centre and how far from it still counts as "in" it. */
    data class Point(val lat: Double, val lon: Double, val radiusKm: Double)
}

/**
 * Offline place names from GeoNames (towns of 5,000+ people, states/regions, countries), bundled
 * in assets/places.tsv. Turns photo coordinates into a place, and place names in a search into
 * something to match. Nothing goes over the network. Loaded once, on first use (~70k towns).
 */
class Gazetteer private constructor(
    private val countries: Array<String>,
    private val regionNames: Array<String>,
    private val regionCountry: IntArray,
    private val names: Array<String>,
    private val lats: DoubleArray,
    private val lons: DoubleArray,
    private val regionOf: IntArray,
    private val population: IntArray,
    private val byName: Map<String, IntArray>,
    private val grid: Map<Long, IntArray>
) {
    private val regionsByName: Map<String, List<Int>> =
        regionNames.indices.filter { regionNames[it].isNotEmpty() }.groupBy { regionNames[it].lowercase() }
    private val countryByName: Map<String, Int> =
        countries.indices.associateBy { countries[it].lowercase() } + COUNTRY_ALIASES.mapNotNull { (alias, name) ->
            countries.indexOf(name).takeIf { it >= 0 }?.let { alias to it }
        }

    /** The nearest town within [MAX_TOWN_KM], with its region and country; null in the middle of nowhere. */
    fun resolve(lat: Double, lon: Double): ResolvedPlace? {
        var best = -1
        var bestKm = Double.MAX_VALUE
        val cellLat = floor(lat).toInt()
        val cellLon = floor(lon).toInt()
        for (dLat in -1..1) for (dLon in -1..1) {
            val cell = grid[cellKey(cellLat + dLat, wrapLon(cellLon + dLon))] ?: continue
            for (i in cell) {
                val km = distanceKm(lat, lon, lats[i], lons[i])
                if (km < bestKm) {
                    bestKm = km
                    best = i
                }
            }
        }
        if (best < 0) return null
        val region = regionOf[best]
        return ResolvedPlace(
            city = names[best].takeIf { bestKm <= MAX_TOWN_KM },
            region = regionNames[region],
            country = countries[regionCountry[region]]
        )
    }

    /** A place named by [phrase] ("goa", "new york", "india"), or null. Countries win over regions over towns. */
    fun lookup(phrase: String): PlaceQuery? {
        val key = phrase.trim().lowercase()
        countryByName[key]?.let { return PlaceQuery.Country(countries[it], countries[it]) }
        if (key.length < 3) return null
        regionsByName[key]?.let { list ->
            // Same region name in two countries is rare; take the one with the biggest town.
            val region = list.maxBy { r -> population.indices.filter { regionOf[it] == r }.maxOfOrNull { population[it] } ?: 0 }
            return PlaceQuery.Region(regionNames[region], regionNames[region], countries[regionCountry[region]])
        }
        val towns = byName[key] ?: return null
        val label = names[towns.maxBy { population[it] }]
        return PlaceQuery.City(label, towns.map { PlaceQuery.Point(lats[it], lons[it], radiusFor(population[it])) })
    }

    /** True if [phrase] could be a place, for cheap checks while parsing. */
    fun isPlace(phrase: String): Boolean = lookup(phrase) != null

    companion object {
        /** A photo further than this from any town gets only its region and country. */
        private const val MAX_TOWN_KM = 40.0

        private val COUNTRY_ALIASES = mapOf(
            "usa" to "United States", "america" to "United States",
            "uk" to "United Kingdom", "britain" to "United Kingdom", "great britain" to "United Kingdom",
            "uae" to "United Arab Emirates", "emirates" to "United Arab Emirates",
            "holland" to "Netherlands", "korea" to "South Korea", "russia" to "Russia"
        )

        @Volatile
        private var INSTANCE: Gazetteer? = null
        private val loadLock = Mutex()

        suspend fun get(context: Context): Gazetteer = INSTANCE ?: loadLock.withLock {
            INSTANCE ?: withContext(Dispatchers.IO) { load(context) }.also { INSTANCE = it }
        }

        /** Big cities spread further: Mumbai's suburbs are still "Mumbai". */
        private fun radiusFor(population: Int): Double = when {
            population >= 5_000_000 -> 40.0
            population >= 1_000_000 -> 25.0
            population >= 100_000 -> 12.0
            else -> 6.0
        }

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * DEG
            val dLon = (lon2 - lon1) * DEG
            val a = sin(dLat / 2).let { it * it } + cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).let { it * it }
            return 2 * 6371.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        private const val DEG = PI / 180

        private fun cellKey(lat: Int, lon: Int): Long = (lat.toLong() shl 32) or (lon.toLong() and 0xFFFFFFFFL)
        private fun wrapLon(lon: Int) = ((lon + 180) % 360 + 360) % 360 - 180

        private fun load(context: Context): Gazetteer = context.assets.open("places.tsv").use(::parse)

        /** Parses places.tsv (also used by unit tests, which read it straight from assets). */
        internal fun parse(stream: InputStream): Gazetteer {
            val lines = stream.bufferedReader().readLines().filterNot { it.startsWith("#") }
            var i = 0
            fun section(tag: String): Int = lines[i++].split('\t').also { require(it[0] == tag) }[1].toInt()

            val countries = Array(section("C")) { lines[i++] }
            val regionCount = section("A")
            val regionNames = Array(regionCount) { "" }
            val regionCountry = IntArray(regionCount)
            repeat(regionCount) { r ->
                val f = lines[i++].split('\t')
                regionNames[r] = f[0]
                regionCountry[r] = f[1].toInt()
            }
            val count = section("P")
            val names = Array(count) { "" }
            val lats = DoubleArray(count)
            val lons = DoubleArray(count)
            val regionOf = IntArray(count)
            val population = IntArray(count)
            val byName = HashMap<String, MutableList<Int>>(count * 2)
            val grid = HashMap<Long, MutableList<Int>>()
            repeat(count) { p ->
                val f = lines[i++].split('\t')
                names[p] = f[0]
                lats[p] = f[1].toDouble()
                lons[p] = f[2].toDouble()
                regionOf[p] = f[3].toInt()
                population[p] = f[4].toInt()
                byName.getOrPut(f[0].lowercase()) { mutableListOf() } += p
                f.getOrNull(5)?.takeIf { it.isNotEmpty() }?.split('|')?.forEach { alt ->
                    byName.getOrPut(alt.lowercase()) { mutableListOf() } += p
                }
                grid.getOrPut(cellKey(floor(lats[p]).toInt(), floor(lons[p]).toInt())) { mutableListOf() } += p
            }
            return Gazetteer(
                countries, regionNames, regionCountry, names, lats, lons, regionOf, population,
                byName.mapValues { it.value.toIntArray() },
                grid.mapValues { it.value.toIntArray() }
            )
        }
    }
}
