package com.navaneeth.filer.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.ai.AiPhase
import androidx.compose.runtime.mutableLongStateOf
import com.navaneeth.filer.data.ai.AiProblem
import com.navaneeth.filer.data.ai.AiTask
import com.navaneeth.filer.data.ai.PluginData
import com.navaneeth.filer.data.ai.RenameSuggestionEntity
import com.navaneeth.filer.data.ai.TextSource
import com.navaneeth.filer.data.files.NameResult
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.AiViewModel
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The AI plugins' cards on a file's details screen. Each shows only when its plugin is ready.

/** A card in the details list: neutral container, a shaped icon chip and a title. */
@Composable
private fun AiCard(
    icon: ImageVector,
    title: String,
    accent: Accent,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = accent.colors()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapedIcon(icon, ExpressiveShapes.Squircle, colors.container, colors.onContainer, size = 36.dp, iconSize = 20.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * What [task] is doing for this file right now: waiting for another AI task or working, with the
 * time so far and Stop. Shows nothing when the task isn't running.
 */
@Composable
fun TaskStatus(path: String, task: AiTask, ai: AiViewModel, workingText: String) {
    val states by ai.states.collectAsState()
    val state = states[path]?.get(task) ?: return
    val haptics = rememberHaptics()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val text = when {
        state.phase == AiPhase.WAITING -> stringResource(R.string.ai_status_waiting)
        else -> workingText
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpressiveLoader(size = 28.dp)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.ai_status_elapsed, DateUtils.formatElapsedTime(((now - state.startedAt) / 1000).coerceAtLeast(0))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { haptics.tick(); ai.cancel(path, task) }) { Text(stringResource(R.string.ai_stop)) }
    }
}

/** Why [task] last failed for this file, with Try again; nothing when it didn't. */
@Composable
fun TaskProblem(path: String, task: AiTask, ai: AiViewModel, onRetry: (() -> Unit)? = null) {
    val problems by ai.problems.collectAsState()
    val problem = problems[path]?.get(task) ?: return
    val haptics = rememberHaptics()
    val amber = Accent.AMBER.colors()
    Surface(shape = RoundedCornerShape(16.dp), color = amber.container, contentColor = amber.onContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Text(
                    stringResource(
                        when (problem) {
                            AiProblem.TOO_HOT -> R.string.ai_problem_hot
                            AiProblem.NO_TEXT -> R.string.ai_problem_no_text
                            AiProblem.NEEDS_PHOTO_TEXT -> R.string.ai_problem_needs_photo_text
                            AiProblem.NEEDS_TRANSCRIPT -> R.string.ai_problem_needs_transcript
                            AiProblem.NEEDS_SMART_SEARCH -> R.string.ai_problem_needs_smart_search
                            AiProblem.NO_TAGS -> R.string.ai_problem_no_tags
                            AiProblem.FAILED -> R.string.ai_problem_failed
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { haptics.tick(); ai.clearProblem(path, task) }) { Text(stringResource(R.string.ai_dismiss)) }
                val retryable = problem == AiProblem.FAILED || problem == AiProblem.TOO_HOT
                if (onRetry != null && retryable) {
                    TextButton(onClick = { haptics.confirm(); onRetry() }) { Text(stringResource(R.string.ai_try_again)) }
                }
            }
        }
    }
}

/**
 * A clearer name the language model suggested. Rename goes through the file manager (so tags,
 * favourites and the search index follow); a taken name gets " (1)", " (2)"….
 */
@Composable
fun NameSuggestionCard(path: String, modifiedAt: Long, ai: AiViewModel, onRenamed: (String) -> Unit) {
    val suggestion by remember(path) { ai.suggestionFlow(path) }.collectAsState(null)
    val current = suggestion
    if (current == null || current.status != RenameSuggestionEntity.PENDING || current.modifiedAt != modifiedAt) return
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val sky = Accent.SKY.colors()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = sky.container,
        contentColor = sky.onContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.ai_suggested_name), style = MaterialTheme.typography.labelLarge)
            }
            Text(current.suggestedName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.ai_suggested_name_body), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { haptics.tick(); ai.dismissSuggestion(path) }) { Text(stringResource(R.string.ai_dismiss)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            var result = fileOps.rename(path, current.suggestedName)
                            var n = 1
                            while (result == NameResult.Exists && n < 10) {
                                result = fileOps.rename(path, numbered(current.suggestedName, n++))
                            }
                            busy = false
                            if (result is NameResult.Done) {
                                haptics.confirm()
                                onRenamed(result.path)
                            } else {
                                haptics.reject()
                            }
                        }
                    }
                ) { Text(stringResource(R.string.ai_rename)) }
            }
        }
    }
}

