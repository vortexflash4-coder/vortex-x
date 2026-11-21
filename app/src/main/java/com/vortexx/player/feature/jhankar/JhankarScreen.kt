package com.vortexx.player.feature.jhankar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortexx.player.audio.dsp.JhankarBand
import com.vortexx.player.audio.dsp.JhankarCurve
import com.vortexx.player.audio.dsp.JhankarParameters
import com.vortexx.player.audio.dsp.JhankarPreset
import com.vortexx.player.audio.dsp.JhankarPresets
import com.vortexx.player.audio.dsp.JhankarReading
import com.vortexx.player.audio.dsp.JhankarTone
import com.vortexx.player.audio.dsp.OutputProfile
import com.vortexx.player.audio.dsp.PresetResult
import com.vortexx.player.audio.dsp.SpectrumAnalyzer
import com.vortexx.player.audio.engine.JhankarRouteState
import com.vortexx.player.ui.designsystem.components.PillChip
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.components.ToggleRow
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.instrument.EqFader
import com.vortexx.player.ui.designsystem.instrument.InstrumentText
import com.vortexx.player.ui.designsystem.instrument.PanelLabel
import com.vortexx.player.ui.designsystem.instrument.PrecisionSlider
import com.vortexx.player.ui.designsystem.instrument.ReadoutGrid
import com.vortexx.player.ui.designsystem.instrument.SegmentedSelector
import com.vortexx.player.ui.designsystem.instrument.StatusLed
import com.vortexx.player.ui.designsystem.instrument.StatusTone
import com.vortexx.player.ui.designsystem.instrument.StereoLevelMeter
import com.vortexx.player.ui.designsystem.instrument.formatDb
import com.vortexx.player.ui.designsystem.instrument.rememberMeterDisplay
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Jhankar Sound Studio: the one screen for the Jhankar engine. Every control here changes what the DspChain does. */
@Composable
fun JhankarScreen(onBack: () -> Unit, viewModel: JhankarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val dsp by viewModel.dsp.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val ab by viewModel.ab.collectAsStateWithLifecycle()
    val meter = rememberMeterDisplay(viewModel.meters)
    val p = state.parameters
    val update = viewModel::update
    val sampleRate = viewModel.spectrum.sampleRate
    var band by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(title = "Jhankar Sound Studio", subtitle = "Live stage master · Hindi · Bluetooth tower", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.xxl)) {
            // 1 ---------------------------------------------------------------- Sound engine
            item("h1") { PanelLabel("1 · Sound engine") }
            item("engine") {
                Column {
                    ToggleRow(
                        title = "Jhankar engine",
                        summary = if (state.engineOn) "ON · processing ${state.profile.label}" else "OFF · your music plays untouched",
                        checked = state.engineOn,
                        onCheckedChange = viewModel::setEngine,
                    )
                    if (!state.engineOn) Note("Settings below are saved but have no effect until the engine is ON.")
                    Slider("Intensity", "Scales every enhancement below. EQ, sub-bass and output gain stay exactly as set.", p.intensity, { v -> update { it.copy(intensity = v) } }, reset = JhankarTone.DEFAULT_INTENSITY)
                    Spacer(Modifier.height(VortexSpacing.sm))
                    SegmentedSelector(
                        options = listOf('A', 'B'),
                        selected = ab.active,
                        label = { "Sound $it" },
                        onSelect = { if (it != ab.active) viewModel.toggleAb() },
                    )
                    Note("A/B: switch between two settings. Change sound A, tap B, change it again, then tap back and forth to compare.")
                    Row(Modifier.padding(horizontal = VortexSpacing.lg), horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm)) {
                        SecondaryButton("Reset this output", onClick = viewModel::resetProfile, modifier = Modifier.weight(1f))
                    }
                }
            }

            // 2 ---------------------------------------------------------------- Equalizer
            item("h2") {
                PanelLabel("2 · Professional equalizer") {
                    TextButton(onClick = viewModel::resetEq) { Text("Flat", style = InstrumentText.readoutSmall) }
                }
            }
            item("eq") {
                Column {
                    ToggleRow("Equalizer", "10 bands: low shelf, 8 bells, high shelf", p.eqOn, { v -> update { it.copy(eqOn = v) } })
                    EqPanel(p, sampleRate, state.engineOn && p.eqOn)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val faderWidth = maxOf(56.dp, (maxWidth - VortexSpacing.sm * 2) / JhankarTone.BANDS)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = VortexSpacing.sm)) {
                            p.bands.forEachIndexed { i, b ->
                                EqFader(
                                    label = hzText(b.freqHz),
                                    gainDb = b.gainDb,
                                    usable = b.freqHz < sampleRate * 0.45f,
                                    onChange = { db -> update { it.withBand(i, it.bands[i].copy(gainDb = db)) } },
                                    modifier = Modifier.width(faderWidth),
                                    maxDb = JhankarTone.MAX_BAND_DB,
                                )
                            }
                        }
                    }
                    Note("Drag a fader, double-tap to reset it. Pick a band below to move its frequency and Q.")
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = VortexSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
                    ) {
                        p.bands.forEachIndexed { i, b -> PillChip("Band ${i + 1}", selected = i == band, onClick = { band = i }) }
                    }
                    val b = p.bands[band.coerceIn(0, JhankarTone.BANDS - 1)]
                    val index = band.coerceIn(0, JhankarTone.BANDS - 1)
                    val kind = when (index) { 0 -> "low shelf"; JhankarTone.BANDS - 1 -> "high shelf"; else -> "bell" }
                    Caption("Band ${index + 1} · $kind · ${hzText(b.freqHz)} Hz")
                    LogSlider("Frequency", b.freqHz, JhankarTone.MIN_FREQ_HZ, JhankarTone.MAX_FREQ_HZ, "Hz", JhankarTone.DEFAULT_FREQ_HZ[index]) { f -> update { it.withBand(index, it.bands[index].copy(freqHz = f)) } }
                    FloatSlider("Gain", b.gainDb, -JhankarTone.MAX_BAND_DB..JhankarTone.MAX_BAND_DB, 0.5f, "dB", 0f) { g -> update { it.withBand(index, it.bands[index].copy(gainDb = g)) } }
                    LogSlider("Q (width)", b.q, JhankarTone.MIN_Q, JhankarTone.MAX_Q, "", JhankarTone.DEFAULT_Q) { q -> update { it.withBand(index, it.bands[index].copy(q = q)) } }
                    Caption("Preamp")
                    FloatSlider("Preamp", p.preampDb, -JhankarTone.MAX_PREAMP_DB..JhankarTone.MAX_PREAMP_DB, 0.5f, "dB", 0f) { v -> update { it.copy(preampDb = v) } }
                    ToggleRow("Auto headroom", "Takes the average boost of the EQ off the preamp: -${formatDb(JhankarCurve.headroomDb(p, sampleRate).toFloat(), signed = false)} dB now", p.autoHeadroom, { v -> update { it.copy(autoHeadroom = v) } })
                }
            }

            // 3 ---------------------------------------------------------------- Bluetooth tower studio
            item("h3") { PanelLabel("3 · Bluetooth tower studio") }
            item("bt") {
                Column {
                    RouteLed(route)
                    ReadoutGrid(
                        buildList {
                            add("Output" to (route.deviceName.ifEmpty { route.typeName.ifEmpty { "Phone speaker" } }))
                            add("Route type" to route.typeName.ifEmpty { "-" })
                            add("Codec" to route.codecText)
                            add("Sample rate" to if (route.outputSampleRate > 0) "${route.outputSampleRate} Hz" else "waiting for playback")
                            add("Channels" to if (route.outputChannels > 0) route.outputChannels.toString() else "waiting for playback")
                            add("Device rates" to if (route.sampleRates.isEmpty()) "not reported" else route.sampleRates.joinToString(" ") { "${it / 1000f}k".replace(".0k", "k") })
                            add("Last event" to route.lastEvent)
                        },
                    )
                    Spacer(Modifier.height(VortexSpacing.sm))
                    SegmentedSelector(
                        options = OutputProfile.entries.toList(),
                        selected = state.profile,
                        label = { shortProfile(it) },
                        onSelect = viewModel::selectProfile,
                    )
                    Note(state.profile.summary)
                    ToggleRow("Automatic profile", "Switch by itself when a speaker, headphones or tower connects. Your manual choice is remembered for that Bluetooth device.", state.autoProfile, viewModel::setAuto)
                    Note("Android does not say which codec Bluetooth uses, and it cannot tell a speaker from earphones, so pick the profile if the guess is wrong. The app improves the signal it sends; it cannot change the speaker's hardware.")
                }
            }

            // 4 ---------------------------------------------------------------- Bass
            item("h4") { PanelLabel("4 · Bass & subwoofer") }
            item("bass") {
                Column {
                    ToggleRow("Bass section", "Dynamic bass, sub-bass, harmonics and mono bass", p.bassOn, { v -> update { it.copy(bassOn = v) } })
                    Slider("Dynamic bass", "Adds bass only where the track is thin, so loud tracks do not boom.", p.dynamicBass, { v -> update { it.copy(dynamicBass = v) } })
                    FloatSlider("Sub-bass shelf", p.subBassDb, -JhankarTone.MAX_SUB_DB..JhankarTone.MAX_SUB_DB, 0.5f, "dB", 0f, caption = "Below 60 Hz. Cut it for small speakers.") { v -> update { it.copy(subBassDb = v) } }
                    Slider("Bass harmonics", "Overtones so the bass is heard on small speakers too.", p.bassHarmonics, { v -> update { it.copy(bassHarmonics = v) } })
                    Slider(
                        "Mono bass below", "Keeps the lows centred so a tower or two speakers do not cancel. 0 = off.", p.monoBelowHz, { v -> update { it.copy(monoBelowHz = JhankarParameters.clampMono(v)) } },
                        range = 0..JhankarTone.MAX_MONO_HZ, step = 10, suffix = "Hz", reset = 0,
                    )
                }
            }

            // 5 ---------------------------------------------------------------- Vocal
            item("h5") { PanelLabel("5 · Vocal enhancement") }
            item("vocal") {
                Column {
                    Slider("Vocal clarity", "Cuts mud, adds presence to the voice.", p.vocalClarity, { v -> update { it.copy(vocalClarity = v) } })
                    Slider("High detail", "Lifts cymbals, tabla and string detail.", p.hfDetail, { v -> update { it.copy(hfDetail = v) } })
                    Slider("Air harmonics", "A little sparkle above 10 kHz.", p.airHarmonics, { v -> update { it.copy(airHarmonics = v) } })
                }
            }

            // 6 ---------------------------------------------------------------- Stereo
            item("h6") { PanelLabel("6 · Stereo imaging") }
            item("stereo") {
                Column {
                    ToggleRow("Stereo section", "Width and mono bass", p.stereoOn, { v -> update { it.copy(stereoOn = v) } })
                    Slider("Stereo width", "100 % = as recorded. Above 100 % is scaled by Intensity.", p.widthPercent, { v -> update { it.copy(widthPercent = JhankarParameters.clampWidth(v)) } }, range = JhankarTone.MIN_WIDTH..JhankarTone.MAX_WIDTH, step = 5, reset = 100)
                }
            }

            // 7 ---------------------------------------------------------------- Dynamics
            item("h7") { PanelLabel("7 · Dynamic range control") }
            item("dyn") {
                Column {
                    Slider("Compressor", "Evens out loud and soft parts, with make-up gain.", p.compression, { v -> update { it.copy(compression = v) } })
                    Slider("Transient punch", "More attack on dhol, tabla and kick.", p.transient, { v -> update { it.copy(transient = v) } })
                    Slider("Loudness compensation", "Fuller lows and highs at low volume.", p.loudness, { v -> update { it.copy(loudness = v) } })
                    ToggleRow("Peak limiter", "Ceiling -0.5 dBFS, 5 ms look-ahead. Keep it ON so the output cannot clip.", dsp.limiter, viewModel::setLimiter)
                    if (state.engineOn && !dsp.limiter) Note("Limiter is OFF: strong settings can now clip and sound harsh.")
                }
            }

            // 8 ---------------------------------------------------------------- Analyzer
            item("h8") { PanelLabel("8 · Audio analyzer") { Text("clipped ${meter.clippedTotal}", style = InstrumentText.readoutSmall) } }
            item("ana") {
                Column {
                    Spectrum(viewModel.spectrum)
                    Spacer(Modifier.height(VortexSpacing.sm))
                    StereoLevelMeter(meter, onClearClip = viewModel::resetClipCounter)
                    Spacer(Modifier.height(VortexSpacing.sm))
                    EngineReadout(viewModel)
                    Note("Spectrum and meters show the sound after the whole chain, as it leaves for the speaker.")
                }
            }

            // 9 ---------------------------------------------------------------- Presets
            item("h9") { PanelLabel("9 · Preset library") }
            item("presets") {
                PresetLibrary(p, presets, viewModel::applyPreset, viewModel::savePreset, viewModel::renamePreset, viewModel::deletePreset)
            }

            // 10 --------------------------------------------------------------- Calibration
            item("h10") { PanelLabel("10 · Advanced calibration") }
            item("cal") {
                Column {
                    FloatSlider("Output gain", p.outputGainDb, JhankarTone.MIN_OUTPUT_DB..JhankarTone.MAX_OUTPUT_DB, 0.5f, "dB", 0f, caption = "Final level before the limiter. Tower profile starts at -1 dB to leave room for the Bluetooth encoder.") { v -> update { it.copy(outputGainDb = v) } }
                    Note("Known limits: the limiter reads sample peaks, not true peaks. The loudness contour is fixed, not volume-following. This is the app's own signal processing; it is not Dolby Atmos and carries no Dolby certification.")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Caption(text: String) {
    Text(text, style = InstrumentText.readoutSmall, modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.xs))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = VortexColors.TextSecondary,
        modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.xs),
    )
}

