package com.navaneeth.filer.ui.components

import com.navaneeth.filer.data.extractor.UnreadableFileException
import android.text.format.DateUtils
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.model.IndexStatus

/** Background/foreground pair for a status pill. */
data class StatusColors(val container: Color, val content: Color)

@Composable
fun IndexStatus.colors(): StatusColors {
    val dark = isSystemInDarkTheme()
    return when (this) {
        // Muted accents, not the theme's saturated containers.
        IndexStatus.INDEXED -> Accent.GREEN.colors().let { StatusColors(it.container, it.onContainer) }
        IndexStatus.PENDING -> Accent.BLUE.colors().let { StatusColors(it.container, it.onContainer) }
        IndexStatus.FAILED -> StatusColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        IndexStatus.SKIPPED, IndexStatus.GONE -> StatusColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val IndexStatus.label: Int
    get() = when (this) {
        IndexStatus.INDEXED -> R.string.status_ready
        IndexStatus.PENDING -> R.string.status_waiting
        IndexStatus.FAILED -> R.string.status_failed
        IndexStatus.SKIPPED -> R.string.status_skipped
        IndexStatus.GONE -> R.string.status_gone
    }

@Composable
fun IndexStatusChip(status: IndexStatus, modifier: Modifier = Modifier) {
    val colors = status.colors()
    Surface(shape = MaterialTheme.shapes.small, color = colors.container, modifier = modifier) {
        Text(
            text = stringResource(status.label),
            style = MaterialTheme.typography.labelSmall,
            color = colors.content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Big number with a caption, used for the Ready / Waiting / Failed summary. */
@Composable
fun StatTile(
    value: String,
    label: String,
    colors: StatusColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = colors.container, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = colors.content)
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "5 minutes ago", "Yesterday", localized by the platform. */
fun formatRelative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** "1:05" style elapsed time. */
fun formatDuration(startMillis: Long, endMillis: Long): String =
    DateUtils.formatElapsedTime(((endMillis - startMillis) / 1000).coerceAtLeast(0))

/** Why a file couldn't be indexed, in words; stored reason codes are mapped, other errors shown as is. */
@Composable
fun indexProblemText(error: String?): String? = when (error) {
    null, "" -> null
    UnreadableFileException.PROTECTED -> stringResource(R.string.problem_protected)
    UnreadableFileException.UNREADABLE -> stringResource(R.string.problem_unreadable)
    else -> error
}