private fun numbered(name: String, n: Int): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return name.substring(0, dot) + " ($n)" + name.substring(dot)
}

/** Tags on a file: the user's own and the model's suggestions (marked), with Add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsCard(path: String, ai: AiViewModel) {
    val haptics = rememberHaptics()
    val states by ai.states.collectAsState()
    val suggesting = states[path]?.containsKey(AiTask.INSIGHTS) == true
    val readingFirst = suggesting && states[path]?.containsKey(AiTask.PHOTO_TEXT) == true
    val tags by remember(path) { ai.tagsFlow(path) }.collectAsState(emptyList())
    var adding by remember { mutableStateOf(false) }
    AiCard(Icons.Rounded.AutoAwesome, stringResource(R.string.ai_tags_title), Accent.TEAL) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text("#${tag.tag}") },
                    leadingIcon = if (tag.isAuto) ({
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(R.string.cd_suggested_tag), modifier = Modifier.size(16.dp))
                    }) else null,
                    trailingIcon = {
                        IconButton(onClick = { haptics.tick(); ai.removeTag(path, tag.tag) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_remove_tag, tag.tag), modifier = Modifier.size(16.dp))
                        }
                    },
                    colors = InputChipDefaults.inputChipColors(
                        containerColor = if (tag.isAuto) Accent.TEAL.colors().container else Color.Transparent,
                        labelColor = if (tag.isAuto) Accent.TEAL.colors().onContainer else MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = Accent.TEAL.colors().onContainer
                    ),
                    shape = ExpressiveShapes.Pill
                )
            }
            AssistChip(
                onClick = { haptics.tick(); adding = true },
                label = { Text(stringResource(R.string.ai_add_tag)) },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                shape = ExpressiveShapes.Pill
            )
            // Only on request: the search model suggests tags (and a name, for unclear names).
            // Always offered; if something's missing, the card says what instead of hiding it.
            if (!suggesting) {
                AssistChip(
                    onClick = { haptics.confirm(); ai.suggestTags(path) },
                    label = { Text(stringResource(R.string.ai_suggest_tags)) },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = ExpressiveShapes.Pill
                )
            }
        }
        if (tags.isEmpty() && !suggesting) {
            Text(stringResource(R.string.ai_tags_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TaskStatus(
            path, AiTask.INSIGHTS, ai,
            workingText = stringResource(if (readingFirst) R.string.ai_reading_text_first else R.string.ai_tags_suggesting)
        )
        TaskProblem(path, AiTask.INSIGHTS, ai) { ai.suggestTags(path) }
    }
    if (adding) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.ai_add_tag_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ai_tag_hint)) },
                    prefix = { Text("#") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (text.isNotBlank()) { ai.addTag(path, text); haptics.confirm() }
                        adding = false
                    })
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { ai.addTag(path, text); haptics.confirm(); adding = false }) {
                    Text(stringResource(R.string.ai_add_tag))
                }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

/**
 * Text the OCR plugin read in a photo. Photos only: a PDF's text (its own or read from a scan) feeds
 * Suggest tags without being shown.
 */
