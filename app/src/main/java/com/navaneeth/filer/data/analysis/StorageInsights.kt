package com.navaneeth.filer.data.analysis

import java.time.YearMonth
import kotlin.math.ceil

/** The span the Insights screen looks back over. */
enum class InsightRange(val months: Long?) { SIX_MONTHS(6), ONE_YEAR(12), ALL_TIME(null) }

data class UsagePoint(val month: YearMonth, val usedBytes: Long)

data class Forecast(
    /** Space used at the end of each month, oldest first; the last point is now. */
    val history: List<UsagePoint>,
    val capacityBytes: Long,
    /** Average space added per month over the range. */
    val pacePerMonth: Long,
    /** When the volume fills at that pace; null if it isn't filling, or not within 10 years. */
    val fullBy: YearMonth?,
    /** How many more days cleaning up everything suggested would buy; null if not filling. */
    val cleanupBuysDays: Int?
)

/** A short "worth knowing" fact. [kind] is the list to open from it, if any. */
sealed interface Highlight {
    val kind: CleanupKind?

    /** [trend] is the space added in each of the last 8 months, ending with this one. */
    data class ThisMonth(val added: Long, val topType: SpaceType, val aboveAverage: Boolean, val trend: List<Long>) : Highlight {
        override val kind: CleanupKind? get() = null
    }
    data class ChatApp(val app: FileSource, val bytes: Long) : Highlight {
        override val kind get() = CleanupKind.CHAT_MEDIA
    }
    data class ForgottenDownloads(val bytes: Long) : Highlight {
        override val kind get() = CleanupKind.OLD_DOWNLOADS
    }
    data class Videos4K(val bytes: Long, val count: Int) : Highlight {
        override val kind get() = CleanupKind.VIDEOS_4K
    }
    data class OldScreenshots(val old: Int, val total: Int) : Highlight {
        override val kind get() = CleanupKind.OLD_SCREENSHOTS
    }
    /** Photos that look like IDs or bank papers, outside the Locked folder (smart search). */
    data class Sensitive(val count: Int) : Highlight {
        override val kind get() = CleanupKind.SENSITIVE
    }
    /** Near-identical photos (smart search): [bytes] freed by keeping the best of each of [groups]. */
    data class SimilarShots(val bytes: Long, val groups: Int) : Highlight {
        override val kind get() = CleanupKind.SIMILAR_PHOTOS
    }
    /** Greetings, quotes and memes received in chat apps (smart search). */
    data class Forwards(val bytes: Long, val count: Int) : Highlight {
        override val kind get() = CleanupKind.FORWARDS
    }
    /** [have] of the [total] installer files are for apps already installed. */
    data class InstallersYouHave(val have: Int, val total: Int) : Highlight {
        override val kind get() = CleanupKind.INSTALLERS
    }
        /** Most removable duplicate copies ([share] of their space) are in [place]. */
    data class DuplicatesIn(val place: FileSource, val share: Float) : Highlight {
        override val kind get() = CleanupKind.DUPLICATES
    }
}

/**
 * Turns a [VolumeReport] into insights. Everything is worked out from file dates, so history is
 * an estimate: files deleted in the past aren't known, and copied files keep their original date.
 */
object StorageInsights {

    private const val MAX_HISTORY_MONTHS = 60L
    private const val MAX_FORECAST_MONTHS = 120.0
    private const val MB = 1024L * 1024

    fun forecast(report: VolumeReport, range: InsightRange, today: YearMonth = YearMonth.now()): Forecast {
        val start = startOf(report, range, today)
        val addedAfter = addedPerMonth(report)
        val history = generateSequence(start) { it.plusMonths(1) }
            .takeWhile { it <= today }
            .map { month ->
                val later = addedAfter.filterKeys { it > month && it <= today }.values.sum()
                UsagePoint(month, (report.usedBytes - later).coerceAtLeast(0))
            }
            .toList()

        val added = addedAfter.filterKeys { it in start..today }.values.sum()
        val pace = added / history.size.coerceAtLeast(1)
        val free = report.volume.freeBytes
        val monthsLeft = if (pace > 0) free.toDouble() / pace else Double.MAX_VALUE
        val filling = monthsLeft <= MAX_FORECAST_MONTHS
        return Forecast(
            history = history,
            capacityBytes = report.volume.totalBytes,
            pacePerMonth = pace,
            fullBy = if (filling) today.plusMonths(ceil(monthsLeft).toLong()) else null,
            cleanupBuysDays = if (filling) (report.clutterBytes * 30 / pace).toInt() else null
        )
    }

