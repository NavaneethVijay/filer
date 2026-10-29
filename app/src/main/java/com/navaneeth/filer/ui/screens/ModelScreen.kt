package com.navaneeth.filer.ui.screens

import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ShapedIcon
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.data.model.DownloadState
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.data.model.ModelDownloadManager
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.ui.components.GroupDivider
import com.navaneeth.filer.ui.components.GroupTitle
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.isBusy
import com.navaneeth.filer.ui.components.rememberSmartSearchEnabler
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel
import com.navaneeth.filer.ui.viewmodel.SettingsViewModel

@Composable
fun ModelScreen(
    library: LibraryViewModel,
    settings: SettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val state by library.downloadState.collectAsState()
    val accelerator by settings.accelerator.collectAsState()
    val enable = rememberSmartSearchEnabler(library)
    var confirmRemove by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val modelSize = formatSize(context, ModelDownloadManager.EXPECTED_SIZE_BYTES)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_hardware)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp)
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val blue = Accent.BLUE.colors()
                            ShapedIcon(
                                icon = Icons.Rounded.AutoAwesome,
                                shape = ExpressiveShapes.Cookie,
                                container = blue.container,
                                content = blue.strong,
                                size = 52.dp,
                                iconSize = 26.dp
                            )
                            Spacer(Modifier.size(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.model_name), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    stringResource(R.string.model_summary, modelSize),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        AnimatedContent(
                            targetState = state,
                            contentKey = { it::class },
                            transitionSpec = { fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
                            label = "modelState"
                        ) { current ->
                            ModelStateBody(
                                state = current,
                                onDownload = enable,
                                onPause = { haptics.tick(); library.pauseSmartSearchDownload() },
                                onCancel = { haptics.tick(); library.cancelSmartSearchDownload() },
                                onRemove = { confirmRemove = true }
                            )
                        }
                    }
                }
            }

            item { GroupTitle(stringResource(R.string.processing_title)) }
            item {
                SettingsGroup {
                    AcceleratorOption(
                        title = stringResource(R.string.accelerator_auto),
                        subtitle = stringResource(R.string.accelerator_auto_body),
                        selected = accelerator == PreferencesManager.ACCELERATOR_AUTO,
                        onSelect = { haptics.tick(); settings.setAccelerator(PreferencesManager.ACCELERATOR_AUTO) }
                    )
                    GroupDivider()
                    AcceleratorOption(
                        title = stringResource(R.string.accelerator_cpu),
                        subtitle = stringResource(R.string.accelerator_cpu_body),
                        selected = accelerator == PreferencesManager.ACCELERATOR_CPU,
                        onSelect = { haptics.tick(); settings.setAccelerator(PreferencesManager.ACCELERATOR_CPU) }
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.accelerator_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }

            item { GroupTitle(stringResource(R.string.about_model_title)) }
            item {
                SettingsGroup {
                    SettingRow(Icons.Rounded.PhoneAndroid, stringResource(R.string.about_model_local), subtitle = stringResource(R.string.about_model_local_body))
                    GroupDivider()
                    SettingRow(Icons.Rounded.CloudDownload, stringResource(R.string.about_model_network), subtitle = stringResource(R.string.about_model_network_body))
                    GroupDivider()
                    SettingRow(Icons.Rounded.Storage, stringResource(R.string.about_model_storage), subtitle = stringResource(R.string.about_model_storage_body, modelSize))
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.remove_model_title),
            body = stringResource(R.string.remove_model_body),
            confirm = stringResource(R.string.action_remove),
            onConfirm = { haptics.confirm(); settings.purgeModel(); confirmRemove = false },
            onDismiss = { confirmRemove = false }
        )
    }
}

@Composable
private fun ModelStateBody(
    state: DownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state is DownloadState.Ready -> {
                Surface(shape = MaterialTheme.shapes.small, color = IndexStatus.INDEXED.colors().container) {
                    Text(
                        stringResource(R.string.model_installed),
                        style = MaterialTheme.typography.labelLarge,
                        color = IndexStatus.INDEXED.colors().content,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.remove_model)) }
            }
            state.isBusy -> {
                val fraction = when (state) {
                    is DownloadState.Downloading -> state.percent
                    is DownloadState.Verifying -> state.percent
                    else -> 0f
                }
                val animated by animateFloatAsState(fraction, tween(500), label = "modelProgress")
                if (state is DownloadState.Checking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = if (state is DownloadState.Downloading) {
                        stringResource(
                            R.string.smart_search_progress,
                            formatSize(context, state.bytesRead),
                            formatSize(context, state.totalBytes.takeIf { it > 0 } ?: ModelDownloadManager.EXPECTED_SIZE_BYTES)
                        )
                    } else {
                        stringResource(R.string.smart_search_setting_up)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPause) { Text(stringResource(R.string.action_pause)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            else -> {
                Text(
                    stringResource(
                        when (state) {
                            DownloadState.Paused -> R.string.smart_search_paused
                            is DownloadState.Error -> R.string.smart_search_error
                            else -> R.string.model_not_installed
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is DownloadState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) {
                        Text(
                            stringResource(
                                when (state) {
                                    DownloadState.Paused -> R.string.action_resume
                                    is DownloadState.Error -> R.string.action_try_again
                                    else -> R.string.action_download
                                }
                            )
                        )
                    }
                    if (state == DownloadState.Paused) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcceleratorOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) }
    )
}
