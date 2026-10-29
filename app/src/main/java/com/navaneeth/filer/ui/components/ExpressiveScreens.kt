package com.navaneeth.filer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.media.MediaCategory

// Shared pieces that bring the Storage tab's expressive look to Browse, its lists and Search.

/** The colour family a category's icon chip uses. BLUE, TEAL and SKY only tell kinds apart. */
val MediaCategory.accent: Accent
    get() = when (this) {
        MediaCategory.IMAGES -> Accent.BLUE
        MediaCategory.VIDEOS -> Accent.TEAL
        MediaCategory.AUDIO -> Accent.SKY
        MediaCategory.DOCUMENTS -> Accent.BLUE
        MediaCategory.DOWNLOADS -> Accent.SKY
        MediaCategory.APKS -> Accent.TEAL
    }

/** The shape of a category's icon chip, varied so neighbours differ. */
val MediaCategory.chipShape: Shape
    get() = when (this) {
        MediaCategory.IMAGES -> ExpressiveShapes.Cookie
        MediaCategory.VIDEOS -> ExpressiveShapes.Squircle
        MediaCategory.AUDIO -> ExpressiveShapes.Flower
        MediaCategory.DOCUMENTS -> ExpressiveShapes.Squircle
        MediaCategory.DOWNLOADS -> ExpressiveShapes.Pill
        MediaCategory.APKS -> ExpressiveShapes.Cookie
    }

/** A section heading in the expressive size, with an optional action at the end ("See all"). */
@Composable
fun ExpressiveSectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = ExpressiveType.Section, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** A list that is still loading: the expressive loader in the middle of the space. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
        ExpressiveLoader(size = 56.dp)
    }
}

/**
 * A large M3 top app bar with a bold title; it collapses to a regular bar as the list scrolls
 * (pair it with `TopAppBarDefaults.exitUntilCollapsedScrollBehavior`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoldLargeTopBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit = {}
) {
    LargeTopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior
    )
}