    /** Space added in the range, by type, largest first. */
    fun grew(report: VolumeReport, range: InsightRange, today: YearMonth = YearMonth.now()): List<Pair<SpaceType, Long>> {
        val start = startOf(report, range, today)
        val totals = HashMap<SpaceType, Long>()
        report.addedByMonth
            .filter { it.month in start..today }
            .forEach { month -> month.bytes.forEach { (type, bytes) -> totals.merge(type, bytes, Long::plus) } }
        return totals.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Where the last 12 months of files came from, largest first. */
    fun sources(report: VolumeReport): List<Pair<FileSource, Long>> =
        report.recentSources.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }

    /** Each of the last 12 months, oldest first, including empty ones. */
    fun lastTwelveMonths(report: VolumeReport, today: YearMonth = YearMonth.now()): List<MonthAdded> {
        val byMonth = report.addedByMonth.associateBy { it.month }
        return (11L downTo 0L).map { back ->
            val month = today.minusMonths(back)
            byMonth[month] ?: MonthAdded(month, emptyMap())
        }
    }

    /** The facts worth showing, most interesting first. Empty for a volume with little on it. */
    fun highlights(report: VolumeReport, today: YearMonth = YearMonth.now()): List<Highlight> = buildList {
        // Safety first, then what smart search found, then the rest.
        report.files(CleanupKind.SENSITIVE).size.takeIf { it > 0 }?.let { add(Highlight.Sensitive(it)) }
        val similarPaths = report.files(CleanupKind.SIMILAR_PHOTOS).mapTo(HashSet()) { it.path }
        val similarGroups = report.contents.similar.count { group -> group.others.any { it.path in similarPaths } }
        if (report.bytes(CleanupKind.SIMILAR_PHOTOS) >= 20 * MB && similarGroups > 0) {
            add(Highlight.SimilarShots(report.bytes(CleanupKind.SIMILAR_PHOTOS), similarGroups))
        }
        if (report.bytes(CleanupKind.FORWARDS) >= 20 * MB) {
            add(Highlight.Forwards(report.bytes(CleanupKind.FORWARDS), report.files(CleanupKind.FORWARDS).size))
        }
        val installers = report.files(CleanupKind.INSTALLERS)
        val have = installers.count { it.path in report.installedApkPaths }
        if (have > 0) add(Highlight.InstallersYouHave(have, installers.size))

        val months = lastTwelveMonths(report, today)
        val thisMonth = months.last()
        if (thisMonth.total >= 100 * MB) {
            val previous = months.dropLast(1).takeLast(6).map { it.total }
            val average = previous.average()
            val topType = thisMonth.bytes.maxBy { it.value }.key
            add(Highlight.ThisMonth(thisMonth.total, topType, thisMonth.total > average * 1.5, months.takeLast(8).map { it.total }))
        }

        report.chatAppBytes.maxByOrNull { it.value }
            ?.takeIf { it.value >= 500 * MB }
            ?.let { add(Highlight.ChatApp(it.key, it.value)) }

        report.bytes(CleanupKind.OLD_DOWNLOADS).takeIf { it >= 100 * MB }?.let { add(Highlight.ForgottenDownloads(it)) }

        val videos4k = report.files(CleanupKind.VIDEOS_4K)
        if (videos4k.sumOf { it.sizeBytes } >= 1024 * MB) add(Highlight.Videos4K(videos4k.sumOf { it.sizeBytes }, videos4k.size))

        val oldShots = report.files(CleanupKind.OLD_SCREENSHOTS).size
        if (oldShots >= 20) add(Highlight.OldScreenshots(oldShots, report.screenshotCount))

        report.duplicates?.let { groups -> duplicatesHighlight(groups, report.volume.root.path)?.let(::add) }
    }

    private fun duplicatesHighlight(groups: List<DuplicateGroup>, root: String): Highlight.DuplicatesIn? {
        val extra = groups.flatMap { group -> group.copies.drop(1) }
        val total = extra.sumOf { it.sizeBytes }
        if (total < 100 * MB) return null
        val (place, bytes) = extra
            .groupBy { PathRules.source(it.path.removePrefix(root).trimStart('/').lowercase()) }
            .mapValues { (_, files) -> files.sumOf { it.sizeBytes } }
            .maxBy { it.value }
        return Highlight.DuplicatesIn(place, bytes.toFloat() / total)
    }

    private fun startOf(report: VolumeReport, range: InsightRange, today: YearMonth): YearMonth {
        range.months?.let { return today.minusMonths(it - 1) }
        val first = report.addedByMonth.firstOrNull()?.month ?: today
        return maxOf(first, today.minusMonths(MAX_HISTORY_MONTHS - 1))
    }

    private fun addedPerMonth(report: VolumeReport): Map<YearMonth, Long> =
        report.addedByMonth.associate { it.month to it.total }
}
