package com.navaneeth.filer.ui.viewer

import android.net.Uri
import android.text.format.DateUtils
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.navaneeth.filer.R
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.navaneeth.filer.ui.util.LocalOpenDetails
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import com.navaneeth.filer.data.files.FileManager
import com.navaneeth.filer.ui.components.FileActionsSheet
import com.navaneeth.filer.ui.model.FileKind
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import com.navaneeth.filer.ui.util.FileActions
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.findActivity
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Photos and videos being viewed, and which one to show first. */
@Immutable
data class ViewerSession(val items: List<FileUiItem>, val start: Int, val manageable: Boolean = true)

/** Opens and closes the built-in media viewer (photos & videos) from anywhere in the app. */
@Stable
class PhotoViewerState {
    var session by mutableStateOf<ViewerSession?>(null)
        private set

    /**
     * Shows [item], swiping through the media among [siblings] (the list it was opened from).
     * [manageable] false (Recycle bin, Locked folder): view, share and open with only.
     */
    fun open(item: FileUiItem, siblings: List<FileUiItem>, manageable: Boolean = true) {
        val media = siblings.filter { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO }.ifEmpty { listOf(item) }
        val start = media.indexOfFirst { it.uri == item.uri }
        session = if (start >= 0) ViewerSession(media, start, manageable) else ViewerSession(listOf(item), 0, manageable)
    }

    fun close() {
        session = null
    }
}

val LocalPhotoViewer = staticCompositionLocalOf { PhotoViewerState() }

/** Largest edge decoded, so 50 MP photos fit in memory while zooming stays sharp enough. */
private const val MAX_DECODE_PX = 2560

/**
 * Full-screen media viewer: swipe between photos and videos, pinch or double-tap to zoom photos,
 * play/pause videos, tap to hide the controls. Share, Open with (any app), favourite, delete, and
 * every other file action in ⋮.
 */
@Composable
fun PhotoViewer(state: PhotoViewerState, ai: com.navaneeth.filer.ui.viewmodel.AiViewModel) {
    var last by remember { mutableStateOf<ViewerSession?>(null) }
    state.session?.let { last = it }
    AnimatedVisibility(
        visible = state.session != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(250), initialScale = 0.94f),
        exit = fadeOut(tween(180))
    ) {
        last?.let { session -> ViewerContent(session, ai, onClose = state::close) }
    }
}

@Composable
private fun ViewerContent(session: ViewerSession, ai: com.navaneeth.filer.ui.viewmodel.AiViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val favorites by fileOps.favoritePaths.collectAsState()
    var items by remember(session) { mutableStateOf(session.items) }
    val pager = rememberPagerState(initialPage = session.start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var sheetItem by remember { mutableStateOf<FileUiItem?>(null) }
    var aiItem by remember { mutableStateOf<FileUiItem?>(null) }
    val noAppMessage = stringResource(R.string.error_no_app)

    BackHandler(onBack = onClose)

    // Reset zoom state on page change
    LaunchedEffect(pager.currentPage) {
        zoomed = false
    }

    // Deleted, moved or renamed elsewhere (or from ⋮): drop it; close when nothing is left.
    LaunchedEffect(session) {
        FileManager.getInstance(context).changes.collect {
            // A disk check per photo: never on the main thread (a long list froze the app).
            val current = items
            val present = withContext(Dispatchers.IO) { current.filter { item -> item.pathOrNull()?.let { File(it).exists() } ?: true } }
            if (present.size != current.size) items = present
            if (items.isEmpty()) onClose()
        }
    }

    // Edge to edge on black: light system icons, and hide the bars along with the controls.
    val view = LocalView.current
    val controller = remember(view) { view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) } }
    DisposableEffect(controller) {
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            lightStatus?.let { controller.isAppearanceLightStatusBars = it }
            lightNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    LaunchedEffect(chrome) {
        if (chrome) controller?.show(WindowInsetsCompat.Type.systemBars()) else controller?.hide(WindowInsetsCompat.Type.systemBars())
    }

    val current = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            key = { items[it].uri },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            if (item.kind == FileKind.VIDEO) {
                VideoPlayerItem(
                    item = item,
                    isActive = (page == pager.currentPage),
                    chromeVisible = chrome,
                    onTap = { chrome = !chrome }
                )
            } else {
                ZoomableImage(
                    item = item,
                    onTap = { chrome = !chrome },
                    onZoomChange = { if (page == pager.currentPage) zoomed = it }
                )
            }
        }

        // Light-on-dark controls over a scrim; keep the content colour white in either theme.
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
            AnimatedVisibility(visible = chrome && current != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                current?.let { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(formatModified(item.modifiedAt), formatSize(context, item.sizeBytes), "${pager.currentPage + 1} / ${items.size}")
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        if (session.manageable) {
                            IconButton(onClick = { sheetItem = item }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_more_options, item.name))
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = chrome && current != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                current?.let { item ->
                    val path = item.pathOrNull()?.takeIf { session.manageable }
                    val favorite = path != null && path in favorites
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        fun report(ok: Boolean) {
                            if (!ok) {
                                haptics.reject()
                                scope.launch { snackbar.showSnackbar(noAppMessage) }
                            }
                        }
                        // Tags, text in the photo and more; never for bin/Locked files.
                        if (path != null && session.manageable && item.kind == com.navaneeth.filer.ui.model.FileKind.IMAGE) {
                            ViewerAction(Icons.Rounded.AutoAwesome, stringResource(R.string.viewer_ai)) { haptics.tick(); aiItem = item }
                        }
                        ViewerAction(Icons.Rounded.Share, stringResource(R.string.action_share)) { report(FileActions.share(context, item)) }
                        ViewerAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_open_with)) {
                            report(FileActions.openWith(context, item))
                        }
                        val openDetails = LocalOpenDetails.current
                        if (path != null && openDetails != null) {
                            ViewerAction(Icons.Rounded.Info, stringResource(R.string.action_details)) { openDetails(item) }
                        }
                        if (path != null) {
                            ViewerAction(
                                if (favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                stringResource(if (favorite) R.string.action_unfavorite else R.string.action_favorite)
                            ) { haptics.tick(); fileOps.setFavorite(listOf(path), !favorite) }
                            // Asks first; once it's in the bin, the change below drops it from the pager.
                            ViewerAction(Icons.Rounded.Delete, stringResource(R.string.action_trash)) {
                                haptics.tick()
                                fileOps.requestTrash(listOf(path))
                            }
                        }
                    }
                }
            }
        }
    }

    sheetItem?.let { item ->
        FileActionsSheet(item = item, onDismiss = { sheetItem = null })
    }
    aiItem?.let { item -> com.navaneeth.filer.ui.components.AiPanelSheet(item = item, ai = ai, onDismiss = { aiItem = null }) }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label)
    }
}

