package com.vortexx.player.audio.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vortexx.player.audio.dsp.DspHost
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.LoadMeter
import com.vortexx.player.audio.dsp.MeterBus
import com.vortexx.player.audio.dsp.ProcessorFormat
import com.vortexx.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How the music player should send audio to the output. */
enum class OutputModePreference(val label: String, val summary: String) {
    Dsp("DSP (PCM)", "Decoded PCM runs through the Audio Lab"),
    Offload("Battery offload", "Hardware decodes where supported; Audio Lab off"),
}

/** The processor's view of the current stream, published from the playback thread. */
data class ProcessorState(
    val format: ProcessorFormat?,
    val inactiveReason: String?,
)

/**
 * Owner of the DSP settings. The UI changes them here; the audio thread reads
 * [parameters] (a volatile reference) once per buffer through [DspHost].
 * Settings persist in the app's DataStore, saved 300 ms after the last change
 * so dragging a fader does not write on every frame.
 */
@Singleton
class AudioEngine @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) : DspHost {

    override val meters = MeterBus()
    override val load = LoadMeter()

    @Volatile
    override var parameters: DspParameters = DspParameters()
        private set

    @Volatile
    override var processingAllowed: Boolean = true
        private set

    private val _state = MutableStateFlow(DspParameters())
    val state: StateFlow<DspParameters> = _state.asStateFlow()

    private val _outputMode = MutableStateFlow(OutputModePreference.Dsp)
    val outputMode: StateFlow<OutputModePreference> = _outputMode.asStateFlow()

    private val _processor = MutableStateFlow(ProcessorState(format = null, inactiveReason = null))
    val processor: StateFlow<ProcessorState> = _processor.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var saveJob: Job? = null

    init {
        scope.launch {
            val prefs = dataStore.data.first()
            val restored = DspParameters(
                enabled = prefs[KEY_ENABLED] ?: true,
                preampDb = DspParameters.clampPreamp(prefs[KEY_PREAMP] ?: 0f),
                bandGainsDb = DspParameters.parseBands(prefs[KEY_BANDS]),
            )
            val mode = OutputModePreference.entries.firstOrNull { it.name == prefs[KEY_OUTPUT_MODE] } ?: OutputModePreference.Dsp
            publish(restored, save = false)
            processingAllowed = mode == OutputModePreference.Dsp
            _outputMode.value = mode
            _loaded.value = true
        }
    }

    fun setEnabled(enabled: Boolean) = publish(parameters.copy(enabled = enabled))

    fun setPreamp(db: Float) = publish(parameters.withPreamp(db))

    fun setBand(index: Int, db: Float) = publish(parameters.withBand(index, db))

    fun resetEq() = publish(parameters.copy(preampDb = 0f, bandGainsDb = DspParameters.FLAT))

    /** Uses the complete cascade response at the active PCM rate, plus a margin. */
    fun applyAutoHeadroom() = publish(parameters.withPreamp(
        parameters.recommendedPreampDb(_processor.value.format?.sampleRate ?: 48_000),
    ))

    fun setOutputMode(mode: OutputModePreference) {
        processingAllowed = mode == OutputModePreference.Dsp
        _outputMode.value = mode
        scope.launch { dataStore.edit { it[KEY_OUTPUT_MODE] = mode.name } }
    }

    override fun onProcessorConfigured(format: ProcessorFormat?, reason: String?) {
        _processor.value = ProcessorState(format, reason)
    }

    private fun publish(next: DspParameters, save: Boolean = true) {
        parameters = next
        _state.value = next
        if (!save) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            dataStore.edit { prefs ->
                prefs[KEY_ENABLED] = next.enabled
                prefs[KEY_PREAMP] = next.preampDb
                prefs[KEY_BANDS] = DspParameters.formatBands(next.bandGainsDb)
            }
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 300L
        val KEY_ENABLED = booleanPreferencesKey("dsp_enabled")
        val KEY_PREAMP = floatPreferencesKey("dsp_preamp_db")
        val KEY_BANDS = stringPreferencesKey("dsp_eq10_db")
        val KEY_OUTPUT_MODE = stringPreferencesKey("audio_output_mode")
    }
}
