package com.vortexx.player.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.vortexx.player.domain.model.Video
import com.vortexx.player.feature.controlcenter.ControlCenterScreen
import com.vortexx.player.feature.diagnostics.DiagnosticsScreen
import com.vortexx.player.feature.explorer.ExplorerFolderScreen
import com.vortexx.player.feature.explorer.ExplorerScreen
import com.vortexx.player.feature.home.HomeScreen
import com.vortexx.player.feature.music.CollectionScreen
import com.vortexx.player.feature.music.MusicLibraryScreen
import com.vortexx.player.feature.nowplaying.NowPlayingScreen
import com.vortexx.player.feature.playlists.PlaylistDetailScreen
import com.vortexx.player.feature.playlists.PlaylistsScreen
import com.vortexx.player.feature.queue.QueueScreen
import com.vortexx.player.feature.settings.SettingsScreen
import com.vortexx.player.feature.studio.StudioScreen
import com.vortexx.player.feature.video.VideoLibraryScreen
import com.vortexx.player.feature.video.VideoPlayerScreen
import com.vortexx.player.ui.designsystem.components.AdaptiveWidth
import com.vortexx.player.ui.designsystem.components.VortexWidths

@Composable
fun VortexNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val back: () -> Unit = { navController.popBackStack() }
    val playVideo: (Video) -> Unit = { video ->
        navController.navigate(VideoPlayerRoute(videoId = video.id, uri = video.contentUri, title = video.title))
    }
    val openNowPlaying: () -> Unit = { navController.navigate(NowPlayingRoute) { launchSingleTop = true } }

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable<HomeRoute> {
            AdaptiveWidth(VortexWidths.Wide) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
                    onPlayVideo = playVideo,
                    onOpenNowPlaying = openNowPlaying,
                )
            }
        }
        composable<MusicRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                MusicLibraryScreen(
                    onOpenCollection = { type, key, title -> navController.navigate(CollectionRoute(type.name, key, title)) },
                )
            }
        }
        composable<CollectionRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                CollectionScreen(onBack = back)
            }
        }
        composable<NowPlayingRoute> {
            NowPlayingScreen(
                onBack = back,
                onOpenQueue = { navController.navigate(QueueRoute) { launchSingleTop = true } },
                onOpenControlCenter = { navController.navigate(ControlCenterRoute) { launchSingleTop = true } },
                onBrowseMusic = {
                    navController.popBackStack()
                    navController.navigate(MusicRoute) { launchSingleTop = true }
                },
            )
        }
        composable<QueueRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                QueueScreen(onBack = back)
            }
        }
        composable<ControlCenterRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                ControlCenterScreen(onBack = back)
            }
        }
        composable<VideosRoute> {
            AdaptiveWidth(VortexWidths.Wide) {
                VideoLibraryScreen(onPlayVideo = playVideo)
            }
        }
        composable<VideoPlayerRoute> {
            VideoPlayerScreen(onBack = back)
        }
        composable<PlaylistsRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                PlaylistsScreen(onOpenPlaylist = { id -> navController.navigate(PlaylistDetailRoute(id)) })
            }
        }
        composable<PlaylistDetailRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                PlaylistDetailScreen(onBack = back)
            }
        }
        composable<ExplorerRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                ExplorerScreen(
                    onOpenFolder = { path -> navController.navigate(ExplorerFolderRoute(path)) },
                    onOpenNowPlaying = openNowPlaying,
                    onPlayExternalVideo = { uri, title -> navController.navigate(VideoPlayerRoute(videoId = -1L, uri = uri, title = title)) },
                )
            }
        }
        composable<ExplorerFolderRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                ExplorerFolderScreen(onBack = back, onPlayVideo = playVideo)
            }
        }
        composable<StudioRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                StudioScreen(onBack = back, onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) { launchSingleTop = true } })
            }
        }
        composable<DiagnosticsRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                DiagnosticsScreen(onBack = back)
            }
        }
        composable<SettingsRoute> {
            AdaptiveWidth(VortexWidths.Reading) {
                SettingsScreen(
                    onBack = back,
                    onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) { launchSingleTop = true } },
                )
            }
        }
    }
}
