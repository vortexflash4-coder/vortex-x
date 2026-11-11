package com.vortexx.player.ui.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vortexx.player.data.library.ArtworkLoader
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.ui.designsystem.theme.VortexColors

/** Provided at the app root; null in previews, where a fallback icon is drawn. */
val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader?> { null }

/**
 * Album art / video thumbnail with a graceful fallback. Loading happens off the
 * main thread and results are memory-cached by [ArtworkLoader].
 */
@Composable
fun MediaArtwork(
    request: ArtworkRequest?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentDescription: String? = null,
) {
    val loader = LocalArtworkLoader.current
    val shape = RoundedCornerShape(cornerRadius)
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(VortexColors.CardRaised, VortexColors.Card)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val sizePx = if (constraints.hasBoundedWidth) constraints.maxWidth else 256
        val bitmap: ImageBitmap? by produceState(
            initialValue = if (request != null) loader?.cached(request, sizePx)?.asImageBitmap() else null,
            request,
            sizePx,
        ) {
            // Keys changed (or first run): show the cached image immediately, else clear and load.
            if (request == null || loader == null) {
                value = null
                return@produceState
            }
            val cached = loader.cached(request, sizePx)
            if (cached != null) {
                value = cached.asImageBitmap()
            } else {
                value = null
                value = loader.load(request, sizePx)?.asImageBitmap()
            }
        }
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = VortexColors.TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(0.42f),
            )
        }
    }
}
