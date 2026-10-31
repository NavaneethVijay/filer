package com.navaneeth.filer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import com.navaneeth.filer.data.update.UpdateStatus
import com.navaneeth.filer.ui.util.formatSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Description
import com.navaneeth.filer.data.storage.IndexKind
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.navaneeth.filer.ui.components.BoldLargeTopBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.data.model.DownloadState
import com.navaneeth.filer.ui.components.GroupDivider
import com.navaneeth.filer.ui.components.GroupTitle
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup
import com.navaneeth.filer.ui.components.SwitchRow
import com.navaneeth.filer.ui.components.rememberSmartSearchEnabler
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.SmartFolder
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel
import com.navaneeth.filer.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    library: LibraryViewModel,
    settings: SettingsViewModel,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAiPlugins: () -> Unit,
    onOpenIndexStatus: () -> Unit,
    onOpenIndexedFiles: () -> Unit,
    onOpenModel: () -> Unit,
    onBrowseStorage: () -> Unit
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val smartFolders by library.smartFolders.collectAsState()
    val wholePhone by library.wholePhone.collectAsState()
    var confirmWholePhoneOff by remember { mutableStateOf(false) }
    val indexedKinds by settings.indexedKinds.collectAsState()
    var confirmKindOff by remember { mutableStateOf<IndexKind?>(null) }
    val downloadState by library.downloadState.collectAsState()
    val showAdvanced by library.showAdvanced.collectAsState()
    val builtInViewer by settings.builtInViewer.collectAsState()
    val phoneColors by settings.phoneColors.collectAsState()
    val chargingOnly by settings.chargingOnly.collectAsState()
    val wifiOnly by settings.wifiOnly.collectAsState()
    val summary by settings.indexSummary.collectAsState()
    val developerMode by settings.developerMode.collectAsState()

    var confirmRemove by remember { mutableStateOf<SmartFolder?>(null) }
    var confirmClearIndex by remember { mutableStateOf(false) }
    var confirmRemoveModel by remember { mutableStateOf(false) }

    val enableSmartSearch = rememberSmartSearchEnabler(library)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val powerManager = remember(context) { context.getSystemService(android.os.PowerManager::class.java) }
    var isUnrestricted by remember { mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        isUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BoldLargeTopBar(stringResource(R.string.tab_settings), onBack, scrollBehavior)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = LocalBottomBarSpace.current + 32.dp)
        ) {
            item { GroupTitle(stringResource(R.string.section_smart_folders)) }
            item {
                SettingsGroup {
                    val phone = wholePhone
                    val phoneFailedNote = if (phone != null && phone.failed > 0) " · " + pluralStringResource(R.plurals.smart_folder_failed, phone.failed, phone.failed) else ""
                    SwitchRow(
                        icon = Icons.Rounded.Smartphone,
                        title = stringResource(R.string.whole_phone_title),
                        subtitle = (if (phone != null && phone.indexing) {
                            stringResource(R.string.smart_folder_progress, phone.ready, phone.total)
                        } else {
                            stringResource(R.string.whole_phone_body)
                        }) + phoneFailedNote,
                        checked = phone != null,
                        onCheckedChange = { on ->
                            haptics.toggle(on)
                            if (on) library.setWholePhone(true) else confirmWholePhoneOff = true
                        }
                    )
                    GroupDivider()
                    smartFolders.forEach { folder ->
                        val failedNote = if (folder.failed > 0) " · " + pluralStringResource(R.plurals.smart_folder_failed, folder.failed, folder.failed) else ""
                        SettingRow(
                            icon = Icons.Rounded.Folder,
                            title = folder.name,
                            subtitle = (if (folder.indexing) {
                                stringResource(R.string.smart_folder_progress, folder.ready, folder.total)
                            } else {
                                library.displayPath(folder.path)
                            }) + failedNote,
                            trailing = {
                                IconButton(onClick = { confirmRemove = folder }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_remove_folder, folder.name))
                                }
                            }
                        )
                        GroupDivider()
                    }
                    SettingRow(
                        icon = Icons.Rounded.Add,
                        title = stringResource(R.string.action_add_folder),
                        subtitle = stringResource(R.string.smart_folders_add_hint),
                        accent = true,
                        onClick = onBrowseStorage
                    )
                }
            }

            item { GroupTitle(stringResource(R.string.section_kinds)) }
            item {
                SettingsGroup {
                    IndexKind.entries.forEachIndexed { index, kind ->
                        if (index > 0) GroupDivider()
                        SwitchRow(
                            icon = kind.icon,
                            title = stringResource(kind.title),
                            subtitle = stringResource(kind.body),
                            checked = kind in indexedKinds,
                            onCheckedChange = { on ->
                                haptics.toggle(on)
                                if (on) settings.setKindIndexed(kind, true) else confirmKindOff = kind
                            }
                        )
                    }
                }
            }

            item { GroupTitle(stringResource(R.string.section_search)) }
            item {
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.smart_search_title),
                        subtitle = smartSearchStatus(downloadState),
                        trailing = { SmartSearchAction(downloadState, onEnable = enableSmartSearch) }
                    )
                    GroupDivider()
                    SettingRow(
                        icon = Icons.Rounded.Extension,
                        title = stringResource(R.string.settings_ai_plugins),
                        subtitle = stringResource(R.string.settings_ai_plugins_body),
                        chevron = true,
                        onClick = { haptics.tick(); onOpenAiPlugins() }
                    )
                }
            }

            item { GroupTitle(stringResource(R.string.section_general)) }
            item {
                SettingsGroup {
                    SwitchRow(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.phone_colors),
                        subtitle = stringResource(R.string.phone_colors_body),
                        checked = phoneColors,
                        onCheckedChange = { haptics.toggle(it); settings.setPhoneColors(it) }
                    )
                    GroupDivider()
                    SwitchRow(
                        icon = Icons.Rounded.PhotoLibrary,
                        title = stringResource(R.string.built_in_viewer),
                        subtitle = stringResource(R.string.built_in_viewer_body),
                        checked = builtInViewer,
                        onCheckedChange = { haptics.toggle(it); settings.setBuiltInViewer(it) }
                    )
                    GroupDivider()
                    SwitchRow(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.show_details),
                        subtitle = stringResource(R.string.show_details_body),
                        checked = showAdvanced,
                        onCheckedChange = { haptics.toggle(it); settings.setShowAdvanced(it) }
                    )
                    GroupDivider()
                    SettingRow(
                        icon = Icons.Rounded.Shield,
                        title = stringResource(R.string.privacy_about),
                        onClick = onOpenPrivacy,
                        chevron = true
                    )
                }
            }

            item { GroupTitle(stringResource(R.string.updates_title)) }
            item {
                SettingsGroup {
                    UpdateSection(settings, versionName)
                }
            }

            item {
                AnimatedVisibility(
                    visible = showAdvanced,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        GroupTitle(stringResource(R.string.section_indexing))
                        SettingsGroup {
                            IndexProgressRow(summary.ready, summary.total, summary.failed, onClick = onOpenIndexStatus)
                            GroupDivider()
                            SettingRow(Icons.Rounded.FolderOpen, stringResource(R.string.indexed_files), onClick = onOpenIndexedFiles, chevron = true)
                            GroupDivider()
                            SettingRow(
                                Icons.Rounded.Memory,
                                stringResource(R.string.model_hardware),
                                subtitle = stringResource(R.string.model_hardware_body),
                                onClick = onOpenModel,
                                chevron = true
                            )
                            GroupDivider()
                            SwitchRow(
                                icon = Icons.Rounded.BatteryChargingFull,
                                title = stringResource(R.string.charging_only),
                                checked = chargingOnly,
                                onCheckedChange = { haptics.toggle(it); settings.setChargingOnly(it) }
                            )
                            GroupDivider()
                            SwitchRow(
                                icon = Icons.Rounded.Bolt,
                                title = stringResource(R.string.unrestricted_battery),
                                subtitle = stringResource(R.string.unrestricted_battery_body),
                                checked = isUnrestricted,
                                onCheckedChange = {
                                    haptics.toggle(it)
                                    if (!isUnrestricted) {
                                        try {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = android.net.Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    }
                                }
                            )
                            GroupDivider()
                            SwitchRow(
                                icon = Icons.Rounded.Wifi,
                                title = stringResource(R.string.wifi_only),
                                checked = wifiOnly,
                                onCheckedChange = { haptics.toggle(it); settings.setWifiOnly(it) }
                            )
                            GroupDivider()
                            SettingRow(Icons.Rounded.Refresh, stringResource(R.string.rescan_now), onClick = { haptics.confirm(); library.rescan() })
                            GroupDivider()
                            SettingRow(
                                Icons.Rounded.DeleteSweep,
                                stringResource(R.string.clear_index),
                                destructive = true,
                                onClick = { confirmClearIndex = true }
                            )
                            if (downloadState is DownloadState.Ready) {
                                GroupDivider()
                                SettingRow(
                                    Icons.Rounded.Close,
                                    stringResource(R.string.remove_model),
                                    destructive = true,
                                    onClick = { confirmRemoveModel = true }
                                )
                            }
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(
                    visible = showAdvanced && developerMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    DeveloperSection(settings, phoneModelReady = downloadState is DownloadState.Ready)
                }
            }

            item {
                Text(
                    text = stringResource(R.string.version_label, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                )
            }
        }
    }

    confirmRemove?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.remove_smart_title, folder.name),
            body = stringResource(R.string.remove_smart_body),
            confirm = stringResource(R.string.action_remove),
            onConfirm = { haptics.confirm(); library.removeSmartFolder(folder); confirmRemove = null },
            onDismiss = { confirmRemove = null }
        )
    }
    confirmKindOff?.let { kind ->
        ConfirmDialog(
            title = stringResource(R.string.kind_off_title, stringResource(kind.title).lowercase()),
            body = stringResource(R.string.kind_off_body),
            confirm = stringResource(R.string.action_turn_off),
            onConfirm = { haptics.confirm(); settings.setKindIndexed(kind, false); confirmKindOff = null },
            onDismiss = { confirmKindOff = null }
        )
    }
    if (confirmWholePhoneOff) {
        ConfirmDialog(
            title = stringResource(R.string.whole_phone_off_title),
            body = stringResource(R.string.whole_phone_off_body),
            confirm = stringResource(R.string.action_turn_off),
            onConfirm = { haptics.confirm(); library.setWholePhone(false); confirmWholePhoneOff = false },
            onDismiss = { confirmWholePhoneOff = false }
        )
    }
    if (confirmClearIndex) {
        ConfirmDialog(
            title = stringResource(R.string.clear_index_title),
            body = stringResource(R.string.clear_index_body),
            confirm = stringResource(R.string.action_clear),
            onConfirm = { haptics.confirm(); settings.clearIndex(); confirmClearIndex = false },
            onDismiss = { confirmClearIndex = false }
        )
    }
    if (confirmRemoveModel) {
        ConfirmDialog(
            title = stringResource(R.string.remove_model_title),
            body = stringResource(R.string.remove_model_body),
            confirm = stringResource(R.string.action_remove),
            onConfirm = { haptics.confirm(); settings.purgeModel(); confirmRemoveModel = false },
            onDismiss = { confirmRemoveModel = false }
        )
    }
}