private fun hzText(hz: Float): String =
    if (hz >= 1000f) String.format(Locale.ROOT, "%.1fk", hz / 1000f).replace(".0k", "k") else String.format(Locale.ROOT, "%.0f", hz)

private fun shortProfile(p: OutputProfile): String = when (p) {
    OutputProfile.Tower -> "Tower"
    OutputProfile.PartySpeaker -> "Party"
    OutputProfile.PremiumStereo -> "Stereo"
    OutputProfile.Headphone -> "Phones"
}

/** Whole-number slider (percent, Hz) with a caption; double-tap resets. */
@Composable
private fun Slider(
    title: String,
    caption: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange = 0..100,
    step: Int = 5,
    suffix: String = "%",
    reset: Int = 0,
) {
    Column {
        Caption("$title · $caption")
        PrecisionSlider(
            label = title,
            value = value.toFloat(),
            range = range.first.toFloat()..range.last.toFloat(),
            step = step.toFloat(),
            unit = suffix,
            onChange = { onChange(it.roundToInt()) },
            resetValue = reset.toFloat(),
            valueText = if (value == 0 && range.first == 0 && suffix == "Hz") "off" else "$value $suffix",
        )
    }
}

@Composable
private fun FloatSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    reset: Float,
    caption: String? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        if (caption != null) Caption("$title · $caption")
        PrecisionSlider(
            label = title,
            value = value,
            range = range,
            step = step,
            unit = unit,
            onChange = { onChange((it * 10f).roundToInt() / 10f) },
            resetValue = reset,
        )
    }
}

