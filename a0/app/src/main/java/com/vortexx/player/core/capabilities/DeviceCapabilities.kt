package com.vortexx.player.core.capabilities

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.os.Build
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceSummary(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val soc: String?,
)

data class DecoderInfo(
    val codecName: String,
    val mime: String,
    val hardware: Boolean?,
    val maxWidth: Int?,
    val maxHeight: Int?,
)

/** A media format and the decoders installed for it. */
data class FormatSupport(
    val label: String,
    val mime: String,
    val decoders: List<DecoderInfo>,
) {
    val supported: Boolean get() = decoders.isNotEmpty()
    val hasHardware: Boolean get() = decoders.any { it.hardware == true }
}

data class OutputDevice(
    val typeName: String,
    val productName: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<String>,
)

data class SpatialAudioInfo(
    val apiAvailable: Boolean,
    val level: String,
    val available: Boolean,
    val enabled: Boolean,
)

data class EffectInfo(val name: String, val kind: String, val implementor: String)

data class EqualizerInfo(
    val bandCount: Int,
    val centerFrequenciesHz: List<Int>,
    val minLevelDb: Float,
    val maxLevelDb: Float,
    val presetNames: List<String>,
)

/**
 * Read-only capability probing from platform APIs. Nothing here alters audio;
 * it reports exactly what the device exposes so the UI never over-claims.
 */
