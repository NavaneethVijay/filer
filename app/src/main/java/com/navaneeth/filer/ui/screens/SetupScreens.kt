package com.navaneeth.filer.ui.screens

import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.Accent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.navaneeth.filer.R
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel

/**
 * Asks for "All files access" so Filer can work like a file manager. The grant happens in system
 * Settings; we re-check whenever the user comes back.
 */
@Composable
fun AllFilesAccessScreen(
    library: LibraryViewModel,
    onGranted: () -> Unit
) {
    val context = LocalContext.current
    val hasAccess by library.hasAccess.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { library.refreshAccess() }
    LaunchedEffect(hasAccess) { if (hasAccess) onGranted() }

    Scaffold(
        bottomBar = {
            Button(
                onClick = { context.startActivity(Storage.allFilesAccessIntent(context)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .height(56.dp)
            ) { Text(stringResource(R.string.access_allow), style = MaterialTheme.typography.titleMedium) }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.access_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AccessPoint(Icons.Rounded.Folder, R.string.access_point_browse)
            AccessPoint(Icons.Rounded.Shield, R.string.access_point_private)
            AccessPoint(Icons.Rounded.AutoAwesome, R.string.access_point_choose)
        }
    }
}

@Composable
private fun AccessPoint(icon: ImageVector, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = Accent.BLUE.colors().container, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Optional first pick of smart-search folders; everything can be changed later. */
@Composable
fun SmartFoldersSetupScreen(
    library: LibraryViewModel,
    onDone: () -> Unit
) {
    val haptics = rememberHaptics()
    val suggestions = remember { library.suggestedFolders() }
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { suggestions.forEach { (p, on) -> put(p, on) } } }
    var wholePhone by rememberSaveable { mutableStateOf(false) }
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)

    fun finish(paths: List<String>, includeWholePhone: Boolean) {
        haptics.confirm()
        library.completeSetup(paths, includeWholePhone)
        onDone()
    }

    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Button(
                    onClick = { finish(selected.filterValues { it }.keys.toList(), wholePhone) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) { Text(stringResource(R.string.action_continue), style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { finish(emptyList(), includeWholePhone = false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_skip_for_now))
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.smart_setup_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.smart_setup_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column {
                        ListItem(
                            colors = rowColors,
                            modifier = Modifier.toggleable(
                                value = wholePhone,
                                role = Role.Checkbox,
                                onValueChange = { haptics.toggle(it); wholePhone = it }
                            ),
                            leadingContent = { Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(stringResource(R.string.whole_phone_title)) },
                            supportingContent = { Text(stringResource(R.string.whole_phone_setup_body)) },
                            trailingContent = { Checkbox(checked = wholePhone, onCheckedChange = null) }
                        )
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        suggestions.forEachIndexed { index, (path, _) ->
                            val checked = selected[path] == true
                            ListItem(
                                colors = rowColors,
                                modifier = Modifier.toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { haptics.toggle(it); selected[path] = it }
                                ),
                                leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                headlineContent = { Text(path.substringAfterLast('/')) },
                                supportingContent = { Text(library.displayPath(path)) },
                                trailingContent = { Checkbox(checked = checked, onCheckedChange = null) }
                            )
                            if (index < suggestions.lastIndex) {
                                HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.smart_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}
