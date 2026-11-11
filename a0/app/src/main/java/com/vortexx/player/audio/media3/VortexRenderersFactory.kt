package com.vortexx.player.audio.media3

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Renderers factory for the music player: Media3's standard renderers, with
 * [processor] added to DefaultAudioSink's PCM processing chain.
 *
 * Float output stays as Media3 sets it (off by default) because Media3 skips
 * audio processors when float output is enabled.
 */
@UnstableApi
class VortexRenderersFactory(
    context: Context,
    private val processor: AudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(processor))
            .build()
}