/** Slider on a logarithmic scale (frequency, Q). */
@Composable
private fun LogSlider(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    reset: Float,
    onChange: (Float) -> Unit,
) {
    val shown = if (unit == "Hz") "${hzText(value)} Hz" else String.format(Locale.ROOT, "%.2f", value)
    PrecisionSlider(
        label = title,
        value = ln(value.coerceIn(min, max)),
        range = ln(min)..ln(max),
        step = 0.05f,
        unit = unit,
        onChange = { onChange(exp(it)) },
        resetValue = ln(reset),
        valueText = shown,
    )
}

@Composable
private fun RouteLed(route: JhankarRouteState) {
    Row(Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm)) {
        StatusLed(
            text = if (route.connected) "${route.kind.label} connected" else "Phone speaker",
            tone = if (route.connected) StatusTone.Live else StatusTone.Neutral,
        )
    }
}

/** The EQ response the audio thread applies, with the band points. */
@Composable
private fun EqPanel(p: JhankarParameters, sampleRate: Int, active: Boolean) {
    val freqs = remember { DoubleArray(96) { 20.0 * (1000.0).pow(it / 95.0) } }
    val curve = remember(p, sampleRate) { JhankarCurve.responseDb(p, freqs, sampleRate) }
    val line = if (active) VortexColors.Accent else VortexColors.TextSecondary
    val range = 15f
    Column(
        Modifier
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.xs)
            .clip(RoundedCornerShape(16.dp))
            .background(VortexColors.Card)
            .border(1.dp, VortexColors.Divider, RoundedCornerShape(16.dp))
            .padding(VortexSpacing.md),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = if (active) "Jhankar equalizer curve" else "Jhankar equalizer curve, not applied" },
        ) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            for (i in 0..4) {
                drawLine(VortexColors.Divider.copy(alpha = if (i == 2) 0.9f else 0.35f), Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
            }
            val path = Path()
            val fill = Path()
            for (i in curve.indices) {
                val x = w * i / (curve.size - 1)
                val y = mid - (curve[i].toFloat().coerceIn(-range, range) / range) * (mid - 2.dp.toPx())
                if (i == 0) { path.moveTo(x, y); fill.moveTo(x, mid); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(w, mid)
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.30f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val span = ln(1000.0)
            p.bands.forEach { b ->
                val x = (w * ln(b.freqHz / 20.0) / span).toFloat()
                val y = mid - (b.gainDb.coerceIn(-range, range) / range) * (mid - 2.dp.toPx())
                drawCircle(VortexColors.TextPrimary, 3.dp.toPx(), Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("20 Hz", style = InstrumentText.readoutSmall)
            Spacer(Modifier.weight(1f))
            Text("±${range.toInt()} dB", style = InstrumentText.readoutSmall)
            Spacer(Modifier.weight(1f))
            Text("20 kHz", style = InstrumentText.readoutSmall)
        }
    }
}

/** Real FFT spectrum of the output: 48 log bands, 20 Hz to 20 kHz. */
@Composable
private fun Spectrum(bus: com.vortexx.player.audio.dsp.SpectrumBus) {
    val analyzer = remember { SpectrumAnalyzer() }
    val raw = remember { FloatArray(analyzer.bands) }
    val shown = remember { FloatArray(analyzer.bands) { FLOOR } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(bus) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last >= 45_000_000L) {
                    val dt = if (last == 0L) 0.05f else (now - last) / 1e9f
                    last = now
                    analyzer.analyze(bus, raw)
                    for (i in shown.indices) shown[i] = max(raw[i], shown[i] - 40f * dt)
                    tick++
                }
            }
        }
    }
    Canvas(
        Modifier
            .padding(horizontal = VortexSpacing.lg)
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(VortexColors.Card)
            .semantics { contentDescription = "Output spectrum analyzer" },
    ) {
        if (tick < 0) return@Canvas
        val n = shown.size
        val slot = size.width / n
        for (i in 0 until n) {
            val f = ((shown[i] - FLOOR) / -FLOOR).coerceIn(0f, 1f)
            val hh = f * size.height
            drawRect(
                if (shown[i] > -6f) VortexColors.AccentActive else VortexColors.Accent.copy(alpha = 0.85f),
                topLeft = Offset(i * slot + 1f, size.height - hh),
                size = Size(slot - 2f, hh),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = VortexSpacing.lg, vertical = 2.dp)) {
        Text("20 Hz", style = InstrumentText.readoutSmall)
        Spacer(Modifier.weight(1f))
        Text("1 kHz", style = InstrumentText.readoutSmall)
        Spacer(Modifier.weight(1f))
        Text("20 kHz", style = InstrumentText.readoutSmall)
    }
}

