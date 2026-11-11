package com.vortexx.player.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vortexx.player.core.permissions.MediaPermissions
import com.vortexx.player.core.permissions.rememberPermissionGroupState
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.playback.PlaybackUiState
import com.vortexx.player.playback.PositionSnapshot
import com.vortexx.player.ui.common.LocalSnackbarHostState
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.navigation.NowPlayingRoute
import com.vortexx.player.ui.navigation.VideoPlayerRoute
import com.vortexx.player.ui.navigation.VortexNavHost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlin.reflect.KClass

@HiltViewModel
class AppViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    private val library: MediaLibraryRepository,
) : ViewModel() {
    val playbackState: StateFlow<PlaybackUiState> = playback.state
    val position: Flow<PositionSnapshot> = playback.positionUpdates(intervalMs = 500L)

    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.next()
    fun refreshLibrary() = library.refresh()
}

@Composable
fun VortexAppRoot(
    openNowPlayingRequests: Flow<Unit>,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var onboardingDismissed by rememberSaveable { mutableStateOf(false) }

    val mediaAccess = rememberPermissionGroupState(
        permissions = MediaPermissions.all,
        isGranted = { it.hasAny },
        onResult = { granted -> if (granted) viewModel.refreshLibrary() },
    )

    if (!mediaAccess.granted && !onboardingDismissed) {
        WelcomeScreen(
            state = mediaAccess,
            onContinueWithoutAccess = { onboardingDismissed = true },
        )
        return
    }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val fullScreen = destination.isOn(NowPlayingRoute::class) || destination.isOn(VideoPlayerRoute::class)

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(VortexColors.Background),
        ) {
            val useRail = maxWidth >= 600.dp
            val isSelected: (TopLevelDestination) -> Boolean = { destination.isOn(it.routeClass) }
            val onSelect: (TopLevelDestination) -> Unit = { navController.navigateTopLevel(it.route) }

            // Landscape phones put the camera cutout at the side; keep chrome clear of it
            // (full-screen players draw into it on purpose).
            val cutoutPadding = if (fullScreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            Row(Modifier.fillMaxSize().then(cutoutPadding)) {
                if (useRail && !fullScreen) {
                    VortexNavRail(PrimaryDestinations + RailExtraDestinations, isSelected, onSelect)
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = VortexColors.Background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!fullScreen) {
                            Column {
                                if (playbackState.nowPlaying != null) {
                                    MiniPlayerHost(
                                        state = playbackState,
                                        position = viewModel.position,
                                        onOpen = { navController.navigate(NowPlayingRoute) { launchSingleTop = true } },
                                        onPlayPause = viewModel::togglePlayPause,
                                        onNext = viewModel::next,
                                    )
                                }
                                if (useRail) {
                                    Spacer(Modifier.navigationBarsPadding())
                                } else {
                                    VortexBottomBar(PrimaryDestinations, isSelected, onSelect)
                                }
                            }
                        }
                    },
                ) { padding ->
                    VortexNavHost(
                        navController = navController,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    LaunchedEffect(openNowPlayingRequests) {
        openNowPlayingRequests.collect {
            navController.navigate(NowPlayingRoute) { launchSingleTop = true }
        }
    }
}

@Composable
private fun MiniPlayerHost(
    state: PlaybackUiState,
    position: Flow<PositionSnapshot>,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val snapshot by position.collectAsStateWithLifecycle(initialValue = PositionSnapshot())
    MiniPlayer(
        state = state,
        position = snapshot,
        onOpen = onOpen,
        onPlayPause = onPlayPause,
        onNext = onNext,
    )
}

private fun NavDestination?.isOn(route: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(route) } == true

fun NavController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
