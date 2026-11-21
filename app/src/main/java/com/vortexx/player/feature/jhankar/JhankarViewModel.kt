package com.vortexx.player.feature.jhankar

import androidx.lifecycle.ViewModel
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.JhankarMeters
import com.vortexx.player.audio.dsp.JhankarParameters
import com.vortexx.player.audio.dsp.JhankarPreset
import com.vortexx.player.audio.dsp.MeterBus
import com.vortexx.player.audio.dsp.OutputProfile
import com.vortexx.player.audio.dsp.PresetResult
import com.vortexx.player.audio.dsp.SpectrumBus
import com.vortexx.player.audio.engine.AudioEngine
import com.vortexx.player.audio.engine.JhankarRouteMonitor
import com.vortexx.player.audio.engine.JhankarRouteState
import com.vortexx.player.audio.engine.JhankarState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** The two sounds the A/B switch flips between; [active] is 'A' or 'B'. */
data class AbState(val active: Char = 'A', val a: JhankarParameters? = null, val b: JhankarParameters? = null)

@HiltViewModel
class JhankarViewModel @Inject constructor(
    private val engine: AudioEngine,
    private val routeMonitor: JhankarRouteMonitor,
) : ViewModel() {
    val state: StateFlow<JhankarState> = engine.jhankarState
    val presets: StateFlow<List<JhankarPreset>> = engine.jhankarPresets
    val dsp: StateFlow<DspParameters> = engine.state
    val route: StateFlow<JhankarRouteState> = routeMonitor.state
    val meters: MeterBus get() = engine.meters
    val jhankarMeters: JhankarMeters get() = engine.jhankarMeters
    val spectrum: SpectrumBus get() = engine.spectrum

    private val _ab = MutableStateFlow(AbState())
    val ab: StateFlow<AbState> = _ab.asStateFlow()

    fun setEngine(on: Boolean) = engine.setJhankarEngine(on)

    fun selectProfile(profile: OutputProfile) {
        engine.selectJhankarProfile(profile)
        routeMonitor.rememberChoice(profile)
        _ab.value = AbState()
    }

    fun setAuto(on: Boolean) = engine.setJhankarAuto(on)

    fun update(change: (JhankarParameters) -> JhankarParameters) = engine.updateJhankar(change)

    fun setLimiter(on: Boolean) = engine.setLimiter(on)

    fun applyPreset(preset: JhankarPreset) = engine.applyJhankarPreset(preset)

    fun resetEq() = engine.resetJhankarEq()

    fun resetProfile() {
        engine.resetJhankarProfile()
        _ab.value = AbState()
    }

    fun savePreset(name: String): PresetResult = engine.saveJhankarPreset(name)

    fun renamePreset(name: String, newName: String): PresetResult = engine.renameJhankarPreset(name, newName)

    fun deletePreset(name: String) {
        engine.deleteJhankarPreset(name)
    }

    fun resetClipCounter() = engine.meters.resetClipCounter()

    /** Stores the sound now playing in the active slot and switches to the other one (which starts as the same sound). */
    fun toggleAb() {
        val now = engine.jhankarState.value.parameters.copy(engine = false)
        val current = _ab.value
        val next = if (current.active == 'A') 'B' else 'A'
        val a = if (current.active == 'A') now else current.a ?: now
        val b = if (current.active == 'B') now else current.b ?: now
        _ab.value = AbState(next, a, b)
        val target = if (next == 'A') a else b
        engine.updateJhankar { target }
    }
}
