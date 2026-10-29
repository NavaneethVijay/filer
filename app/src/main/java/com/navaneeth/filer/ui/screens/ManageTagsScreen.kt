package com.navaneeth.filer.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.automirrored.rounded.LabelOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.navaneeth.filer.R
import com.navaneeth.filer.ui.components.BoldLargeTopBar
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.GroupDivider
import com.navaneeth.filer.ui.components.GroupTitle
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.SettingRow
import com.navaneeth.filer.ui.components.SettingsGroup
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.AiViewModel

/** Every tag with its file count: open, rename (merging into an existing tag) or delete. */
@Composable
fun ManageTagsScreen(ai: AiViewModel, onOpenTag: (String) -> Unit, onBack: () -> Unit) {
    val haptics = rememberHaptics()
    val tags by ai.tagCounts.collectAsState()
    val suggested by ai.suggestedTagCount.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var clearSuggested by remember { mutableStateOf(false) }
    val barSpace = LocalBottomBarSpace.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { BoldLargeTopBar(title = stringResource(R.string.section_tags), onBack = onBack, scrollBehavior = scrollBehavior) }
    ) { padding ->
        if (tags.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.LabelOff,
                title = stringResource(R.string.manage_tags_empty_title),
                body = stringResource(R.string.manage_tags_empty_body),
                modifier = Modifier.padding(top = padding.calculateTopPadding(), bottom = barSpace)
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = barSpace + 32.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.manage_tags_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            item { GroupTitle(pluralStringResource(R.plurals.manage_tags_count, tags.size, tags.size)) }
            item {
                SettingsGroup {
                    tags.forEachIndexed { index, tag -> key(tag.tag) {
                        if (index > 0) GroupDivider()
                        var menu by remember { mutableStateOf(false) }
                        SettingRow(
                            icon = Icons.AutoMirrored.Rounded.Label,
                            title = "#${tag.tag}",
                            subtitle = pluralStringResource(R.plurals.tag_files, tag.count, tag.count),
                            onClick = { haptics.tick(); onOpenTag(tag.tag) },
                            trailing = {
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_tag_options, tag.tag))
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_rename)) },
                                            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
                                            onClick = { menu = false; renaming = tag.tag }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.manage_tags_delete)) },
                                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                                            onClick = { menu = false; deleting = tag.tag }
                                        )
                                    }
                                }
                            }
                        )
                    } }
                }
            }
            if (suggested > 0) {
                item { GroupTitle(stringResource(R.string.manage_tags_suggested_title)) }
                item {
                    SettingsGroup {
                        SettingRow(
                            icon = Icons.Rounded.AutoAwesome,
                            title = stringResource(R.string.manage_tags_remove_suggested),
                            subtitle = pluralStringResource(R.plurals.manage_tags_suggested_count, suggested, suggested),
                            destructive = true,
                            onClick = { clearSuggested = true }
                        )
                    }
                }
            }
        }
    }

    renaming?.let { tag ->
        var text by rememberSaveable(tag) { mutableStateOf(tag) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.manage_tags_rename_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    prefix = { Text("#") },
                    supportingText = { Text(stringResource(R.string.manage_tags_rename_body)) }
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { haptics.confirm(); ai.renameTag(tag, text); renaming = null }) {
                    Text(stringResource(R.string.action_rename))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
    deleting?.let { tag ->
        ConfirmDialog(
            title = stringResource(R.string.manage_tags_delete_title, tag),
            body = stringResource(R.string.manage_tags_delete_body),
            confirm = stringResource(R.string.manage_tags_delete),
            onConfirm = { haptics.confirm(); ai.deleteTag(tag); deleting = null },
            onDismiss = { deleting = null }
        )
    }
    if (clearSuggested) {
        ConfirmDialog(
            title = stringResource(R.string.manage_tags_remove_suggested_title),
            body = stringResource(R.string.manage_tags_remove_suggested_body),
            confirm = stringResource(R.string.plugin_remove),
            onConfirm = { haptics.confirm(); ai.removeSuggestedTags(); clearSuggested = false },
            onDismiss = { clearSuggested = false }
        )
    }
}
