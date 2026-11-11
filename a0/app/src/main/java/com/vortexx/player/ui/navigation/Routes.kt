package com.vortexx.player.ui.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation destinations (Navigation Compose 2.8+).

@Serializable data object HomeRoute
@Serializable data object MusicRoute
@Serializable data object VideosRoute
@Serializable data object PlaylistsRoute
@Serializable data object ExplorerRoute
@Serializable data object SettingsRoute
@Serializable data object NowPlayingRoute
@Serializable data object QueueRoute
@Serializable data object ControlCenterRoute
@Serializable data object StudioRoute
@Serializable data object DiagnosticsRoute

/** Album / artist / genre / folder detail. [type] is a CollectionType name. */
@Serializable data class CollectionRoute(val type: String, val key: String, val title: String)

@Serializable data class PlaylistDetailRoute(val playlistId: Long)

@Serializable data class ExplorerFolderRoute(val path: String)

/** [videoId] is the MediaStore id, or -1 for a file opened through the system picker. */
@Serializable data class VideoPlayerRoute(val videoId: Long, val uri: String, val title: String)