private const val FLOOR = -80f

@Composable
private fun EngineReadout(vm: JhankarViewModel) {
    val reading = remember { JhankarReading() }
    var bass by remember { mutableFloatStateOf(0f) }
    var transient by remember { mutableFloatStateOf(0f) }
    var comp by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(vm) {
        while (true) {
            kotlinx.coroutines.delay(150)
            vm.jhankarMeters.drain(reading)
            bass = reading.bassBoostDb
            transient = reading.transientDb
            comp = reading.compressionDb
        }
    }
    ReadoutGrid(
        listOf(
            "Dynamic bass" to "${formatDb(bass)} dB added",
            "Transient punch" to "${formatDb(transient)} dB",
            "Compressor" to "${formatDb(comp, signed = false)} dB reduction",
            "Limiter ceiling" to "-0.5 dBFS",
        ),
    )
}

@Composable
private fun PresetLibrary(
    current: JhankarParameters,
    user: List<JhankarPreset>,
    onApply: (JhankarPreset) -> Unit,
    onSave: (String) -> PresetResult,
    onRename: (String, String) -> PresetResult,
    onDelete: (String) -> Unit,
) {
    val selected = remember(current, user) { JhankarPresets.matching(current, user) }
    val mine = selected?.takeIf { !it.builtIn }
    var naming by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.padding(horizontal = VortexSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(selected?.name ?: "Custom sound", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (mine != null) {
                TextButton(onClick = { renaming = true }) { Text("Rename", style = InstrumentText.readoutSmall) }
                TextButton(onClick = { deleting = true }) { Text("Delete", style = InstrumentText.readoutSmall) }
            }
        }
        if (selected != null) Note(selected.note)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = VortexSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
        ) {
            (JhankarPresets.BUILT_IN + user).forEach { preset ->
                PillChip(preset.name, selected = preset == selected, onClick = { onApply(preset) })
            }
            PillChip("Save mine", selected = false, onClick = { naming = true }, icon = Icons.Rounded.Add)
        }
        Note("A preset sets the equalizer, bass, vocal, stereo, dynamics and output gain of the output you are tuning. Saved by you: ${user.size} of ${JhankarPresets.MAX_USER_PRESETS}.")
    }
    if (naming) {
        NameDialog("Save this sound", "Save", "", { JhankarPresets.saveOutcome(user, it) }, { if (onSave(it).done) naming = false }, { naming = false })
    }
    if (renaming && mine != null) {
        NameDialog("Rename preset", "Rename", mine.name, { JhankarPresets.renameOutcome(user, mine.name, it) }, { if (onRename(mine.name, it).done) renaming = false }, { renaming = false })
    }
    if (deleting && mine != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = VortexColors.Surface,
            title = { Text("Delete \"${mine.name}\"?") },
            text = { Text("The saved sound is removed. The sound you hear now stays as it is.") },
            confirmButton = { TextButton(onClick = { onDelete(mine.name); deleting = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    outcome: (String) -> PresetResult,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val clean = JhankarPresets.cleanName(name)
    val result = if (clean.isEmpty()) PresetResult.BadName else outcome(clean)
    val message: String? = when {
        clean.isEmpty() -> null
        result == PresetResult.BadName -> "That name belongs to a built-in preset"
        result == PresetResult.NameTaken -> "Another preset already has that name"
        result == PresetResult.Full -> "All ${JhankarPresets.MAX_USER_PRESETS} places are used. Delete one first."
        result == PresetResult.Overwritten -> "Replaces the saved sound that has this name"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexColors.Surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(JhankarPresets.MAX_NAME_LENGTH) },
                singleLine = true,
                label = { Text("Preset name") },
                isError = !result.done && message != null,
                supportingText = { if (message != null) Text(message) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VortexColors.Accent,
                    focusedLabelColor = VortexColors.AccentActive,
                    cursorColor = VortexColors.AccentActive,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clean) }, enabled = result.done) {
                Text(if (result == PresetResult.Overwritten) "Replace" else confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Entry card for the You screen. */
@Composable
fun JhankarEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier, viewModel: JhankarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(VortexColors.Card)
            .border(1.dp, if (state.engineOn) VortexColors.Accent.copy(alpha = 0.5f) else VortexColors.Divider, shape)
            .clickable(role = Role.Button, onClickLabel = "Open Jhankar Sound Studio", onClick = onClick)
            .padding(VortexSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(VortexColors.AccentContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = VortexColors.Accent)
        }
        Spacer(Modifier.width(VortexSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("Jhankar Sound Studio", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.engineOn) "ON · ${state.profile.label}" else "OFF · tower, party speaker and headphone tuning",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.engineOn) VortexColors.Success else VortexColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}
