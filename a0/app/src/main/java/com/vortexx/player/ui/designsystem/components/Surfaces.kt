package com.vortexx.player.ui.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexRadius
import com.vortexx.player.ui.designsystem.theme.VortexSpacing

/** Rounded graphite card with a hairline border. */
@Composable
fun VortexCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = VortexColors.Card,
    contentPadding: PaddingValues = PaddingValues(VortexSpacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(VortexRadius.md)
    val base = modifier
        .clip(shape)
        .background(color)
        .border(BorderStroke(1.dp, VortexColors.Divider.copy(alpha = 0.55f)), shape)
    val interactive = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Column(modifier = interactive.padding(contentPadding), content = content)
}

/** Screen header used instead of a generic Material top app bar. */
@Composable
fun VortexTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = if (onBack == null) VortexSpacing.lg else VortexSpacing.xs, end = VortexSpacing.xs)
            .padding(vertical = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(VortexColors.Accent),
        )
        Spacer(Modifier.width(VortexSpacing.sm))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = VortexColors.TextSecondary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Selectable pill used for tabs, filters and option groups. */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val background = if (selected) VortexColors.Accent else VortexColors.Card
    val content = if (selected) VortexColors.TextPrimary else VortexColors.TextSecondary
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(VortexRadius.pill))
            .background(background)
            .border(1.dp, if (selected) Color.Transparent else VortexColors.Divider, RoundedCornerShape(VortexRadius.pill))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VortexSpacing.xs))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = VortexSpacing.touchTarget),
        shape = RoundedCornerShape(VortexRadius.pill),
        colors = ButtonDefaults.buttonColors(
            containerColor = VortexColors.Accent,
            contentColor = VortexColors.TextPrimary,
            disabledContainerColor = VortexColors.Card,
            disabledContentColor = VortexColors.TextSecondary,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VortexSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = VortexSpacing.touchTarget),
        shape = RoundedCornerShape(VortexRadius.pill),
        border = BorderStroke(1.dp, VortexColors.Divider),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VortexColors.TextPrimary),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VortexSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Compact statistic tile for dashboards. */
@Composable
fun StatTile(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    VortexCard(modifier = modifier, contentPadding = PaddingValues(VortexSpacing.md)) {
        Icon(icon, contentDescription = null, tint = VortexColors.AccentActive, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(VortexSpacing.sm))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = VortexColors.TextSecondary, maxLines = 1)
    }
}

/** Icon badge in a soft crimson circle. */
@Composable
fun AccentIconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = VortexColors.AccentActive) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(VortexColors.AccentContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A settings-style row with a switch; the whole row is the touch target. */
@Composable
fun ToggleRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary)
            }
        }
        Spacer(Modifier.width(VortexSpacing.md))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VortexColors.TextPrimary,
                checkedTrackColor = VortexColors.Accent,
                uncheckedThumbColor = VortexColors.TextSecondary,
                uncheckedTrackColor = VortexColors.Card,
                uncheckedBorderColor = VortexColors.Divider,
            ),
        )
    }
}

/** Key/value line for diagnostics and info cards. */
@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = VortexColors.TextPrimary) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = VortexSpacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = VortexColors.TextSecondary,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(0.55f),
        )
    }
}

/** Tinted surface used as the base of overlays such as the mini player. */
@Composable
fun ElevatedPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = VortexColors.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(VortexRadius.lg),
        content = content,
    )
}
