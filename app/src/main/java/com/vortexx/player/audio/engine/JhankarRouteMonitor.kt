package com.vortexx.player.audio.engine

import android.content.Context
import com.vortexx.player.audio.dsp.OutputProfile
import com.vortexx.player.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The kind of output the phone is sending sound to. */
enum class RouteKind(val label: String) {
    Speaker("Phone speaker"),
    Bluetooth("Bluetooth"),
    Wired("Wired"),
    Usb("USB"),
    Hdmi("HDMI"),
    Other("Other output"),
}

/** What the Jhankar studio shows about the output. Codec is never guessed: Android does not tell apps which one Bluetooth uses. */
data class JhankarRouteState(
    val connected: Boolean = false,
    val kind: RouteKind = RouteKind.Speaker,
    val deviceName: String = "",
    val typeName: String = "",
    val exact: Boolean = false,
    val sampleRates: List<Int> = emptyList(),
    val outputSampleRate: Int = 0,
    val outputChannels: Int = 0,
    val suggested: OutputProfile = OutputProfile.PremiumStereo,
    val lastEvent: String = "No output change yet",
) {
    val codecText: String get() = if (kind == RouteKind.Bluetooth) "Not exposed by Android" else "n/a"
}

object JhankarRoutes {
    /** Output kind from the type name [com.vortexx.player.core.capabilities.DeviceCapabilities.deviceTypeName] gives. */
    fun kindOf(typeName: String): RouteKind = when {
        typeName.startsWith("Bluetooth") || typeName == "Hearing aid" -> RouteKind.Bluetooth
        typeName.startsWith("Wired") || typeName == "Analog line out" -> RouteKind.Wired
        typeName.startsWith("USB") -> RouteKind.Usb
        typeName.startsWith("HDMI") || typeName == "Digital line out" -> RouteKind.Hdmi
        typeName == "Built-in speaker" || typeName == "Earpiece" -> RouteKind.Speaker
        else -> RouteKind.Other
    }

    /**
     * The profile to start with. Android cannot tell a Bluetooth speaker from Bluetooth earphones, so Bluetooth starts as
     * Tower (the user can change it, and the choice is remembered for that device); only LE headsets are known headphones.
     */
    fun defaultProfile(kind: RouteKind, typeName: String): OutputProfile = when (kind) {
        RouteKind.Bluetooth -> if (typeName == "Bluetooth LE headset" || typeName == "Hearing aid") OutputProfile.Headphone else OutputProfile.Tower
        RouteKind.Wired -> OutputProfile.Headphone
        RouteKind.Usb -> if (typeName == "USB headset") OutputProfile.Headphone else OutputProfile.PremiumStereo
        else -> OutputProfile.PremiumStereo
    }
}

/**
 * Follows the existing [AudioDiagnostics] route (no second device callback) and tells the Jhankar engine which
 * profile fits the output when it changes: a Bluetooth speaker connecting, headphones plugged in, a disconnect.
 */
@Singleton
class JhankarRouteMonitor @Inject constructor(
    @ApplicationContext context: Context,
    private val diagnostics: AudioDiagnostics,
    private val engine: AudioEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("jhankar_route", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(JhankarRouteState())
    val state: StateFlow<JhankarRouteState> = _state.asStateFlow()

    private var started = false
    private var lastKey: String? = null
    private var lastName = ""

    /** Starts following the route. Call once from the main thread (the application). */
    fun start() {
        if (started) return
        started = true
        diagnostics.startRouteTracking()
        scope.launch {
            // The engine restores its saved profile first; the route is applied on top of that.
            engine.loaded.first { it }
            diagnostics.state.collect { path -> onPath(path) }
        }
    }

    /** The user chose [profile] by hand: remember it for this Bluetooth device. */
    fun rememberChoice(profile: OutputProfile) {
        val key = lastKey ?: return
        if (_state.value.kind == RouteKind.Bluetooth) prefs.edit().putString(key, profile.name).apply()
    }

    private fun onPath(path: AudioPathState) {
        val route = path.route
        val kind = route?.let { JhankarRoutes.kindOf(it.typeName) } ?: RouteKind.Speaker
        val name = route?.productName.orEmpty()
        val typeName = route?.typeName ?: "Built-in speaker"
        val key = "$kind:$name"
        val changed = key != lastKey
        val previous = _state.value
        var suggested = previous.suggested
        var event = previous.lastEvent
        if (changed) {
            val remembered = if (kind == RouteKind.Bluetooth) OutputProfile.entries.firstOrNull { it.name == prefs.getString(key, null) } else null
            suggested = remembered ?: JhankarRoutes.defaultProfile(kind, typeName)
            val label = name.ifEmpty { typeName }
            event = when {
                lastKey == null -> "Output: $label"
                kind == RouteKind.Speaker -> "Disconnected: ${lastName.ifEmpty { "external output" }}, back on the phone speaker"
                else -> "Connected: $label"
            }
            lastKey = key
            lastName = name.ifEmpty { typeName }
            engine.onOutputProfile(suggested)
        }
        _state.value = JhankarRouteState(
            connected = kind != RouteKind.Speaker,
            kind = kind,
            deviceName = name,
            typeName = typeName,
            exact = route?.exact ?: false,
            sampleRates = route?.sampleRates.orEmpty(),
            outputSampleRate = path.output?.sampleRate ?: 0,
            outputChannels = path.output?.channelCount ?: 0,
            suggested = suggested,
            lastEvent = event,
        )
    }
}
