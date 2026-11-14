package com.vortexx.player.ui.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Maximum content widths so screens stay readable on tablets, foldables and landscape phones. */
object VortexWidths {
    /** Lists, settings, details and text-heavy screens. */
    val Reading: Dp = 840.dp

    /** Media grids (home, videos). */
    val Wide: Dp = 1280.dp
}

/**
 * Centres [content] and caps its width at [maxWidth]. On phones (narrower than
 * [maxWidth]) it is a no-op; on wide windows it stops rows and cards from stretching.
 */
@Composable
fun AdaptiveWidth(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxSize(),
        ) {
            content()
        }
    }
}
