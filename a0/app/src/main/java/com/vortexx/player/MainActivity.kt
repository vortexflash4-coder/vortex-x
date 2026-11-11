package com.vortexx.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.vortexx.player.data.library.ArtworkLoader
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.ui.app.VortexAppRoot
import com.vortexx.player.ui.designsystem.components.LocalArtworkLoader
import com.vortexx.player.ui.designsystem.theme.VortexTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playbackConnection: PlaybackConnection
    @Inject lateinit var artworkLoader: ArtworkLoader

    /** Tapping the media notification asks the UI to open Now Playing. */
    private val openNowPlaying = Channel<Unit>(Channel.CONFLATED)
    private val openNowPlayingRequests = openNowPlaying.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            VortexTheme {
                CompositionLocalProvider(LocalArtworkLoader provides artworkLoader) {
                    VortexAppRoot(openNowPlayingRequests = openNowPlayingRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        playbackConnection.connect()
    }

    override fun onStop() {
        // Playback continues in PlaybackService; only the UI's controller is released.
        playbackConnection.release()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) {
            openNowPlaying.trySend(Unit)
            intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.vortexx.player.OPEN_NOW_PLAYING"
    }
}
