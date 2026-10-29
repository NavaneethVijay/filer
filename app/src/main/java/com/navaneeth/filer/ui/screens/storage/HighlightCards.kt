package com.navaneeth.filer.ui.screens.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.analysis.CleanupKind
import com.navaneeth.filer.data.analysis.Highlight
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ExpressiveType
import com.navaneeth.filer.ui.components.MiniBars
import com.navaneeth.filer.ui.components.ShapedIcon
import com.navaneeth.filer.ui.components.colors

/** A [Highlight] in words and colours, ready for either card style. */
private class HighlightText(
    val overline: String,
    val big: String,
    val text: String,
    val action: String?,
    val accent: Accent,
    val icon: ImageVector,
    /** Worked out by smart search, so it only covers indexed files. */
    val smart: Boolean = false
)

@Composable
private fun Highlight.text(): HighlightText {
    val context = LocalContext.current
    return when (this) {
        is Highlight.ThisMonth -> HighlightText(
            overline = stringResource(if (aboveAverage) R.string.highlight_growing_fast else R.string.highlight_this_month),
            big = "+" + context.bytes(added),
            text = stringResource(R.string.highlight_this_month_text, stringResource(topType.label)),
            action = null,
            accent = Accent.BLUE,
            icon = Icons.AutoMirrored.Rounded.TrendingUp
        )
        is Highlight.ChatApp -> HighlightText(
            overline = stringResource(R.string.highlight_chat_app),
            big = context.bytes(bytes),
            text = stringResource(R.string.highlight_chat_app_text, stringResource(app.label)),
            action = stringResource(R.string.highlight_chat_app_action),
            accent = Accent.BLUE,
            icon = CleanupKind.CHAT_MEDIA.look.icon
        )
        is Highlight.ForgottenDownloads -> HighlightText(
            overline = stringResource(R.string.highlight_forgotten),
            big = context.bytes(bytes),
            text = stringResource(R.string.highlight_forgotten_text),
            action = stringResource(R.string.highlight_forgotten_action),
            accent = Accent.AMBER,
            icon = CleanupKind.OLD_DOWNLOADS.look.icon
        )
        is Highlight.Videos4K -> HighlightText(
            overline = stringResource(R.string.highlight_4k),
            big = context.bytes(bytes),
            text = pluralStringResource(R.plurals.highlight_4k_text, count, count),
            action = stringResource(R.string.highlight_4k_action),
            accent = Accent.TEAL,
            icon = CleanupKind.VIDEOS_4K.look.icon
        )
        is Highlight.OldScreenshots -> HighlightText(
            overline = stringResource(R.string.highlight_screenshots),
            big = stringResource(R.string.highlight_screenshots_big, old, total),
            text = stringResource(R.string.highlight_screenshots_text),
            action = stringResource(R.string.highlight_screenshots_action),
            accent = Accent.SLATE,
            icon = CleanupKind.OLD_SCREENSHOTS.look.icon
        )
        is Highlight.Sensitive -> HighlightText(
            overline = stringResource(R.string.highlight_sensitive),
            big = pluralStringResource(R.plurals.highlight_sensitive_big, count, count),
            text = stringResource(R.string.highlight_sensitive_text),
            action = stringResource(R.string.action_lock),
            accent = Accent.AMBER,
            icon = CleanupKind.SENSITIVE.look.icon,
            smart = true
        )
        is Highlight.SimilarShots -> HighlightText(
            overline = stringResource(R.string.highlight_similar),
            big = context.bytes(bytes),
            text = pluralStringResource(R.plurals.highlight_similar_text, groups, groups),
            action = stringResource(R.string.highlight_similar_action),
            accent = Accent.SKY,
            icon = CleanupKind.SIMILAR_PHOTOS.look.icon,
            smart = true
        )
        is Highlight.Forwards -> HighlightText(
            overline = stringResource(R.string.highlight_forwards),
            big = context.bytes(bytes),
            text = pluralStringResource(R.plurals.highlight_forwards_text, count, count),
            action = stringResource(R.string.highlight_forwards_action),
            accent = Accent.BLUE,
            icon = CleanupKind.FORWARDS.look.icon,
            smart = true
        )
        is Highlight.InstallersYouHave -> HighlightText(
            overline = stringResource(R.string.highlight_installers),
            big = stringResource(R.string.highlight_installers_big, have, total),
            text = stringResource(R.string.highlight_installers_text),
            action = stringResource(R.string.highlight_installers_action),
            accent = Accent.TEAL,
            icon = CleanupKind.INSTALLERS.look.icon
        )
        is Highlight.DuplicatesIn -> HighlightText(
            overline = stringResource(R.string.highlight_duplicates),
            big = stringResource(R.string.highlight_duplicates_big, (share * 100).toInt()),
            text = stringResource(R.string.highlight_duplicates_text, stringResource(place.label)),
            action = stringResource(R.string.highlight_duplicates_action),
            accent = Accent.SKY,
            icon = CleanupKind.DUPLICATES.look.icon
        )
    }
}

