package com.navaneeth.filer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.inference.RemoteStatus
import com.navaneeth.filer.data.worker.EngineCompareWorker
import com.navaneeth.filer.data.worker.EngineComparison
import com.navaneeth.filer.ui.components.ExpressiveLoader
import com.navaneeth.filer.ui.components.GroupDivider
import com.navaneeth.filer.ui.components.GroupTitle
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup
import com.navaneeth.filer.ui.components.SwitchRow
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.CompareState
import com.navaneeth.filer.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the status line asks the Mac whether it's there while this section is on screen. */
private const val MAC_POLL_MS = 3_000L

/**
 * Settings › Developer (after tapping the version 7 times): index with a USB-connected Mac,
 * start a pass now, and compare the Mac's vectors with the phone's.
 */
@Composable
fun DeveloperSection(settings: SettingsViewModel, phoneModelReady: Boolean) {
    val haptics = rememberHaptics()
    val indexWithMac by settings.indexWithMac.collectAsState()
    val status by settings.macStatus.collectAsState()
    val compare by settings.compare.collectAsState()
    val progress by settings.indexProgress.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val startedText = stringResource(R.string.via_mac_started)
    val switchedText = stringResource(R.string.via_mac_switched)
    val connected = status is RemoteStatus.Connected && phoneModelReady

    LaunchedEffect(Unit) {
        while (true) {
            settings.refreshMac()
            delay(MAC_POLL_MS)
        }
    }

    Column {
        GroupTitle(stringResource(R.string.section_developer))
        SettingsGroup {
            SwitchRow(
                icon = Icons.Rounded.Computer,
                title = stringResource(R.string.index_with_mac),
                subtitle = macStatusText(status, phoneModelReady, indexWithMac),
                checked = indexWithMac,
                onCheckedChange = { haptics.toggle(it); settings.setIndexWithMac(it) }
            )
            GroupDivider()
            SettingRow(
                icon = Icons.Rounded.PlayArrow,
                title = stringResource(R.string.index_now_via_mac),
                subtitle = when {
                    progress.isRunning && progress.viaMac && progress.itemsPerSec > 0f ->
                        stringResource(R.string.via_mac_running_rate, progress.processedCount, progress.totalCount, progress.itemsPerSec)
                    progress.isRunning && progress.viaMac -> stringResource(R.string.via_mac_running, progress.processedCount, progress.totalCount)
                    progress.isRunning -> stringResource(R.string.via_mac_running_phone, progress.processedCount, progress.totalCount)
                    else -> stringResource(R.string.index_now_via_mac_body)
                },
                accent = connected,
                trailing = if (progress.isRunning && progress.viaMac) ({ ExpressiveLoader(size = 32.dp) }) else null,
                onClick = if (connected) ({
                    haptics.confirm()
                    val started = settings.indexNowViaMac()
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(if (started) startedText else switchedText)
                    }
                }) else null
            )
            GroupDivider()
            val running = compare is CompareState.Running
            SettingRow(
                icon = Icons.AutoMirrored.Rounded.CompareArrows,
                title = stringResource(R.string.compare_engines),
                subtitle = stringResource(if (running) R.string.compare_running else R.string.compare_engines_body),
                onClick = if (connected && !running) ({ haptics.confirm(); settings.startCompare() }) else null,
                trailing = if (running) ({ ExpressiveLoader(size = 32.dp) }) else null
            )
            GroupDivider()
            SettingRow(
                icon = Icons.Rounded.Code,
                title = stringResource(R.string.turn_off_developer_mode),
                onClick = { haptics.toggle(false); settings.turnOffDeveloperMode() }
            )
        }
    }

    when (val state = compare) {
        is CompareState.Done -> CompareDialog(state.result, onDismiss = settings::dismissCompare)
        is CompareState.Failed -> AlertDialog(
            onDismissRequest = settings::dismissCompare,
            title = { Text(stringResource(R.string.compare_title)) },
            text = { Text(stringResource(compareErrorText(state.reason))) },
            confirmButton = { TextButton(onClick = settings::dismissCompare) { Text(stringResource(R.string.action_ok)) } }
        )
        else -> Unit
    }
}

@Composable
private fun macStatusText(status: RemoteStatus, phoneModelReady: Boolean, enabled: Boolean): String = when {
    !phoneModelReady -> stringResource(R.string.mac_status_no_phone_model)
    // Off: indexing stays on the phone whatever the Mac is doing; still say if it's there (for Compare).
    !enabled && status is RemoteStatus.Connected -> stringResource(R.string.mac_status_off_connected)
    !enabled -> stringResource(R.string.mac_status_off)
    status is RemoteStatus.Connected -> stringResource(R.string.mac_status_connected, status.health.engine, status.health.model.removeSuffix(".litertlm"))
    status is RemoteStatus.WrongModel -> stringResource(R.string.mac_status_wrong_model, status.health.model)
    status is RemoteStatus.Unreachable -> stringResource(R.string.mac_status_unreachable)
    else -> stringResource(R.string.mac_status_checking)
}

private fun compareErrorText(reason: String): Int = when (reason) {
    EngineCompareWorker.ERROR_NO_MODEL -> R.string.compare_error_no_model
    EngineCompareWorker.ERROR_NO_MAC -> R.string.compare_error_no_mac
    EngineCompareWorker.ERROR_NOTHING_INDEXED -> R.string.compare_error_nothing
    else -> R.string.compare_error_failed
}

@Composable
private fun CompareDialog(result: EngineComparison, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compare_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.compare_engine_line, result.macEngine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CompareKind(
                    title = stringResource(R.string.compare_kind_passages, result.textCount),
                    count = result.textCount,
                    mean = result.textMean,
                    min = result.textMin,
                    phoneMs = result.phoneTextMs,
                    macMs = result.macTextMs
                )
                CompareKind(
                    title = stringResource(R.string.compare_kind_photos, result.imageCount),
                    count = result.imageCount,
                    mean = result.imageMean,
                    min = result.imageMin,
                    phoneMs = result.phoneImageMs,
                    macMs = result.macImageMs
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }
    )
}

@Composable
private fun CompareKind(title: String, count: Int, mean: Float, min: Float, phoneMs: Float, macMs: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (count == 0) {
            Text(stringResource(R.string.compare_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.compare_similarity, mean, min), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.compare_speed, phoneMs, macMs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