@Composable
fun PhotoTextCard(path: String, ai: AiViewModel) {
    val context = LocalContext.current
    val states by ai.states.collectAsState()
    val reading = states[path]?.containsKey(AiTask.PHOTO_TEXT) == true
    val haptics = rememberHaptics()
    val row by remember(path) { ai.textFlow(path, TextSource.OCR) }.collectAsState(null)
    val text = row?.text.orEmpty()
    AiCard(Icons.Rounded.DocumentScanner, stringResource(R.string.ai_photo_text_title), Accent.TEAL) {
        when {
            reading -> TaskStatus(path, AiTask.PHOTO_TEXT, ai, workingText = stringResource(R.string.ai_reading_text))
            // Only on request: nothing is read until the user taps.
            row == null -> {
                Text(stringResource(R.string.ai_read_text_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { haptics.confirm(); ai.readText(path) }) {
                    Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.ai_read_text))
                }
            }
            text.isEmpty() -> Text(stringResource(R.string.ai_no_text_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                var expanded by rememberSaveable(path) { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 8,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                FilledTonalButton(onClick = { haptics.confirm(); copy(context, text) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ai_copy_all))
                }
            }
        }
        TaskProblem(path, AiTask.PHOTO_TEXT, ai) { ai.readText(path) }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
}

/** A recording's transcript: Transcribe on tap, progress, then timed lines that play from there. */
@Composable
fun TranscriptCard(path: String, ai: AiViewModel, sharedPlayer: AudioPlayer? = null) {
    val haptics = rememberHaptics()
    val states by ai.states.collectAsState()
    val allProgress by ai.progress.collectAsState()
    val state = states[path]?.get(AiTask.TRANSCRIPT)
    val transcribing = state != null
    val progress = allProgress[path]
    val row by remember(path) { ai.textFlow(path, TextSource.TRANSCRIPT) }.collectAsState(null)
    val segments = remember(row) { PluginData.segmentsFromJson(row?.segmentsJson) }
    var confirmLong by remember { mutableStateOf<Long?>(null) }
    val player = sharedPlayer ?: rememberAudioPlayer(path)
    AiCard(Icons.Rounded.Mic, stringResource(R.string.ai_transcript_title), Accent.SKY) {
        when {
            state?.phase == AiPhase.WAITING -> TaskStatus(path, AiTask.TRANSCRIPT, ai, workingText = "")
            transcribing -> {
                val p = progress ?: 0f
                WavyProgressIndicator(p, Modifier.fillMaxWidth(), color = Accent.SKY.colors().strong, trackColor = Accent.SKY.colors().strong.copy(alpha = 0.24f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_transcribing, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { haptics.tick(); ai.cancel(path, AiTask.TRANSCRIPT) }) { Text(stringResource(android.R.string.cancel)) }
                }
            }
            row == null -> {
                Text(stringResource(R.string.ai_transcribe_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    val duration = durationOf(path)
                    if (duration > LONG_RECORDING_MS) confirmLong = duration else { haptics.confirm(); ai.transcribe(path) }
                }) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.ai_transcribe))
                }
            }
            segments.isEmpty() -> Text(stringResource(R.string.ai_no_speech), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The viewer has its own controls above; the card only needs them on its own.
                    if (sharedPlayer == null) {
                        FilledIconButton(onClick = { haptics.tick(); player.toggle() }) {
                            Icon(
                                if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(if (player.playing) R.string.ai_pause else R.string.ai_play)
                            )
                        }
                    }
                    Text(stringResource(R.string.ai_tap_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    segments.forEach { segment ->
                        val current = player.playing && player.positionMs in segment.startMs until segment.endMs
                        val sky = Accent.SKY.colors()
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (current) sky.container else Color.Transparent,
                            contentColor = if (current) sky.onContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); player.playFrom(segment.startMs) }
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    DateUtils.formatElapsedTime(segment.startMs / 1000),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (current) sky.onContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(44.dp)
                                )
                                Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        TaskProblem(path, AiTask.TRANSCRIPT, ai) { ai.transcribe(path) }
    }
    confirmLong?.let { duration ->
        AlertDialog(
            onDismissRequest = { confirmLong = null },
            title = { Text(stringResource(R.string.ai_long_recording_title)) },
            text = { Text(stringResource(R.string.ai_long_recording_body, DateUtils.formatElapsedTime(duration / 1000))) },
            confirmButton = {
                TextButton(onClick = { haptics.confirm(); confirmLong = null; ai.transcribe(path) }) { Text(stringResource(R.string.ai_transcribe)) }
            },
            dismissButton = { TextButton(onClick = { confirmLong = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

private const val LONG_RECORDING_MS = 30 * 60 * 1000L

private fun durationOf(path: String): Long = runCatching {
    android.media.MediaMetadataRetriever().use { r ->
        r.setDataSource(path)
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    }
}.getOrDefault(0L)

/** Plays a recording, from the start or from a given time; released when its screen goes. */
class AudioPlayer(private val path: String) {
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    private var player: MediaPlayer? = null

    private fun ensure(): MediaPlayer? = player ?: runCatching {
        MediaPlayer().apply {
            setDataSource(path)
            prepare()
            setOnCompletionListener {
                this@AudioPlayer.playing = false
                this@AudioPlayer.positionMs = 0L
            }
        }
    }.getOrNull()?.also {
        player = it
        durationMs = it.duration.toLong().coerceAtLeast(0L)
    }

    /** Reads the length without starting playback. */
    fun prepare() {
        ensure()
    }

    fun playFrom(ms: Long) {
        val p = ensure() ?: return
        p.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        p.start()
        positionMs = ms
        playing = true
    }

    fun seekTo(ms: Long) {
        val p = ensure() ?: return
        p.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        positionMs = ms
    }

    fun toggle() {
        val p = ensure() ?: return
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            p.start()
            playing = true
        }
    }

    fun tick() {
        player?.takeIf { playing }?.let { positionMs = it.currentPosition.toLong() }
    }

    fun release() {
        player?.release()
        player = null
        playing = false
    }
}

@Composable
fun rememberAudioPlayer(path: String): AudioPlayer {
    val player = remember(path) { AudioPlayer(path) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(250)
        }
    }
    return player
}
