package com.navaneeth.filer.ui.screens.storage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.CleanupKind
import com.navaneeth.filer.data.analysis.ContentReport
import com.navaneeth.filer.data.analysis.Forecast
import com.navaneeth.filer.data.analysis.InsightRange
import com.navaneeth.filer.data.analysis.MonthAdded
import com.navaneeth.filer.data.analysis.SpaceType
import com.navaneeth.filer.data.analysis.StorageInsights
import com.navaneeth.filer.data.analysis.VolumeReport
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.LegendKey
import com.navaneeth.filer.ui.components.SizeBar
import com.navaneeth.filer.ui.components.SplitBar
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.viewmodel.StorageViewModel
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How the storage is changing: forecast, growth, sources, busiest month and facts worth knowing. */
@Composable
fun InsightsScreen(storage: StorageViewModel, onOpenReview: (CleanupKind) -> Unit) {
    val state by storage.state.collectAsState()
    val report by storage.report.collectAsState()
    val range by storage.range.collectAsState()
    val listState = rememberLazyListState()
    val title = stringResource(R.string.insights_title)

    val barSpace = LocalBottomBarSpace.current

    // A tab of its own now, so it may be opened before Storage has scanned.
    LaunchedEffect(Unit) { storage.ensureScanned() }

    Scaffold(
        topBar = { StorageTopBar(title, listState) }
    ) { padding ->
        val current = report
        if (current == null) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = barSpace + 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "header") { ScreenHeader(title, stringResource(R.string.insights_subtitle)) }
                item(key = "first_scan") { FirstScanCard(state.stage) }
            }
            return@Scaffold
        }
        val forecast = remember(current, range) { StorageInsights.forecast(current, range) }
        val grew = remember(current, range) { StorageInsights.grew(current, range) }
        val highlights = remember(current) { StorageInsights.highlights(current) }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = barSpace + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "header") { ScreenHeader(title, stringResource(R.string.insights_subtitle)) }
            item(key = "forecast") { ForecastCard(current, forecast, range, storage::setRange) }
            item(key = "grew") { WhatGrewCard(grew, range) }
            item(key = "sources") { SourcesCard(current) }
            item(key = "busiest") { BusiestMonthCard(StorageInsights.lastTwelveMonths(current)) }
            item(key = "contents") { ContentsCard(current.contents) }
            if (highlights.isNotEmpty()) {
                item(key = "worth_title") { SectionTitle(stringResource(R.string.insights_worth_knowing)) }
                itemsIndexed(highlights, key = { _, h -> h::class.simpleName.orEmpty() }) { index, highlight ->
                    HighlightRow(highlight, index, onOpen = highlight.kind?.let { kind -> { onOpenReview(kind) } })
                }
            }
            item(key = "note") {
                Text(
                    stringResource(R.string.insights_estimate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

// region Forecast

@Composable
private fun ForecastCard(report: VolumeReport, forecast: Forecast, range: InsightRange, onRange: (InsightRange) -> Unit) {
    val blue = Accent.BLUE.colors()
    val monthYear = remember { DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()) }

    Surface(shape = RoundedCornerShape(40.dp), color = blue.container, contentColor = blue.onContainer) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.insights_forecast).uppercase(), style = ExpressiveType.Overline, color = blue.onContainer.copy(alpha = 0.8f))
            val headline = forecast.fullBy?.let { stringResource(R.string.insights_full_by, it.format(monthYear)) }
                ?: stringResource(R.string.insights_not_filling)
            Text(headline, style = ExpressiveType.Big)
            Text(forecastBody(report, forecast), style = MaterialTheme.typography.bodyLarge, color = blue.onContainer.copy(alpha = 0.85f))
            RangeToggle(range, onRange)
            UsageChart(forecast, lineColor = blue.strong, projectionColor = Accent.AMBER.colors().strong)
            ChartLabels(forecast, monthYear)
        }
    }
}

@Composable
private fun forecastBody(report: VolumeReport, forecast: Forecast): String {
    val context = LocalContext.current
    val pace = stringResource(R.string.insights_pace, context.bytes(forecast.pacePerMonth))
    val days = forecast.cleanupBuysDays
    val extra = when {
        forecast.fullBy == null -> stringResource(R.string.insights_not_filling_body)
        days == null || report.clutterBytes == 0L -> ""
        else -> stringResource(R.string.insights_buys, context.bytes(report.clutterBytes), duration(days))
    }
    return listOf(pace, extra).filter { it.isNotEmpty() }.joinToString(" ")
}

