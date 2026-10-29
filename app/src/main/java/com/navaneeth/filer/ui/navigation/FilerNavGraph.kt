package com.navaneeth.filer.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.navaneeth.filer.data.analysis.CleanupKind
import com.navaneeth.filer.data.media.MediaCategory
import com.navaneeth.filer.data.storage.Storage
import com.navaneeth.filer.data.model.IndexStatus
import com.navaneeth.filer.data.preferences.PreferencesManager
import com.navaneeth.filer.ui.screens.AiPluginsScreen
import com.navaneeth.filer.ui.screens.TagFilesScreen
import com.navaneeth.filer.ui.screens.ManageTagsScreen
import com.navaneeth.filer.ui.util.LocalOpenDetails
import com.navaneeth.filer.ui.util.LocalOpenViewer
import com.navaneeth.filer.ui.viewer.FileViewerScreen
import com.navaneeth.filer.ui.viewmodel.AiViewModel
import com.navaneeth.filer.ui.model.FileUiItem
import com.navaneeth.filer.ui.model.pathOrNull
import com.navaneeth.filer.ui.components.BottomTab
import com.navaneeth.filer.ui.components.BottomBarHeight
import com.navaneeth.filer.ui.components.FilerBottomBar
import com.navaneeth.filer.ui.components.LocalBottomBarSpace
import com.navaneeth.filer.ui.components.SearchOverlay
import com.navaneeth.filer.ui.components.FileOpsHost
import com.navaneeth.filer.ui.components.TransferBar
import com.navaneeth.filer.ui.components.TransferBarHeight
import com.navaneeth.filer.ui.screens.FavoritesScreen
import com.navaneeth.filer.ui.screens.LockedFolderScreen
import com.navaneeth.filer.ui.screens.RecycleBinScreen
import com.navaneeth.filer.ui.viewer.LocalPhotoViewer
import com.navaneeth.filer.ui.viewer.PhotoViewer
import com.navaneeth.filer.ui.viewer.PhotoViewerState
import com.navaneeth.filer.ui.viewmodel.FileOpsViewModel
import com.navaneeth.filer.ui.viewmodel.LocalFileOps
import com.navaneeth.filer.data.model.DownloadState
import com.navaneeth.filer.ui.screens.BrowseScreen
import com.navaneeth.filer.ui.screens.AllFilesAccessScreen
import com.navaneeth.filer.ui.screens.SmartFoldersSetupScreen
import com.navaneeth.filer.ui.screens.CollectionScreen
import com.navaneeth.filer.ui.screens.FileDetailsScreen
import com.navaneeth.filer.ui.screens.FolderScreen
import com.navaneeth.filer.ui.screens.IndexStatusScreen
import com.navaneeth.filer.ui.screens.IndexedFilesScreen
import com.navaneeth.filer.ui.screens.ModelScreen
import com.navaneeth.filer.ui.screens.PrivacyAboutScreen
import com.navaneeth.filer.ui.screens.RecentScreen
import com.navaneeth.filer.ui.screens.SettingsScreen
import com.navaneeth.filer.ui.screens.WelcomeScreen
import com.navaneeth.filer.ui.screens.storage.CleanupReviewScreen
import com.navaneeth.filer.ui.screens.storage.DuplicatesScreen
import com.navaneeth.filer.ui.screens.storage.InsightsScreen
import com.navaneeth.filer.ui.screens.storage.SpaceMapScreen
import com.navaneeth.filer.ui.screens.storage.StorageScreen
import com.navaneeth.filer.ui.viewmodel.StorageViewModel
import com.navaneeth.filer.ui.util.LocalSnackbarHostState
import com.navaneeth.filer.ui.viewmodel.IndexViewModel
import com.navaneeth.filer.ui.viewmodel.LibraryViewModel
import com.navaneeth.filer.ui.viewmodel.SearchViewModel
import com.navaneeth.filer.ui.viewmodel.SettingsViewModel

