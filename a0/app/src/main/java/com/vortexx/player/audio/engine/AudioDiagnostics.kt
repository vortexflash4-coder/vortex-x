package com.vortexx.player.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.vortexx.player.audio.media3.AudioEncodings
import com.vortexx.player.core.capabilities.DeviceCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** The decoded stream entering the audio renderer. */
data class InputStreamInfo(
    val mimeType: String?,
    val codecs: String?,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrate: Int,
)

/** The output Media3 actually opened (from AudioSink.AudioTrackConfig). */
data class OutputConfigInfo(
    val encoding: Int,
    val encodingName: String,
    val isPcm: Boolean,
    val sampleRate: Int,
    val channelCount: Int,
    val offload: Boolean,
    val tunneling: Boolean,
    val bufferMillis: Int?,
)

data class RouteInfo(
    val typeName: String,
    val productName: String,
    /** True when Android reported the route for media (Android 13+); false = best guess from connected outputs. */
    val exact: Boolean,
    val sampleRates: List<Int>,
    val encodings: List<String>,
)

data class OffloadSupport(val codec: String, val supported: Boolean)

data class AudioPathState(
    val decoderName: String? = null,
    val input: InputStreamInfo? = null,
    val output: OutputConfigInfo? = null,
    val underruns: Int = 0,
    val sinkError: String? = null,
    val route: RouteInfo? = null,
    val routeChanges: Int = 0,
    val offloadSupport: List<OffloadSupport> = emptyList(),
)

/**
 * Live description of the music player's audio path, fed by Media3 analytics
 * and Android's audio-device callbacks. Everything shown in the Audio Lab's
 * path panel comes from here; nothing is inferred from settings alone.
 */
@Singleton
class AudioDiagnostics @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _state = MutableStateFlow(AudioPathState())
    val state: StateFlow<AudioPathState> = _state.asStateFlow()

    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var callbackRegistered = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
    }

    /** Media3 analytics listener to attach to the music player. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun analyticsListener(): AnalyticsListener = object : AnalyticsListener {
        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _state.update { it.copy(decoderName = decoderName) }
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            val info = InputStreamInfo(
                mimeType = format.sampleMimeType,
                codecs = format.codecs,
                sampleRate = format.sampleRate,
                channelCount = format.channelCount,
                bitrate = if (format.averageBitrate > 0) format.averageBitrate else format.bitrate,
            )
            _state.update { it.copy(input = info) }
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val channels = Integer.bitCount(audioTrackConfig.channelConfig)
            val info = OutputConfigInfo(
                encoding = audioTrackConfig.encoding,
                encodingName = AudioEncodings.name(audioTrackConfig.encoding),
                isPcm = AudioEncodings.isLinearPcm(audioTrackConfig.encoding),
                sampleRate = audioTrackConfig.sampleRate,
                channelCount = channels,
                offload = audioTrackConfig.offload,
                tunneling = audioTrackConfig.tunneling,
                bufferMillis = AudioEncodings.bufferMillis(audioTrackConfig.encoding, audioTrackConfig.bufferSize, audioTrackConfig.sampleRate, channels),
            )
            _state.update { it.copy(output = info, sinkError = null) }
        }

        override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            _state.update { it.copy(output = null) }
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            _state.update { it.copy(underruns = it.underruns + 1) }
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            _state.update { it.copy(sinkError = audioSinkError.message ?: audioSinkError.javaClass.simpleName) }
        }
    }

    /** Starts route tracking (main thread). Safe to call more than once. */
    fun startRouteTracking() {
        if (!callbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
        }
        _state.update { it.copy(offloadSupport = probeOffloadSupport()) }
        refreshRoute()
    }

    fun stopRouteTracking() {
        if (callbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            callbackRegistered = false
        }
    }

    fun resetUnderruns() {
        _state.update { it.copy(underruns = 0) }
    }

    private fun refreshRoute() {
        val route = currentRoute()
        _state.update { current ->
            val changed = current.route != null && route != null &&
                (current.route.typeName != route.typeName || current.route.productName != route.productName)
            current.copy(route = route, routeChanges = current.routeChanges + if (changed) 1 else 0)
        }
    }

    private fun currentRoute(): RouteInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val routed = runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes) }.getOrNull()
            val device = routed?.firstOrNull()
            if (device != null) return device.toRoute(exact = true)
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val best = outputs.filter { mediaPriority(it.type) > 0 }.maxByOrNull { mediaPriority(it.type) } ?: return null
        return best.toRoute(exact = false)
    }

    private fun AudioDeviceInfo.toRoute(exact: Boolean) = RouteInfo(
        typeName = DeviceCapabilities.deviceTypeName(type),
        productName = productName?.toString().orEmpty(),
        exact = exact,
        sampleRates = sampleRates.toList(),
        encodings = encodings.map { DeviceCapabilities.encodingName(it) }.distinct(),
    )

    /** Android's usual media routing preference, used only before Android 13. */
    private fun mediaPriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> 90
        AudioDeviceInfo.TYPE_USB_DEVICE -> 85
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 80
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> 75
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 70
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> 60
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> 50
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 10
        else -> 0
    }

    @Suppress("DEPRECATION")
    private fun probeOffloadSupport(): List<OffloadSupport> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return listOf("MP3" to AudioFormat.ENCODING_MP3, "AAC-LC" to AudioFormat.ENCODING_AAC_LC).map { (name, encoding) ->
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(44_100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val supported = runCatching { AudioManager.isOffloadedPlaybackSupported(format, mediaAttributes) }.getOrDefault(false)
            OffloadSupport(name, supported)
        }
    }

    private companion object {
        // AudioDeviceInfo.TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER (API 31); literals keep minSdk 26 builds lint-clean.
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
    }
}