private val IndexKind.icon: ImageVector
    get() = when (this) {
        IndexKind.PHOTOS -> Icons.Rounded.Image
        IndexKind.PDFS -> Icons.Rounded.PictureAsPdf
        IndexKind.TEXT -> Icons.Rounded.Description
    }

private val IndexKind.title: Int
    get() = when (this) {
        IndexKind.PHOTOS -> R.string.kind_photos
        IndexKind.PDFS -> R.string.kind_pdfs
        IndexKind.TEXT -> R.string.kind_text
    }

private val IndexKind.body: Int
    get() = when (this) {
        IndexKind.PHOTOS -> R.string.kind_photos_body
        IndexKind.PDFS -> R.string.kind_pdfs_body
        IndexKind.TEXT -> R.string.kind_text_body
    }

@Composable
private fun smartSearchStatus(state: DownloadState): String = when (state) {
    DownloadState.Ready -> stringResource(R.string.smart_search_status_on)
    is DownloadState.Downloading -> stringResource(R.string.smart_search_status_downloading, (state.percent * 100).toInt())
    is DownloadState.Checking, is DownloadState.Verifying -> stringResource(R.string.smart_search_setting_up)
    DownloadState.Paused -> stringResource(R.string.smart_search_status_paused)
    is DownloadState.Error -> stringResource(R.string.smart_search_status_error)
    DownloadState.Idle -> stringResource(R.string.smart_search_status_off)
}