@Singleton
class DeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun device(): DeviceSummary = DeviceSummary(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null,
    )

    fun audioFormats(): List<FormatSupport> = probe(AUDIO_FORMATS)

    fun videoFormats(): List<FormatSupport> = probe(VIDEO_FORMATS)

    /** Formats relevant to immersive / Dolby-family playback. */
    fun immersiveFormats(): List<FormatSupport> = probe(IMMERSIVE_FORMATS)

    private fun probe(formats: List<Pair<String, String>>): List<FormatSupport> {
        val all = decoders()
        return formats.map { (label, mime) ->
            FormatSupport(label, mime, all.filter { it.mime.equals(mime, ignoreCase = true) })
        }
    }

    private var cachedDecoders: List<DecoderInfo>? = null

    @Synchronized
    fun decoders(): List<DecoderInfo> {
        cachedDecoders?.let { return it }
        val result = mutableListOf<DecoderInfo>()
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrDefault(emptyArray())
        for (info in infos) {
            if (info.isEncoder) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) continue
            for (type in info.supportedTypes) {
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                val video = caps?.videoCapabilities
                result += DecoderInfo(
                    codecName = info.name,
                    mime = type,
                    hardware = isHardware(info),
                    maxWidth = video?.supportedWidths?.upper,
                    maxHeight = video?.supportedHeights?.upper,
                )
            }
        }
        cachedDecoders = result
        return result
    }

    private fun isHardware(info: MediaCodecInfo): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            if (name.startsWith("omx.google.") || name.startsWith("c2.android.")) false else null
        }

    fun outputDevices(): List<OutputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            OutputDevice(
                typeName = deviceTypeName(device.type),
                productName = device.productName?.toString().orEmpty(),
                sampleRates = device.sampleRates.toList(),
                channelCounts = device.channelCounts.toList(),
                encodings = device.encodings.map(::encodingName).distinct(),
            )
        }

    fun spatialAudio(): SpatialAudioInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
            return SpatialAudioInfo(apiAvailable = false, level = "Requires Android 12L or later", available = false, enabled = false)
        }
        val spatializer = audioManager.spatializer
        val level = when (spatializer.immersiveAudioLevel) {
            0 -> "None"
            1 -> "Multichannel"
            2 -> "Multichannel with objects"
            else -> "Other (${spatializer.immersiveAudioLevel})"
        }
        return SpatialAudioInfo(
            apiAvailable = true,
            level = level,
            available = spatializer.isAvailable,
            enabled = spatializer.isEnabled,
        )
    }

    fun hdrTypes(): List<String> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        val types: IntArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        }
        return types.map { type ->
            when (type) {
                1 -> "Dolby Vision"
                2 -> "HDR10"
                3 -> "HLG"
                4 -> "HDR10+"
                else -> "Type $type"
            }
        }.distinct()
    }

    fun audioEffects(): List<EffectInfo> =
        runCatching { AudioEffect.queryEffects() }.getOrNull().orEmpty().map { d ->
            EffectInfo(name = d.name, kind = effectKind(d.type), implementor = d.implementor)
        }

    /** Instantiates a platform Equalizer on a private session purely to read its layout. */
    fun equalizer(): EqualizerInfo? {
        var eq: Equalizer? = null
        return try {
            val session = audioManager.generateAudioSessionId()
            val equalizer = Equalizer(0, session)
            eq = equalizer
            val bands = equalizer.numberOfBands.toInt()
            val range = equalizer.bandLevelRange
            EqualizerInfo(
                bandCount = bands,
                centerFrequenciesHz = (0 until bands).map { equalizer.getCenterFreq(it.toShort()) / 1000 },
                minLevelDb = range[0] / 100f,
                maxLevelDb = range[1] / 100f,
                presetNames = (0 until equalizer.numberOfPresets.toInt()).map { equalizer.getPresetName(it.toShort()) },
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { eq?.release() }
        }
    }

    private fun effectKind(type: java.util.UUID): String = when (type) {
        AudioEffect.EFFECT_TYPE_EQUALIZER -> "Equalizer"
        AudioEffect.EFFECT_TYPE_BASS_BOOST -> "Bass boost"
        AudioEffect.EFFECT_TYPE_VIRTUALIZER -> "Virtualizer"
        AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER -> "Loudness enhancer"
        AudioEffect.EFFECT_TYPE_ENV_REVERB -> "Environmental reverb"
        AudioEffect.EFFECT_TYPE_PRESET_REVERB -> "Preset reverb"
        AudioEffect.EFFECT_TYPE_AEC -> "Echo cancellation"
        AudioEffect.EFFECT_TYPE_AGC -> "Gain control"
        AudioEffect.EFFECT_TYPE_NS -> "Noise suppression"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING) {
            "Dynamics processing"
        } else {
            "Vendor effect"
        }
    }

    companion object {
        val AUDIO_FORMATS = listOf(
            "MP3" to "audio/mpeg",
            "AAC" to "audio/mp4a-latm",
            "FLAC" to "audio/flac",
            "Opus" to "audio/opus",
            "Vorbis" to "audio/vorbis",
            "PCM / WAV" to "audio/raw",
            "ALAC" to "audio/alac",
        )

        val VIDEO_FORMATS = listOf(
            "H.264 / AVC" to "video/avc",
            "H.265 / HEVC" to "video/hevc",
            "VP9" to "video/x-vnd.on2.vp9",
            "AV1" to "video/av01",
            "VP8" to "video/x-vnd.on2.vp8",
            "MPEG-4 Part 2" to "video/mp4v-es",
            "Dolby Vision" to "video/dolby-vision",
        )

        val IMMERSIVE_FORMATS = listOf(
            "AC-3" to "audio/ac3",
            "E-AC-3" to "audio/eac3",
            "E-AC-3 JOC (object audio)" to "audio/eac3-joc",
            "AC-4" to "audio/ac4",
            "DTS" to "audio/vnd.dts",
            "DTS-HD" to "audio/vnd.dts.hd",
            "TrueHD" to "audio/true-hd",
            "MPEG-H 3D Audio" to "audio/mhm1",
        )

        fun deviceTypeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line out"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line out"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            26 -> "Bluetooth LE headset"
            27 -> "Bluetooth LE speaker"
            29 -> "HDMI eARC"
            30 -> "Bluetooth LE broadcast"
            else -> "Other output ($type)"
        }

        fun encodingName(encoding: Int): String = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
            AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
            21 -> "PCM 24-bit"
            22 -> "PCM 32-bit"
            AudioFormat.ENCODING_AC3 -> "AC-3"
            AudioFormat.ENCODING_E_AC3 -> "E-AC-3"
            AudioFormat.ENCODING_DTS -> "DTS"
            AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
            AudioFormat.ENCODING_IEC61937 -> "IEC 61937"
            14 -> "Dolby TrueHD"
            17 -> "AC-4"
            18 -> "E-AC-3 JOC"
            19 -> "Dolby MAT"
            20 -> "Opus"
            else -> "Encoding $encoding"
        }
    }
}
