package com.navaneeth.filer.ui.screens

import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.Accent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DataArray
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.util.rememberHaptics
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.components.GroupDivider
import com.navaneeth.filer.ui.components.GroupTitle
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup

private const val DEVELOPER_TAPS = 7

@Composable
fun PrivacyAboutScreen(onBack: () -> Unit, onUnlockDeveloper: () -> Boolean) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var versionTaps by remember { mutableIntStateOf(0) }
    val developerOn = stringResource(R.string.developer_mode_on)
    val developerAlreadyOn = stringResource(R.string.developer_mode_already_on)
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_about)) },
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
                    color = Accent.BLUE.colors().container,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        Text(stringResource(R.string.privacy_hero_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(stringResource(R.string.privacy_hero_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }

            item { GroupTitle(stringResource(R.string.privacy_access_title)) }
            item {
                SettingsGroup {
                    SettingRow(Icons.Rounded.Folder, stringResource(R.string.privacy_access_folders), subtitle = stringResource(R.string.privacy_access_folders_body))
                    GroupDivider()
                    SettingRow(Icons.Rounded.PhotoLibrary, stringResource(R.string.privacy_access_photos), subtitle = stringResource(R.string.privacy_access_photos_body))
                    GroupDivider()
                    SettingRow(Icons.Rounded.Notifications, stringResource(R.string.privacy_access_notifications), subtitle = stringResource(R.string.privacy_access_notifications_body))
                }
            }

            item { GroupTitle(stringResource(R.string.privacy_stores_title)) }
            item {
                SettingsGroup {
                    SettingRow(Icons.Rounded.Link, stringResource(R.string.privacy_stores_pointers), subtitle = stringResource(R.string.privacy_stores_pointers_body))
                    GroupDivider()
                    SettingRow(Icons.AutoMirrored.Rounded.ShortText, stringResource(R.string.privacy_stores_snippets), subtitle = stringResource(R.string.privacy_stores_snippets_body))
                    GroupDivider()
                    SettingRow(Icons.Rounded.DataArray, stringResource(R.string.privacy_stores_vectors), subtitle = stringResource(R.string.privacy_stores_vectors_body))
                }
            }

            item { GroupTitle(stringResource(R.string.privacy_network_title)) }
            item {
                SettingsGroup {
                    SettingRow(Icons.Rounded.CloudDownload, stringResource(R.string.privacy_network_model), subtitle = stringResource(R.string.privacy_network_model_body))
                    GroupDivider()
                    SettingRow(Icons.Rounded.CloudOff, stringResource(R.string.privacy_network_none), subtitle = stringResource(R.string.privacy_network_none_body))
                }
            }

            item {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Tapping the version 7 times unlocks Developer mode (Settings › Developer).
                    Text(
                        text = stringResource(R.string.version_label, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            versionTaps++
                            if (versionTaps >= DEVELOPER_TAPS) {
                                versionTaps = 0
                                haptics.confirm()
                                val message = if (onUnlockDeveloper()) developerOn else developerAlreadyOn
                                scope.launch { snackbar.showSnackbar(message) }
                            } else {
                                haptics.tick()
                            }
                        }
                    )
                    Text(
                        text = stringResource(R.string.geonames_credit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
