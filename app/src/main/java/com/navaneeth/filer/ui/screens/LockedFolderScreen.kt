package com.navaneeth.filer.ui.screens

import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.navaneeth.filer.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import com.navaneeth.filer.ui.components.Accent
import com.navaneeth.filer.ui.components.ExpressiveShapes
import com.navaneeth.filer.ui.components.ShapedIcon
import com.navaneeth.filer.ui.components.colors
import com.navaneeth.filer.ui.components.BoldLargeTopBar
import com.navaneeth.filer.ui.components.LoadingState
import com.navaneeth.filer.ui.components.EmptyState
import com.navaneeth.filer.ui.components.ListGroup
import com.navaneeth.filer.ui.components.StoredItemList
import com.navaneeth.filer.ui.components.StoredItemSheet
import com.navaneeth.filer.ui.components.StoredRow
import com.navaneeth.filer.ui.components.StoredSelectionBar
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.rememberSelection
import com.navaneeth.filer.ui.components.storedItem
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.util.DeviceAuth
import com.navaneeth.filer.ui.util.findActivity
import com.navaneeth.filer.ui.util.formatModified
import com.navaneeth.filer.ui.util.formatSize
import com.navaneeth.filer.ui.util.rememberFileOpener
import com.navaneeth.filer.ui.util.rememberHaptics
import com.navaneeth.filer.ui.viewmodel.LocalFileOps

/**
 * Files moved into Filer's private storage. Opening the folder needs the phone's screen lock
 * (fingerprint, PIN…); it locks again whenever Filer goes to the background, and its contents
 * never appear in screenshots or the recent-apps preview.
 */
@Composable
fun LockedFolderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var unlocked by remember { mutableStateOf(false) }
    var prompting by remember { mutableStateOf(false) }
    // Ask automatically on entry and after Filer was in the background, but not again after a cancel.
    var autoPrompt by remember { mutableStateOf(true) }
    val secure = remember { DeviceAuth.isAvailable(context) }
    val authTitle = stringResource(R.string.locked_auth_title)
    val authSubtitle = stringResource(R.string.locked_auth_subtitle)

    fun authenticate() {
        if (prompting || !DeviceAuth.isAvailable(context)) return
        prompting = true
        DeviceAuth.authenticate(context, authTitle, authSubtitle) { ok ->
            prompting = false
            unlocked = ok
            if (ok) haptics.confirm()
        }
    }

    // The PIN screen itself stops this activity; only a real trip to the background re-locks.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!prompting) {
            unlocked = false
            autoPrompt = true
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!unlocked && autoPrompt) {
            autoPrompt = false
            authenticate()
        }
    }

    // No screenshots or recent-apps thumbnail while this screen is showing.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    if (unlocked) {
        LockedContent(onBack = onBack)
    } else {
        Scaffold(
            topBar = {
                BoldLargeTopBar(
                    title = stringResource(R.string.locked_title),
                    onBack = onBack,
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
                )
            }
        ) { padding ->
            // Nothing scrolls here: keep the prompt clear of the floating navigation bar.
            Column(Modifier.padding(top = padding.calculateTopPadding(), bottom = LocalBottomBarSpace.current)) {
                if (secure) {
                    EmptyState(
                        icon = Icons.Rounded.Lock,
                        title = stringResource(R.string.locked_closed_title),
                        body = stringResource(R.string.locked_closed_body),
                        actionLabel = stringResource(R.string.action_unlock),
                        onAction = ::authenticate
                    )
                } else {
                    EmptyState(
                        icon = Icons.Rounded.Lock,
                        title = stringResource(R.string.locked_no_screen_lock_title),
                        body = stringResource(R.string.locked_no_screen_lock_body),
                        actionLabel = stringResource(R.string.action_open_settings),
                        onAction = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LockedContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val fileOps = LocalFileOps.current
    val locked by fileOps.locked.collectAsState()
    val selection = rememberSelection()
    var sheetRow by remember { mutableStateOf<StoredRow?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Long>?>(null) }
    // A large bold title that collapses on scroll; selection mode keeps a small pinned bar.
    val largeBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val pinnedBar = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val scrollBehavior = if (selection.active) pinnedBar else largeBar
    val unlockLabel = stringResource(R.string.action_unlock_item)
    val barSpace = LocalBottomBarSpace.current

    val rows = remember(locked) {
        locked?.map { item ->
            StoredRow(
                id = item.id,
                item = storedItem(item.storedPath, item.name, item.mimeType, item.sizeBytes, item.lockedAt, item.isDirectory),
                supporting = listOf(formatSize(context, item.sizeBytes), formatModified(item.lockedAt)).joinToString(" · ")
            )
        }
    }

    val onOpen = rememberFileOpener(rows?.map { it.item }.orEmpty(), manageable = false)

    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(rows) { rows?.let { list -> selection.retain(list.map { it.item }) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.active) {
                StoredSelectionBar(
                    selection = selection,
                    rows = rows.orEmpty(),
                    primaryIcon = Icons.Rounded.LockOpen,
                    primaryLabel = unlockLabel,
                    onPrimary = fileOps::unlock,
                    onDeleteForever = { confirmDelete = it },
                    scrollBehavior = scrollBehavior
                )
            } else {
                BoldLargeTopBar(title = stringResource(R.string.locked_title), onBack = onBack, scrollBehavior = scrollBehavior)
            }
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            val list = rows
            when {
                list == null -> LoadingState(Modifier.padding(bottom = barSpace))
                list.isEmpty() -> Column(Modifier.padding(bottom = barSpace)) {
                    LockedWarning()
                    EmptyState(
                        icon = Icons.Rounded.Lock,
                        title = stringResource(R.string.locked_empty_title),
                        body = stringResource(R.string.locked_empty_body)
                    )
                }
                else -> StoredItemList(
                    rows = list,
                    selection = selection,
                    onOpen = { if (!it.item.isDirectory) onOpen(it.item) else sheetRow = it },
                    onMore = { sheetRow = it },
                    header = { item(key = "warning") { LockedWarning() } }
                )
            }
        }
    }

    sheetRow?.let { row ->
        StoredItemSheet(
            row = row,
            primaryIcon = Icons.Rounded.LockOpen,
            primaryLabel = unlockLabel,
            onOpen = if (row.item.isDirectory) null else ({ onOpen(row.item) }),
            onPrimary = { haptics.confirm(); fileOps.unlock(listOf(row.id)) },
            onDeleteForever = { confirmDelete = listOf(row.id) },
            onDismiss = { sheetRow = null }
        )
    }
    confirmDelete?.let { ids ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_locked_title, ids.size, ids.size),
            body = stringResource(R.string.delete_forever_body),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { haptics.confirm(); fileOps.deleteLocked(ids); selection.clear(); confirmDelete = null },
            onDismiss = { confirmDelete = null }
        )
    }
}

/** Uninstalling or clearing Filer's data deletes locked files; say so plainly (amber: a caution). */
@Composable
private fun LockedWarning() {
    val amber = Accent.AMBER.colors()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ShapedIcon(Icons.Rounded.Info, ExpressiveShapes.Flower, amber.container, amber.onContainer, size = 40.dp, iconSize = 20.dp)
            Text(stringResource(R.string.locked_warning), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