/**
 * The overview's insights as an M3 multi-browse carousel: each card tinted with its colour, with a
 * big soft shape peeking in from a corner and a shaped icon; the shapes change card to card.
 * Squeezed cards at the edge fade their words so they read as a peek, not cut-off text.
 */
@Composable
fun HighlightCarousel(highlights: List<Highlight>, onOpen: (Highlight) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberCarouselState { highlights.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 264.dp,
        itemSpacing = 8.dp,
        modifier = modifier.fillMaxWidth().height(CardHeight)
    ) { index ->
        val highlight = highlights[index]
        val info = carouselItemInfo
        val shown = if (info.maxSize > 0f) ((info.size - info.minSize) / (info.maxSize - info.minSize)).coerceIn(0f, 1f) else 1f
        HighlightCard(
            highlight = highlight,
            index = index,
            contentAlpha = shown,
            onOpen = { onOpen(highlight) },
            modifier = Modifier.fillMaxSize().maskClip(CardShapes[index % CardShapes.size])
        )
    }
}

/** One carousel card; [contentAlpha] fades the words while the card is squeezed at the edge. */
@Composable
private fun HighlightCard(highlight: Highlight, index: Int, contentAlpha: Float, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val words = highlight.text()
    val palette = words.accent.colors()
    val shape = DecorShapes[index % DecorShapes.size]

    Surface(onClick = onOpen, color = palette.container, contentColor = palette.onContainer, modifier = modifier) {
        Box {
            Box(
                Modifier
                    .align(if (index % 2 == 0) Alignment.TopEnd else Alignment.BottomEnd)
                    .offset(x = 56.dp, y = if (index % 2 == 0) (-48).dp else 48.dp)
                    .size(180.dp)
                    .rotate(index * 24f)
                    .clip(shape)
                    .background(palette.strong.copy(alpha = 0.14f))
            )
            Column(
                Modifier.fillMaxSize().padding(20.dp).graphicsLayer { alpha = contentAlpha },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShapedIcon(words.icon, shape, palette.strong, palette.onStrong, size = 44.dp, iconSize = 22.dp)
                    if (words.smart) SmartSearchChip()
                }
                Text(words.overline.uppercase(), style = ExpressiveType.Overline, color = palette.onContainer.copy(alpha = 0.8f), maxLines = 1)
                Text(words.big, style = ExpressiveType.Number, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (highlight is Highlight.ThisMonth) {
                    // Amber picks out a month that is well above the usual.
                    val now = if (highlight.aboveAverage) Accent.AMBER.colors().strong else palette.strong
                    MiniBars(highlight.trend, palette.strong.copy(alpha = 0.3f), now, Modifier.fillMaxWidth().height(28.dp))
                }
                Text(words.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                words.action?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private val CardHeight = 248.dp
/** Card outlines, one corner tucked in differently on each, so the row has rhythm. */
private val CardShapes = listOf(
    RoundedCornerShape(32.dp),
    RoundedCornerShape(32.dp, 32.dp, 32.dp, 12.dp),
    RoundedCornerShape(12.dp, 32.dp, 32.dp, 32.dp)
)
/** The corner shape and icon chip shape, in turn. */
private val DecorShapes = listOf(ExpressiveShapes.Cookie, ExpressiveShapes.Flower, ExpressiveShapes.Squircle, ExpressiveShapes.Pill)

/** Full-width card for the Insights screen's "Worth knowing" list, with a button. */
@Composable
fun HighlightRow(highlight: Highlight, index: Int, onOpen: (() -> Unit)?) {
    val words = highlight.text()
    val palette = words.accent.colors()
    // Vary one corner and the icon shape down the list.
    val shape = when (index % 3) {
        0 -> RoundedCornerShape(32.dp)
        1 -> RoundedCornerShape(32.dp, 32.dp, 32.dp, 12.dp)
        else -> RoundedCornerShape(32.dp, 12.dp, 32.dp, 32.dp)
    }
    val iconShape = listOf(ExpressiveShapes.Cookie, ExpressiveShapes.Flower, ExpressiveShapes.Squircle)[index % 3]

    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            ShapedIcon(words.icon, iconShape, palette.container, palette.onContainer, size = 56.dp, iconSize = 26.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(words.overline.uppercase(), style = ExpressiveType.Overline)
                    if (words.smart) SmartSearchChip()
                }
                Text(words.big, style = ExpressiveType.Number)
                Text(words.text, style = MaterialTheme.typography.bodyMedium)
                if (words.action != null && onOpen != null) {
                    Button(
                        onClick = onOpen,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.strong, contentColor = palette.onStrong),
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text(words.action) }
                }
            }
        }
    }
}

/** Small "Smart search" pill: what it's on was worked out from the search index, not the whole phone. */
@Composable
fun SmartSearchChip() {
    val blue = Accent.BLUE.colors()
    Surface(shape = ExpressiveShapes.Pill, color = blue.container, contentColor = blue.onContainer) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(stringResource(R.string.smart_search_chip), style = MaterialTheme.typography.labelSmall)
        }
    }
}