/** "5 days", "3 weeks", "2 months". */
@Composable
private fun duration(days: Int): String = when {
    days < 14 -> pluralStringResource(R.plurals.duration_days, days, days)
    days < 60 -> pluralStringResource(R.plurals.duration_weeks, days / 7, days / 7)
    else -> pluralStringResource(R.plurals.duration_months, days / 30, days / 30)
}

@Composable
private fun RangeToggle(range: InsightRange, onRange: (InsightRange) -> Unit) {
    val blue = Accent.BLUE.colors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.Pill)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        InsightRange.entries.forEach { option ->
            val selected = option == range
            Surface(
                onClick = { onRange(option) },
                shape = ExpressiveShapes.Pill,
                color = if (selected) blue.strong else Color.Transparent,
                contentColor = if (selected) blue.onStrong else blue.onContainer,
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private val InsightRange.label: Int
    get() = when (this) {
        InsightRange.SIX_MONTHS -> R.string.range_six_months
        InsightRange.ONE_YEAR -> R.string.range_one_year
        InsightRange.ALL_TIME -> R.string.range_all_time
    }

/**
 * Space used each month as a filled line, a dotted line from now to when the volume fills, and a
 * dashed "full" line along the top.
 */
@Composable
private fun UsageChart(forecast: Forecast, lineColor: Color, projectionColor: Color) {
    val history = forecast.history
    val projectedMonths = forecast.fullBy?.let { ChronoUnit.MONTHS.between(history.last().month, it).toInt() } ?: 0
    val description = stringResource(R.string.cd_usage_chart)

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .semantics { contentDescription = description }
    ) {
        val top = 8.dp.toPx()
        val bottom = size.height
        val steps = (history.size - 1 + projectedMonths).coerceAtLeast(1)
        fun x(index: Int) = index.toFloat() / steps * size.width
        fun y(bytes: Long) = bottom - (bytes.toFloat() / forecast.capacityBytes) * (bottom - top)

        // Full line.
        drawLine(
            projectionColor, Offset(0f, top), Offset(size.width, top), 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        )

        val line = Path()
        history.forEachIndexed { i, point ->
            if (i == 0) line.moveTo(x(i), y(point.usedBytes)) else line.lineTo(x(i), y(point.usedBytes))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(history.lastIndex), bottom)
            lineTo(0f, bottom)
            close()
        }
        drawPath(area, lineColor.copy(alpha = 0.25f))
        drawPath(line, lineColor, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val now = Offset(x(history.lastIndex), y(history.last().usedBytes))
        if (projectedMonths > 0) {
            drawLine(
                projectionColor, now, Offset(size.width, top), 4.dp.toPx(), StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 8.dp.toPx()))
            )
        }
        drawCircle(Color.White, 8.dp.toPx(), now)
        drawCircle(lineColor, 8.dp.toPx(), now, style = Stroke(4.dp.toPx()))
    }
}

/** First month, "Now" and the month it fills, under the chart. */
@Composable
private fun ChartLabels(forecast: Forecast, format: DateTimeFormatter) {
    val style = MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth()) {
        Text(forecast.history.first().month.format(format), style = style, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.insights_now), style = style)
        Spacer(Modifier.weight(1f))
        val end = forecast.fullBy?.let { stringResource(R.string.insights_capacity, it.format(format)) }
        if (end != null) Text(end, style = style)
    }
}

// endregion

// region Growth and sources