/**
 * One photo that zooms (pinch, or double-tap to 2.5×) around the fingers and pans within its
 * bounds. At 1× single-finger drags are left to the pager, so swiping still changes photo.
 */
@Composable
private fun ZoomableImage(item: FileUiItem, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(item.uri) {
        scale = 1f
        offset = Offset.Zero
        onZoomChange(false)
    }

    LaunchedEffect(scale > 1.01f) {
        onZoomChange(scale > 1.01f)
    }

    fun clamp(o: Offset, s: Float): Offset {
        if (!o.x.isFinite() || !o.y.isFinite() || !s.isFinite() || s <= 1.01f) return Offset.Zero
        val maxX = (size.width * (s - 1) / 2f).coerceAtLeast(0f)
        val maxY = (size.height * (s - 1) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val request = remember(item.uri) {
        ImageRequest.Builder(context).data(item.uri).size(MAX_DECODE_PX).crossfade(true).build()
    }
    AsyncImage(
        model = request,
        contentDescription = item.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offset = clamp((center - tap) * (target - 1), target)
                        }
                    }
                )
            }
            .pointerInput(item.uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        val fingers = pressedChanges.size
                        if (fingers > 1) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val rawCentroid = event.calculateCentroid(useCurrent = true)
                            if (zoom.isFinite() && zoom > 0f && rawCentroid != Offset.Unspecified && rawCentroid.x.isFinite() && rawCentroid.y.isFinite()) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val centroid = rawCentroid - center
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                val factor = if (scale > 0.001f) newScale / scale else 1f
                                val safePan = if (pan != Offset.Unspecified && pan.x.isFinite() && pan.y.isFinite()) pan else Offset.Zero
                                val targetOffset = (offset - centroid) * factor + centroid + safePan
                                scale = newScale
                                offset = if (newScale > 1.01f) clamp(targetOffset, newScale) else Offset.Zero
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (fingers == 1 && scale > 1.01f) {
                            val pan = event.calculatePan()
                            if (pan != Offset.Unspecified && pan.x.isFinite() && pan.y.isFinite()) {
                                offset = clamp(offset + pan, scale)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer {
                val s = if (scale.isFinite() && scale >= 1f) scale else 1f
                val ox = if (offset.x.isFinite()) offset.x else 0f
                val oy = if (offset.y.isFinite()) offset.y else 0f
                scaleX = s
                scaleY = s
                translationX = ox
                translationY = oy
            }
    )
}

/**
 * Built-in video player item with play/pause and scrub bar controls.
 */
@Composable
private fun VideoPlayerItem(
    item: FileUiItem,
    isActive: Boolean,
    chromeVisible: Boolean,
    onTap: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    // Update current playback position while active and playing
    LaunchedEffect(isPlaying, isActive) {
        while (isActive && isPlaying) {
            videoViewRef?.let { vv ->
                if (vv.isPlaying) {
                    currentMs = vv.currentPosition
                    if (durationMs <= 0 && vv.duration > 0) {
                        durationMs = vv.duration
                    }
                }
            }
            delay(200)
        }
    }

    // Automatically pause playback when the user swipes away to another item
    LaunchedEffect(isActive) {
        if (!isActive) {
            videoViewRef?.pause()
            isPlaying = false
        }
    }

    DisposableEffect(item.uri) {
        onDispose {
            videoViewRef?.stopPlayback()
            videoViewRef = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val parsed = Uri.parse(item.uri)
                    if (parsed.scheme == "file" && parsed.path != null) {
                        setVideoPath(parsed.path)
                    } else {
                        setVideoURI(parsed)
                    }
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        durationMs = duration
                        if (isActive) {
                            start()
                            isPlaying = true
                        }
                    }
                    setOnCompletionListener {
                        isPlaying = false
                    }
                    videoViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent tap target to toggle chrome overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap
                )
        )

        // Center play/pause indicator when paused or when chrome is visible
        AnimatedVisibility(
            visible = !isPlaying || chromeVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier
                    .size(64.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        videoViewRef?.let { vv ->
                            if (vv.isPlaying) {
                                vv.pause()
                                isPlaying = false
                            } else {
                                vv.start()
                                isPlaying = true
                            }
                        }
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        // Bottom scrubber when chrome is visible and duration is known
        AnimatedVisibility(
            visible = chromeVisible && durationMs > 0,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        DateUtils.formatElapsedTime((currentMs / 1000).toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                    Text(
                        DateUtils.formatElapsedTime((durationMs / 1000).toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Slider(
                    value = if (durationMs > 0) (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac ->
                        val target = (frac * durationMs).toInt()
                        currentMs = target
                        videoViewRef?.seekTo(target)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}

