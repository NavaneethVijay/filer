package com.navaneeth.filer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.model.DownloadState
import com.navaneeth.filer.data.model.ModelDownloadManager
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel

val DownloadState.isBusy: Boolean
    get() = this is DownloadState.Checking || this is DownloadState.Downloading || this is DownloadState.Verifying

/**
 * Returns a function that starts the smart-search download, first asking before using
 * mobile data when the user chose Wi-Fi only.
 */
@Composable
fun rememberSmartSearchEnabler(library: LibraryViewModel): () -> Unit {
    var askMetered by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    if (askMetered) {
        AlertDialog(
            onDismissRequest = { askMetered = false },
            title = { Text(stringResource(R.string.metered_title)) },
            text = { Text(stringResource(R.string.metered_body)) },
            confirmButton = {
                TextButton(onClick = { askMetered = false; library.enableSmartSearch() }) {
                    Text(stringResource(R.string.metered_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { askMetered = false }) { Text(stringResource(R.string.metered_wait)) }
            }
        )
    }
    return {
        haptics.tick()
        if (library.needsMeteredConfirmation()) askMetered = true else library.enableSmartSearch()
    }
}

/** The one-time invitation on Browse to download the on-device search model. */
@Composable
fun SmartSearchBanner(
    state: DownloadState,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val blue = Accent.BLUE.colors()
    // The one emphasised card on Browse: a light blue container, a cookie-shaped sparkle.
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = blue.container, contentColor = blue.onContainer)
    ) {
        Row(Modifier.padding(start = 20.dp, top = 20.dp, end = 16.dp, bottom = 12.dp)) {
            ShapedIcon(Icons.Rounded.AutoAwesome, ExpressiveShapes.Cookie, blue.strong, blue.onStrong, size = 44.dp, iconSize = 22.dp)
            Spacer(Modifier.size(16.dp))
            AnimatedContent(
                targetState = state.isBusy,
                transitionSpec = { fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
                label = "bannerContent",
                modifier = Modifier.weight(1f)
            ) { busy ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) {
                        val (fraction, label) = when (state) {
                            is DownloadState.Downloading -> state.percent to stringResource(
                                R.string.smart_search_progress,
                                formatSize(context, state.bytesRead),
                                formatSize(context, state.totalBytes.takeIf { it > 0 } ?: ModelDownloadManager.EXPECTED_SIZE_BYTES)
                            )
                            is DownloadState.Verifying -> state.percent to stringResource(R.string.smart_search_finishing)
                            else -> 0f to stringResource(R.string.smart_search_starting)
                        }
                        val animated by animateFloatAsState(fraction, tween(500), label = "downloadProgress")
                        Text(stringResource(R.string.smart_search_setting_up), style = MaterialTheme.typography.titleMedium)
                        WavyProgressIndicator(
                            progress = animated,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            color = blue.strong,
                            trackColor = blue.strong.copy(alpha = 0.24f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onPause, colors = ButtonDefaults.textButtonColors(contentColor = blue.onContainer)) { Text(stringResource(R.string.action_pause)) }
                        }
                    } else {
                        Text(stringResource(R.string.smart_search_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(
                                when (state) {
                                    is DownloadState.Error -> R.string.smart_search_error
                                    DownloadState.Paused -> R.string.smart_search_paused
                                    else -> R.string.smart_search_body
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Button(
                                onClick = onEnable,
                                colors = ButtonDefaults.buttonColors(containerColor = blue.strong, contentColor = blue.onStrong)
                            ) {
                                Text(
                                    stringResource(
                                        when (state) {
                                            is DownloadState.Error -> R.string.action_try_again
                                            DownloadState.Paused -> R.string.action_resume
                                            else -> R.string.smart_search_enable
                                        }
                                    )
                                )
                            }
                            TextButton(
                                onClick = onDismiss,
                                colors = ButtonDefaults.textButtonColors(contentColor = blue.onContainer)
                            ) { Text(stringResource(R.string.action_not_now)) }
                        }
                    }
                }
            }
        }
    }
}
