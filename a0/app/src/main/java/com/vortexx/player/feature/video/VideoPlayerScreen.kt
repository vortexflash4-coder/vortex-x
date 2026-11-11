package com.vortexx.player.feature.video

import android.content.pm.ActivityInfo
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ScreenLockLandscape
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vortexx.player.core.permissions.findActivity
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.settings.VideoScaling
import com.vortexx.player.ui.designsystem.components.PrimaryButton
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import kotlinx.coroutines.delay

/**
 * Full-screen video. Media3's PlayerView supplies the transport controls plus its
 * settings menu (playback speed, audio track and subtitle track selection);
 * VORTEX X adds scaling, rotation lock, resume and error explanations on top.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    onBack: () -> Unit,
    viewModel: VideoPlayerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val hostView = LocalView.current
    var controlsVisible by remember { mutableStateOf(true) }

    // Immersive mode while the player is on screen.
    DisposableEffect(activity) {
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, hostView) }
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(ui.orientation, activity) {
        activity?.requestedOrientation = when (ui.orientation) {
            OrientationLock.Auto -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationLock.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationLock.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // Video does not continue in the background (picture-in-picture is planned for Phase 2).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

    LaunchedEffect(ui.resumedFromMs) {
        if (ui.resumedFromMs != null) {
            delay(6_000L)
            viewModel.dismissResumeHint()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = 3_500
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                }
            },
            update = { view ->
                if (view.player !== viewModel.player) view.player = viewModel.player
                view.resizeMode = ui.scaling.toResizeMode()
                view.keepScreenOn = ui.keepScreenOn && ui.isPlaying
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = controlsVisible || ui.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopOverlay(
                title = ui.title,
                scaling = ui.scaling,
                orientation = ui.orientation,
                trackSummary = trackSummary(ui.audioTrackCount, ui.subtitleTrackCount),
                onBack = onBack,
                onCycleScaling = viewModel::cycleScaling,
                onCycleOrientation = viewModel::cycleOrientation,
            )
        }

        val resumedAt = ui.resumedFromMs
        if (resumedAt != null && ui.error == null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(start = VortexSpacing.lg, end = VortexSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Resumed at ${Format.duration(resumedAt)}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                TextButton(onClick = viewModel::startOver) { Text("Start over", color = VortexColors.AccentActive) }
            }
        }

        val error = ui.error
        if (error != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 440.dp)
                    .padding(VortexSpacing.xl)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VortexColors.Surface)
                    .padding(VortexSpacing.xl),
            ) {
                Text(error.title, style = MaterialTheme.typography.titleLarge, color = VortexColors.TextPrimary)
                Spacer(Modifier.height(VortexSpacing.sm))
                Text(error.detail, style = MaterialTheme.typography.bodyMedium, color = VortexColors.TextSecondary)
                Spacer(Modifier.height(VortexSpacing.sm))
                Text("Code: ${error.codeName}", style = MaterialTheme.typography.labelMedium, color = VortexColors.TextSecondary)
                Spacer(Modifier.height(VortexSpacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm)) {
                    if (error.canRetry) PrimaryButton(text = "Retry", onClick = viewModel::retry)
                    SecondaryButton(text = "Go back", onClick = onBack)
                }
            }
        }
    }
}

@Composable
private fun TopOverlay(
    title: String,
    scaling: VideoScaling,
    orientation: OrientationLock,
    trackSummary: String?,
    onBack: () -> Unit,
    onCycleScaling: () -> Unit,
    onCycleOrientation: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = VortexSpacing.xs, vertical = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trackSummary != null) {
                Text(trackSummary, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
        TextButton(onClick = onCycleScaling) {
            Icon(Icons.Rounded.AspectRatio, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(VortexSpacing.xs))
            Text(scaling.label, color = Color.White)
        }
        IconButton(onClick = onCycleOrientation) {
            Icon(
                when (orientation) {
                    OrientationLock.Auto -> Icons.Rounded.ScreenRotation
                    OrientationLock.Landscape -> Icons.Rounded.ScreenLockLandscape
                    OrientationLock.Portrait -> Icons.Rounded.ScreenLockPortrait
                },
                contentDescription = "Rotation: ${orientation.label}. Tap to change.",
                tint = if (orientation == OrientationLock.Auto) Color.White else VortexColors.AccentActive,
            )
        }
    }
}

private fun trackSummary(audio: Int, subtitles: Int): String? {
    if (audio <= 1 && subtitles == 0) return null
    val parts = buildList {
        if (audio > 1) add("$audio audio tracks")
        if (subtitles > 0) add(if (subtitles == 1) "1 subtitle track" else "$subtitles subtitle tracks")
    }
    return parts.joinToString(" • ") + " — choose in ⚙"
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoScaling.toResizeMode(): Int = when (this) {
    VideoScaling.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScaling.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    VideoScaling.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}
