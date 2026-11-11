package com.vortexx.player.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vortexx.player.core.permissions.PermissionGroupState
import com.vortexx.player.ui.designsystem.components.AccentIconBadge
import com.vortexx.player.ui.designsystem.components.PrimaryButton
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing

/** First launch: explains media access before the system dialog appears. */
@Composable
fun WelcomeScreen(
    state: PermissionGroupState,
    onContinueWithoutAccess: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(VortexColors.Background)
            .background(VortexColors.HeroGlow),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(VortexSpacing.xl),
        ) {
            Text("VORTEX X", style = MaterialTheme.typography.displaySmall, color = VortexColors.TextPrimary)
            Text(
                "Professional music and video player",
                style = MaterialTheme.typography.titleMedium,
                color = VortexColors.AccentActive,
            )
            Spacer(Modifier.height(VortexSpacing.xxl))
            Feature(Icons.Rounded.Headphones, "Your music library", "Songs, albums, artists, genres and folders from this device.")
            Feature(Icons.Rounded.Movie, "Your videos", "Local videos with resume, track selection and scaling controls.")
            Feature(Icons.Rounded.Shield, "Scoped, private access", "VORTEX X reads media through Android's media permissions only. Nothing leaves your device.")
            Spacer(Modifier.height(VortexSpacing.xxl))
            if (state.permanentlyDenied) {
                Text(
                    "Media access is turned off. Enable Music & audio and Photos & videos under Permissions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexColors.TextSecondary,
                )
                Spacer(Modifier.height(VortexSpacing.md))
                PrimaryButton(text = "Open Android settings", onClick = state::openAppSettings, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton(text = "Allow media access", onClick = state::request, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(VortexSpacing.sm))
            SecondaryButton(
                text = "Continue without access",
                onClick = onContinueWithoutAccess,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(VortexSpacing.sm))
            Text(
                "Without access you can still open individual files from Media Explorer.",
                style = MaterialTheme.typography.bodySmall,
                color = VortexColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = VortexSpacing.sm), verticalAlignment = Alignment.Top) {
        AccentIconBadge(icon)
        Spacer(Modifier.width(VortexSpacing.md))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = VortexColors.TextSecondary)
        }
    }
}