@Composable
fun FilerNavGraph(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    val startDestination: Screen = remember {
        when {
            !prefs.isOnboardingCompleted -> Screen.Welcome
            !Storage.hasAllFilesAccess() -> Screen.AllFilesAccess
            else -> Screen.Browse
        }
    }

    val library: LibraryViewModel = viewModel()
    val search: SearchViewModel = viewModel()
    val indexViewModel: IndexViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val fileOps: FileOpsViewModel = viewModel()
    val storage: StorageViewModel = viewModel()
    val ai: AiViewModel = viewModel()
    val photoViewer = remember { PhotoViewerState() }

    val snackbarHostState = remember { SnackbarHostState() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val downloadState by library.downloadState.collectAsState()
    val showAdvanced by library.showAdvanced.collectAsState()

    val navTarget by com.navaneeth.filer.MainActivity.navTargetFlow.collectAsState()
    LaunchedEffect(navTarget) {
        if (navTarget == com.navaneeth.filer.MainActivity.DEST_INDEX_STATUS) {
            com.navaneeth.filer.MainActivity.navTargetFlow.value = null
            navController.navigate(Screen.IndexStatus)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val isBrowseChild = destination != null &&
        (destination.hasRoute<Screen.Folder>() || destination.hasRoute<Screen.Collection>() ||
            destination.hasRoute<Screen.Favorites>() || destination.hasRoute<Screen.RecycleBin>() ||
            destination.hasRoute<Screen.LockedFolder>() || destination.hasRoute<Screen.Recent>() || destination.hasRoute<Screen.RecentDay>() ||
            destination.hasRoute<Screen.TagFiles>() || destination.hasRoute<Screen.ManageTags>() ||
            destination.hasRoute<Screen.Settings>())
    val isStorageChild = destination != null &&
        (destination.hasRoute<Screen.StorageReview>() ||
            destination.hasRoute<Screen.StorageDuplicates>() || destination.hasRoute<Screen.SpaceMap>())
    // A child screen belongs to the tab it was opened from: the Recycle bin or a folder opened from
    // Storage stays under Storage, so Back returns there and the bar shows the right tab.
    val backStack by navController.currentBackStack.collectAsState()
    val currentTab = destination?.tab() ?: if (isBrowseChild || isStorageChild) {
        backStack.lastOrNull { it.destination.tab() != null }?.destination?.tab() ?: BottomTab.BROWSE
    } else {
        null
    }
    val showBottomBar = !searchOpen && currentTab != null

    fun selectTab(tab: BottomTab) {
        // Re-selecting the current tab from one of its child screens returns to the tab's top.
        if (tab == currentTab && destination?.tab() == null) {
            when (tab) {
                BottomTab.BROWSE -> navController.popBackStack<Screen.Browse>(inclusive = false)
                BottomTab.STORAGE -> navController.popBackStack<Screen.Storage>(inclusive = false)
                BottomTab.INSIGHTS -> navController.popBackStack<Screen.StorageInsights>(inclusive = false)
            }
            return
        }
        val target = when (tab) {
            BottomTab.BROWSE -> Screen.Browse
            BottomTab.STORAGE -> Screen.Storage
            BottomTab.INSIGHTS -> Screen.StorageInsights
        }
        // A tab always opens on its main screen; where you were inside another tab isn't restored.
        navController.navigate(target) {
            popUpTo<Screen.Browse>()
            launchSingleTop = true
        }
    }

    val showIndexDetails: (Long) -> Unit = { navController.navigate(Screen.FileDetails(fileId = it)) }

    fun openFolder(path: String) = navController.navigate(Screen.Folder(path))

    /** Duplicates have their own screen; every other kind (or null, everything) uses the review list. */
    fun openCleanup(kind: CleanupKind?) = navController.navigate(
        if (kind == CleanupKind.DUPLICATES) Screen.StorageDuplicates else Screen.StorageReview(kind?.name)
    )

    // Access can be revoked in system Settings at any time; send the user back to the grant screen.
    val hasAccess by library.hasAccess.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { library.refreshAccess() }
    LaunchedEffect(hasAccess) {
        if (!hasAccess && prefs.isOnboardingCompleted && destination?.hasRoute<Screen.AllFilesAccess>() != true) {
            navController.navigate(Screen.AllFilesAccess) { popUpTo(0) { inclusive = true } }
        }
    }

    // Move/Copy: the user picks the destination by browsing. Start them in the files' own folder.
    val transfer by fileOps.transfer.collectAsState()
    val currentFolder = if (destination?.hasRoute<Screen.Folder>() == true) backStackEntry?.toRoute<Screen.Folder>()?.path else null
    LaunchedEffect(transfer) {
        val parent = transfer?.paths?.firstOrNull()?.let { java.io.File(it).parent } ?: return@LaunchedEffect
        photoViewer.close()
        if (searchOpen) {
            searchOpen = false
            search.clear()
        }
        if (currentFolder != parent) {
            if (!isBrowseChild) selectTab(BottomTab.BROWSE)
            openFolder(parent)
        }
    }
    val busy by fileOps.busy.collectAsState()

    // Screens under the bar reserve this much at the bottom. It depends only on the destination, so
    // opening search (which hides the bar) never re-lays out the screen behind it.
    val barSpace = if (currentTab != null) BottomBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp

    CompositionLocalProvider(
        LocalSnackbarHostState provides snackbarHostState,
        LocalBottomBarSpace provides barSpace,
        LocalFileOps provides fileOps,
        LocalPhotoViewer provides photoViewer,
        LocalOpenViewer provides { item: FileUiItem, plugins: Boolean ->
            item.pathOrNull()?.let { navController.navigate(Screen.Viewer(it, plugins)) }
        },
        LocalOpenDetails provides { item: FileUiItem ->
            photoViewer.close()
            val uri = item.pathOrNull()?.let { android.net.Uri.fromFile(java.io.File(it)).toString() } ?: item.uri
            navController.navigate(Screen.FileDetails(fileId = item.fileId ?: -1, uri = uri))
        }
    ) {
        FileOpsHost(fileOps)
        // Root Surface sets the content color, so text is legible in both light and dark theme.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (initialState.isTopLevel() && targetState.isTopLevel()) Motion.fadeThroughIn() else Motion.sharedAxisXIn(forward = true)
                },
                exitTransition = {
                    if (initialState.isTopLevel() && targetState.isTopLevel()) Motion.fadeThroughOut() else Motion.sharedAxisXOut(forward = true)
                },
                popEnterTransition = {
                    if (initialState.isTopLevel() && targetState.isTopLevel()) Motion.fadeThroughIn() else Motion.sharedAxisXIn(forward = false)
                },
                popExitTransition = {
                    if (initialState.isTopLevel() && targetState.isTopLevel()) Motion.fadeThroughOut() else Motion.sharedAxisXOut(forward = false)
                }
            ) {
                // First launch
                composable<Screen.Welcome> {
                    WelcomeScreen(
                        onGetStarted = { navController.navigate(Screen.AllFilesAccess) },
                        onLearnMore = { navController.navigate(Screen.PrivacyAbout) }
                    )
                }
                composable<Screen.AllFilesAccess> {
                    AllFilesAccessScreen(
                        library = library,
                        onGranted = {
                            val next = if (prefs.isOnboardingCompleted) Screen.Browse else Screen.SmartFoldersSetup
                            navController.navigate(next) { popUpTo(0) { inclusive = true } }
                        }
                    )
                }
                composable<Screen.SmartFoldersSetup> {
                    SmartFoldersSetupScreen(
                        library = library,
                        onDone = { navController.navigate(Screen.Browse) { popUpTo(0) { inclusive = true } } }
                    )
                }

                // Top-level tabs
                composable<Screen.Browse> {
                    BrowseScreen(
                        library = library,
                        ai = ai,
                        storage = storage,
                        onOpenTag = { tag -> navController.navigate(Screen.TagFiles(tag)) },
                        onOpenSettings = { navController.navigate(Screen.Settings) },
                        onManageTags = { navController.navigate(Screen.ManageTags) },
                        onOpenSearch = { searchOpen = true },
                        // An example search: the overlay opens with the text in its field, results running.
                        onSearchFor = { search.onQueryChange(it); searchOpen = true },
                        onOpenFolder = ::openFolder,
                        onOpenCollection = { navController.navigate(Screen.Collection(it.name)) },
                        onOpenFavorites = { navController.navigate(Screen.Favorites) },
                        onOpenRecycleBin = { navController.navigate(Screen.RecycleBin) },
                        onOpenLockedFolder = { navController.navigate(Screen.LockedFolder) },
                        onOpenReview = ::openCleanup,
                        onSeeAllRecent = { navController.navigate(Screen.Recent) },
                        onOpenDay = { navController.navigate(Screen.RecentDay(it)) }
                    )
                }
                composable<Screen.Storage> {
                    StorageScreen(
                        storage = storage,
                        onOpenReview = ::openCleanup,
                        onOpenInsights = { selectTab(BottomTab.INSIGHTS) },
                        onOpenSpaceMap = { navController.navigate(Screen.SpaceMap(it)) },
                        onOpenRecycleBin = { navController.navigate(Screen.RecycleBin) }
                    )
                }
                composable<Screen.Settings> {
                    SettingsScreen(
                        library = library,
                        settings = settingsViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenPrivacy = { navController.navigate(Screen.PrivacyAbout) },
                        onOpenAiPlugins = { navController.navigate(Screen.AiPlugins) },
                        onOpenIndexStatus = { navController.navigate(Screen.IndexStatus) },
                        onOpenIndexedFiles = { navController.navigate(Screen.IndexedFiles()) },
                        onOpenModel = { navController.navigate(Screen.ModelSettings) },
                        onBrowseStorage = {
                            Storage.volumes(context).firstOrNull()?.let { volume ->
                                selectTab(BottomTab.BROWSE)
                                openFolder(volume.root.path)
                            }
                        }
                    )
                }

                // Browsing
                composable<Screen.Folder> {
                    FolderScreen(
                        library = library,
                        onBack = { navController.popBackStack() },
                        onOpenFolder = ::openFolder,
                        // Breadcrumb: jump back if that folder is on the stack, otherwise open it.
                        onNavigateTo = { path ->
                            if (!navController.popBackStack(Screen.Folder(path), inclusive = false)) openFolder(path)
                        }
                    )
                }
                composable<Screen.Collection> {
                    val kind = runCatching { MediaCategory.valueOf(it.toRoute<Screen.Collection>().kind) }
                        .getOrDefault(MediaCategory.DOCUMENTS)
                    CollectionScreen(
                        kind = kind,
                        library = library,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable<Screen.Favorites> {
                    FavoritesScreen(onBack = { navController.popBackStack() }, onOpenFolder = ::openFolder)
                }
                composable<Screen.RecycleBin> {
                    RecycleBinScreen(onBack = { navController.popBackStack() })
                }
                composable<Screen.LockedFolder> {
                    LockedFolderScreen(onBack = { navController.popBackStack() })
                }
                composable<Screen.Recent> {
                    RecentScreen(
                        library = library,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable<Screen.RecentDay> {
                    RecentScreen(
                        library = library,
                        onBack = { navController.popBackStack() },
                        day = it.toRoute<Screen.RecentDay>().dayStart
                    )
                }

                // Storage
                composable<Screen.StorageInsights> {
                    InsightsScreen(storage = storage, onOpenReview = ::openCleanup)
                }
                composable<Screen.StorageReview> {
                    val kind = it.toRoute<Screen.StorageReview>().kind?.let { name -> runCatching { CleanupKind.valueOf(name) }.getOrNull() }
                    CleanupReviewScreen(storage = storage, kind = kind, onBack = { navController.popBackStack() })
                }
                composable<Screen.StorageDuplicates> {
                    DuplicatesScreen(storage = storage, onBack = { navController.popBackStack() })
                }
                composable<Screen.SpaceMap> {
                    SpaceMapScreen(
                        storage = storage,
                        startPath = it.toRoute<Screen.SpaceMap>().path,
                        onBack = { navController.popBackStack() },
                        // Opened on top of the map (not in the Browse tab), so Back comes back here.
                        onOpenInBrowse = ::openFolder
                    )
                }

                // Indexing details (Settings › Show indexing details)
                composable<Screen.IndexStatus> {
                    IndexStatusScreen(
                        viewModel = indexViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenFiles = { status -> navController.navigate(Screen.IndexedFiles(status?.name)) }
                    )
                }
                composable<Screen.IndexedFiles> {
                    val status = it.toRoute<Screen.IndexedFiles>().status
                    IndexedFilesScreen(
                        viewModel = indexViewModel,
                        initialFilter = status?.let { name -> runCatching { IndexStatus.valueOf(name) }.getOrNull() },
                        onBack = { navController.popBackStack() },
                        onOpenDetails = showIndexDetails
                    )
                }
                composable<Screen.ModelSettings> {
                    ModelScreen(library = library, settings = settingsViewModel, onBack = { navController.popBackStack() })
                }
                composable<Screen.FileDetails> {
                    val route = it.toRoute<Screen.FileDetails>()
                    val match = remember(route) { if (route.fromSearch) route.uri?.let(search::hitFor) else null }
                    FileDetailsScreen(
                        ai = ai,
                        fileId = route.fileId.takeIf { id -> id >= 0 },
                        uri = route.uri,
                        match = match,
                        query = if (route.fromSearch) search.query.value else null,
                        showAdvanced = showAdvanced,
                        viewModel = indexViewModel,
                        onBack = { navController.popBackStack() },
                        onShowInFolder = { path ->
                            searchOpen = false
                            search.clear()
                            if (!isBrowseChild) selectTab(BottomTab.BROWSE)
                            openFolder(path)
                        }
                    )
                }
                composable<Screen.AiPlugins> {
                    AiPluginsScreen(ai = ai, onOpenManageTags = { navController.navigate(Screen.ManageTags) }, onBack = { navController.popBackStack() })
                }
                composable<Screen.ManageTags> {
                    ManageTagsScreen(ai = ai, onOpenTag = { tag -> navController.navigate(Screen.TagFiles(tag)) }, onBack = { navController.popBackStack() })
                }
                composable<Screen.TagFiles> {
                    TagFilesScreen(tag = it.toRoute<Screen.TagFiles>().tag, ai = ai, onBack = { navController.popBackStack() })
                }
                composable<Screen.Viewer> {
                    val route = it.toRoute<Screen.Viewer>()
                    FileViewerScreen(path = route.path, plugins = route.plugins, ai = ai, onBack = { navController.popBackStack() })
                }
                composable<Screen.PrivacyAbout> {
                    PrivacyAboutScreen(
                        onBack = { navController.popBackStack() },
                        onUnlockDeveloper = settingsViewModel::unlockDeveloperMode
                    )
                }
            }

            // Slides instead of resizing, so the content behind it never re-measures.
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(300, easing = Motion.EmphasizedDecelerate)) { it } + fadeIn(tween(150)),
                exit = slideOutVertically(tween(250, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                // Search isn't a destination: it opens over whatever screen is showing.
                FilerBottomBar(currentTab = currentTab, onTabSelected = ::selectTab, onSearch = { searchOpen = true })
            }
            // A result's details open on top of search; back returns to the same results.
            SearchOverlay(
                visible = searchOpen && destination?.hasRoute<Screen.FileDetails>() != true && destination?.hasRoute<Screen.Viewer>() != true,
                viewModel = search,
                smartSearchReady = downloadState is DownloadState.Ready,
                onClose = { searchOpen = false },
                onOpenResult = { hit ->
                    navController.navigate(Screen.FileDetails(fileId = hit.item.fileId ?: -1, uri = hit.item.uri, fromSearch = true))
                }
            )
            // Over everything, search included; the snackbar (Undo) stays on top of it.
            PhotoViewer(photoViewer, ai)
            // Waiting files for Move/Copy: sits above the bottom bar until pasted or cancelled.
            AnimatedVisibility(
                visible = transfer != null && !searchOpen,
                enter = slideInVertically(tween(300, easing = Motion.EmphasizedDecelerate)) { it } + fadeIn(tween(150)),
                exit = slideOutVertically(tween(250, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut(tween(150)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = barSpace)
                    .then(if (barSpace == 0.dp) Modifier.navigationBarsPadding() else Modifier)
            ) {
                transfer?.let { pending ->
                    TransferBar(
                        transfer = pending,
                        folder = currentFolder,
                        onPaste = fileOps::pasteInto,
                        onCancel = fileOps::cancelTransfer
                    )
                }
            }
            if (busy) {
                LinearProgressIndicator(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = barSpace + if (transfer != null) TransferBarHeight else 0.dp)
                    .navigationBarsPadding()
            )
        }
        }
    }
}

private fun NavDestination.tab(): BottomTab? = when {
    hasRoute<Screen.Browse>() -> BottomTab.BROWSE
    hasRoute<Screen.Storage>() -> BottomTab.STORAGE
    hasRoute<Screen.StorageInsights>() -> BottomTab.INSIGHTS
    else -> null
}

private fun NavBackStackEntry.isTopLevel(): Boolean = destination.tab() != null