@Composable
private fun WhatGrewCard(grew: List<Pair<SpaceType, Long>>, range: InsightRange) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.insights_what_grew), style = ExpressiveType.Section, modifier = Modifier.weight(1f))
                Text(stringResource(range.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (grew.isEmpty()) {
                Text(stringResource(R.string.insights_nothing_grew), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            SplitBar(grew.map { (type, bytes) -> bytes.toFloat() to type.color() }, height = 28.dp)
            grew.forEach { (type, bytes) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendKey(type.color())
                    Row(Modifier.weight(1f)) {
                        Text(stringResource(type.label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).alignByBaseline())
                        Text("+" + context.bytes(bytes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline())
                    }
                }
            }
        }
    }
}

/** Sources as pills that get bigger the more they add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesCard(report: VolumeReport) {
    val sources = remember(report) { StorageInsights.sources(report) }
    if (sources.isEmpty()) return
    val total = sources.sumOf { it.second }.toFloat()
    val accents = listOf(Accent.BLUE, Accent.TEAL, Accent.SKY, Accent.BLUE, Accent.SLATE, Accent.SLATE, Accent.SLATE)

    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(stringResource(R.string.insights_sources), style = ExpressiveType.Section)
                Text(stringResource(R.string.insights_sources_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEachIndexed { rank, (source, bytes) ->
                    val palette = accents[rank].colors()
                    val strong = rank == 0
                    val scale = (3 - rank).coerceAtLeast(0) // the top three are larger
                    Surface(
                        shape = ExpressiveShapes.Pill,
                        color = if (strong) palette.strong else palette.container,
                        contentColor = if (strong) palette.onStrong else palette.onContainer
                    ) {
                        Row(
                            Modifier
                                .height((40 + scale * 8).dp)
                                .padding(horizontal = (14 + scale * 3).dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val textStyle = if (scale >= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall
                            Text(stringResource(source.label), style = textStyle)
                            Text("${(bytes / total * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region Busiest month

/** The biggest month called out, over a bar chart of what was added each month (busiest in blue). */
@Composable
private fun BusiestMonthCard(months: List<MonthAdded>) {
    val context = LocalContext.current
    val busiest = months.maxByOrNull { it.total }?.takeIf { it.total > 0 } ?: return
    val monthName = remember { DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()) }
    val initial = remember { DateTimeFormatter.ofPattern("MMMMM", Locale.getDefault()) }
    val mostly = busiest.bytes.maxBy { it.value }.key

    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.insights_busiest).uppercase(), style = ExpressiveType.Overline)
            Text(
                stringResource(R.string.insights_busiest_value, busiest.month.format(monthName), context.bytes(busiest.total)),
                style = ExpressiveType.Number
            )
            Text(stringResource(R.string.insights_busiest_body, stringResource(mostly.label)), style = MaterialTheme.typography.bodyLarge)
            MonthBars(months, busiest, initial, Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.insights_month_bars_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One bar per month, its GB above it and the month's initial below. */
@Composable
private fun MonthBars(months: List<MonthAdded>, busiest: MonthAdded, initial: DateTimeFormatter, modifier: Modifier = Modifier) {
    val sky = Accent.SKY.colors()
    val blue = Accent.BLUE.colors()
    val max = busiest.total.toFloat()
    Row(modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        months.forEach { month ->
            val isBusiest = month == busiest
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The value rides on top of its bar; bars use 80% of the height so the tallest label fits.
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(
                        gigabytes(month.total),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isBusiest) FontWeight.Bold else FontWeight.Normal,
                        color = if (isBusiest) blue.strong else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .fillMaxHeight((month.total / max * 0.8f).coerceAtLeast(0.02f))
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isBusiest) blue.strong else sky.strong.copy(alpha = 0.35f))
                    )
                }
                Text(
                    initial.format(month.month),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** "14" or "4.1": GB without the unit, short enough to sit over a narrow bar. */
private fun gigabytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return when {
        gb >= 10 -> String.format(Locale.getDefault(), "%.0f", gb)
        gb >= 0.05 -> String.format(Locale.getDefault(), "%.1f", gb)
        else -> "0"
    }
}

// endregion

// region What's in your files

/**
 * Space by what the files are (scenery, documents, greetings…), from smart search. Only indexed
 * files count, so it says how many; without smart search it just says how to get it.
 */
@Composable
private fun ContentsCard(contents: ContentReport) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contents_title), style = ExpressiveType.Section, modifier = Modifier.weight(1f))
                SmartSearchChip()
            }
            if (contents.status != ContentReport.Status.READY) {
                val message = when (contents.status) {
                    ContentReport.Status.PREPARING -> R.string.contents_preparing
                    ContentReport.Status.NOT_ENOUGH -> R.string.contents_not_enough
                    else -> R.string.contents_off
                }
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                stringResource(
                    R.string.contents_coverage,
                    pluralStringResource(R.plurals.contents_photos, contents.photoCount, contents.photoCount),
                    pluralStringResource(R.plurals.contents_documents, contents.documentCount, contents.documentCount)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val rows = contents.bytes.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                .map { stringResource(it.key.label) to it.value } +
                listOfNotNull(contents.otherBytes.takeIf { it > 0 }?.let { stringResource(R.string.content_other) to it })
            val largest = rows.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1L
            rows.forEachIndexed { index, (label, bytes) ->
                val isOther = index == rows.lastIndex && contents.otherBytes > 0
                val color = when {
                    isOther -> MaterialTheme.colorScheme.outline
                    index == 0 -> MaterialTheme.colorScheme.primary
                    else -> SpaceType.PHOTOS.color()
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).alignByBaseline())
                        Text(context.bytes(bytes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline())
                    }
                    SizeBar(bytes.toFloat() / largest, color, height = 10.dp)
                }
            }
            Text(stringResource(R.string.contents_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// endregion