@Composable
private fun SmartSearchAction(state: DownloadState, onEnable: () -> Unit) {
    when (state) {
        DownloadState.Ready -> Text(stringResource(R.string.state_on), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        is DownloadState.Downloading -> {
            val progress by animateFloatAsState(state.percent, label = "settingsDownload")
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        }
        is DownloadState.Checking, is DownloadState.Verifying ->
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        DownloadState.Paused -> TextButton(onClick = onEnable) { Text(stringResource(R.string.action_resume)) }
        is DownloadState.Error -> TextButton(onClick = onEnable) { Text(stringResource(R.string.action_try_again)) }
        DownloadState.Idle -> TextButton(onClick = onEnable) { Text(stringResource(R.string.action_turn_on)) }
    }
}

@Composable
private fun IndexProgressRow(ready: Int, total: Int, failed: Int, onClick: () -> Unit) {
    // Failed files are finished too (retried only when they change), so they fill the bar.
    val fraction = if (total > 0) (ready + failed) / total.toFloat() else 0f
    val animated by animateFloatAsState(fraction, label = "indexFraction")
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        leadingContent = { Icon(Icons.Rounded.Analytics, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.index_status)) },
        supportingContent = {
            Column {
                Text(
                    if (failed > 0) stringResource(R.string.index_ready_with_failures, ready, total, failed)
                    else stringResource(R.string.index_ready, ready, total)
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun UpdateSection(settings: SettingsViewModel, currentVersion: String) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val updateStatus by settings.updateStatus.collectAsState()
    val autoCheck by settings.autoCheckUpdates.collectAsState()

    when (val status = updateStatus) {
        UpdateStatus.Idle -> {
            SettingRow(
                icon = Icons.Rounded.SystemUpdate,
                title = stringResource(R.string.updates_check),
                subtitle = stringResource(R.string.version_label, currentVersion),
                onClick = { haptics.tick(); settings.checkForUpdates() }
            )
        }
        UpdateStatus.Checking -> {
            SettingRow(
                icon = Icons.Rounded.SystemUpdate,
                title = stringResource(R.string.updates_checking),
                trailing = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            )
        }
        is UpdateStatus.UpToDate -> {
            SettingRow(
                icon = Icons.Rounded.CheckCircle,
                title = stringResource(R.string.updates_up_to_date, status.currentVersion),
                subtitle = stringResource(R.string.updates_check),
                onClick = { haptics.tick(); settings.checkForUpdates() }
            )
        }
        is UpdateStatus.Available -> {
            val release = status.release
            val sizeStr = formatSize(context, release.apkSizeBytes)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.updates_available_title, release.versionName),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (release.title.isNotBlank()) {
                            Text(
                                text = release.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { haptics.confirm(); settings.downloadUpdate(release) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.updates_action_download, sizeStr))
                }
            }
        }
        is UpdateStatus.Downloading -> {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.updates_downloading, (status.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is UpdateStatus.ReadyToInstall -> {
            val release = status.release
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.updates_ready_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.updates_ready_body, release.versionName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { haptics.confirm(); settings.installUpdate(context, status.apkFile) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.updates_action_install))
                }
            }
        }
        is UpdateStatus.Failed -> {
            SettingRow(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.updates_error),
                subtitle = status.message,
                onClick = { haptics.tick(); settings.checkForUpdates() }
            )
        }
    }

    GroupDivider()
    SwitchRow(
        icon = Icons.Rounded.Sync,
        title = stringResource(R.string.updates_auto_check),
        subtitle = stringResource(R.string.updates_auto_check_body),
        checked = autoCheck,
        onCheckedChange = {
            haptics.toggle(it)
            settings.setAutoCheckUpdates(it)
        }
    )
}
