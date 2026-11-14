package com.vortexx.player.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexRadius
import com.vortexx.player.ui.designsystem.theme.VortexSpacing

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(VortexSpacing.xl)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = VortexColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(VortexSpacing.lg))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = VortexColors.TextSecondary)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateMessage(
        icon = icon,
        iconTint = VortexColors.TextSecondary,
        title = title,
        message = message,
        modifier = modifier,
    ) {
        if (actionLabel != null && onAction != null) {
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    StateMessage(
        icon = Icons.Rounded.ErrorOutline,
        iconTint = VortexColors.AccentActive,
        title = title,
        message = message,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        if (onRetry != null) PrimaryButton(text = "Try again", onClick = onRetry)
    }
}

/**
 * Explains why access is needed. When the permission was permanently denied,
 * the only working path is the system settings screen, so that is offered instead.
 */
@Composable
fun PermissionState(
    title: String,
    message: String,
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    StateMessage(
        icon = Icons.Rounded.Lock,
        iconTint = VortexColors.AccentActive,
        title = title,
        message = if (permanentlyDenied) {
            "$message\n\nAccess was denied. Enable it under Permissions in Android settings."
        } else {
            message
        },
        modifier = modifier,
    ) {
        if (permanentlyDenied) {
            PrimaryButton(text = "Open Android settings", onClick = onOpenSettings)
        } else {
            PrimaryButton(text = "Allow access", onClick = onRequest)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(VortexSpacing.sm))
            SecondaryButton(text = secondaryLabel, onClick = onSecondary)
        }
    }
}

@Composable
private fun StateMessage(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(VortexSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(VortexColors.Card),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(VortexSpacing.lg))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(VortexSpacing.sm))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = VortexColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(VortexSpacing.xl))
            actions()
        }
    }
}

enum class NoticeTone { Info, Caution, Error, Success }

/** Inline banner for limitations and partial states (e.g. limited media access). */
@Composable
fun InlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tint = when (tone) {
        NoticeTone.Info -> VortexColors.TextSecondary
        NoticeTone.Caution -> VortexColors.Caution
        NoticeTone.Error -> VortexColors.AccentActive
        NoticeTone.Success -> VortexColors.Success
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VortexRadius.sm))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = VortexSpacing.md, vertical = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (tone == NoticeTone.Error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(VortexSpacing.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextPrimary, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(actionLabel, color = tint)
            }
        }
    }
}
