package com.vortexx.player.feature.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vortexx.player.audio.dsp.BassTone
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.DynamicsMeters
import com.vortexx.player.audio.dsp.DynamicsReading
import com.vortexx.player.audio.dsp.SoundPreset
import com.vortexx.player.audio.dsp.SoundPresets
import com.vortexx.player.ui.designsystem.components.PillChip
import com.vortexx.player.ui.designsystem.components.ToggleRow
import com.vortexx.player.ui.designsystem.instrument.InstrumentText
import com.vortexx.player.ui.designsystem.instrument.PanelLabel
import com.vortexx.player.ui.designsystem.instrument.PrecisionSlider
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Preset strip: the built-in sounds, the user's own, and "Save mine". The
 * highlighted chip is the preset whose settings equal the current ones; with
 * no match the sound is shown as custom.
 */
@Composable
fun PresetPanel(
    parameters: DspParameters,
    userPresets: List<SoundPreset>,
    onApply: (SoundPreset) -> Unit,
    onSave: (String) -> Boolean,
    onDelete: (String) -> Unit,
) {
    val selected = remember(parameters, userPresets) { SoundPresets.matching(parameters, userPresets) }
    var naming by rememberSaveable { mutableStateOf(false) }
    Column {
        PanelLabel("Sound presets") {
            if (selected != null && !selected.builtIn) {
                TextButton(onClick = { onDelete(selected.name) }) {
                    Text("Delete \"${selected.name}\"", style = InstrumentText.readoutSmall)
                }
            } else if (selected == null) {
                Text("custom", style = InstrumentText.readoutSmall)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = VortexSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
        ) {
            (SoundPresets.BUILT_IN + userPresets).forEach { preset ->
                PillChip(preset.name, selected = preset == selected, onClick = { onApply(preset) })
            }
            PillChip("Save mine", selected = false, onClick = { naming = true }, icon = Icons.Rounded.Add)
        }
        Hint("A preset sets preamp, EQ, bass, compressor, limiter and width together. Studio Flat changes nothing.")
    }
    if (naming) {
        PresetNameDialog(
            onConfirm = { name -> if (onSave(name)) naming = false },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun PresetNameDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val clean = SoundPresets.cleanName(name)
    val usable = SoundPresets.isUsableName(clean)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexColors.Surface,
        title = { Text("Save this sound") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(SoundPresets.MAX_NAME_LENGTH) },
                singleLine = true,
                label = { Text("Preset name") },
                isError = clean.isNotEmpty() && !usable,
                supportingText = { if (clean.isNotEmpty() && !usable) Text("That name belongs to a built-in preset") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VortexColors.Accent,
                    focusedLabelColor = VortexColors.AccentActive,
                    cursorColor = VortexColors.AccentActive,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(clean) }, enabled = usable) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Bass shelf, kick band and speaker protect. */
@Composable
fun BassPanel(
    parameters: DspParameters,
    onBass: (Float) -> Unit,
    onPunch: (Float) -> Unit,
    onProtect: (Boolean) -> Unit,
) {
    Column {
        PanelLabel("Bass")
        Caption("Bass boost · everything below ${BassTone.SHELF_HZ.toInt()} Hz")
        PrecisionSlider(
            label = "Bass boost",
            value = parameters.bassDb,
            range = 0f..BassTone.MAX_BASS_DB,
            step = 0.5f,
            unit = "dB",
            onChange = onBass,
        )
        Caption("Kick punch · the drum hit around ${BassTone.PUNCH_HZ.toInt()} Hz")
        PrecisionSlider(
            label = "Kick punch",
            value = parameters.punchDb,
            range = 0f..BassTone.MAX_PUNCH_DB,
            step = 0.5f,
            unit = "dB",
            onChange = onPunch,
        )
        ToggleRow(
            title = "Phone speaker protect",
            summary = "Removes bass below ${BassTone.PROTECT_HZ.toInt()} Hz. A phone speaker cannot play it; it only rattles. Switch off for earphones and big speakers.",
            checked = parameters.speakerProtect,
            onCheckedChange = onProtect,
        )
    }
}

/** Compressor amount, limiter switch and what both are doing right now. */
@Composable
fun DynamicsPanel(
    parameters: DspParameters,
    meters: DynamicsMeters,
    onCompressor: (Int) -> Unit,
    onLimiter: (Boolean) -> Unit,
) {
    Column {
        PanelLabel("Compressor and limiter")
        Caption("Compressor · 3 bands · 0 % is off")
        PrecisionSlider(
            label = "Compressor",
            value = parameters.compressor.toFloat(),
            range = 0f..100f,
            step = 5f,
            unit = "%",
            onChange = { onCompressor(it.roundToInt()) },
            valueText = "${parameters.compressor} %",
        )
        ToggleRow(
            title = "Limiter · ceiling −0.5 dBFS",
            summary = "Turns the volume down for an instant when a boost would clip, so loud bass stays clean. Looks 5 ms ahead, so the sound is 5 ms later.",
            checked = parameters.limiter,
            onCheckedChange = onLimiter,
        )
        ReductionMeters(meters)
        Hint("The bars show how much each part is turning the sound down right now. Long bars all the time mean the boost is higher than the output can carry: lower the preamp.")
    }
}

/** Stereo width for two-channel music. */
@Composable
fun WidthPanel(parameters: DspParameters, onWidth: (Int) -> Unit) {
    Column {
        PanelLabel("Stereo width")
        PrecisionSlider(
            label = "Stereo width",
            value = parameters.widthPercent.toFloat(),
            range = 0f..BassTone.MAX_WIDTH_PERCENT.toFloat(),
            step = 10f,
            unit = "%",
            onChange = { onWidth(it.roundToInt()) },
            resetValue = 100f,
            valueText = "${parameters.widthPercent} %",
        )
        Hint("100 % is as recorded, 0 % is mono. Works on stereo music; mono and surround streams are left as they are.")
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = InstrumentText.readoutSmall,
        modifier = Modifier.padding(start = VortexSpacing.lg, end = VortexSpacing.lg, top = VortexSpacing.xs),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = VortexColors.TextSecondary,
        modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
    )
}

// ---------------------------------------------------------------- gain-reduction meters

/** What the reduction meters show, in dB (0 = untouched, negative = turned down). */
@Stable
class ReductionDisplayState {
    var lowDb by mutableFloatStateOf(0f)
    var midDb by mutableFloatStateOf(0f)
    var highDb by mutableFloatStateOf(0f)
    var limiterDb by mutableFloatStateOf(0f)

    internal fun update(reading: DynamicsReading, dtSeconds: Float) {
        val rise = RETURN_DB_PER_SECOND * dtSeconds
        lowDb = ballistic(lowDb, toDb(reading.lowGain), rise)
        midDb = ballistic(midDb, toDb(reading.midGain), rise)
        highDb = ballistic(highDb, toDb(reading.highGain), rise)
        limiterDb = ballistic(limiterDb, toDb(reading.limiterGain), rise)
    }

    /** More reduction shows at once; the bar returns at a readable speed. */
    private fun ballistic(shown: Float, incoming: Float, rise: Float): Float =
        if (incoming <= shown) incoming else min(incoming, shown + rise)

    companion object {
        const val RANGE_DB = 18f
        const val RETURN_DB_PER_SECOND = 20f

        fun toDb(gain: Float): Float = if (gain >= 1f) 0f else max(-RANGE_DB * 2f, 20f * log10(max(gain, 1e-6f)))
    }
}

@Composable
private fun rememberReductionDisplay(bus: DynamicsMeters): ReductionDisplayState {
    val state = remember(bus) { ReductionDisplayState() }
    val reading = remember(bus) { DynamicsReading() }
    LaunchedEffect(bus) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                bus.drain(reading)
                val dt = if (lastFrame == 0L) 0f else ((frameNanos - lastFrame) / 1_000_000_000.0).toFloat()
                lastFrame = frameNanos
                state.update(reading, dt.coerceIn(0f, 0.25f))
            }
        }
    }
    return state
}

@Composable
private fun ReductionMeters(bus: DynamicsMeters) {
    val state = rememberReductionDisplay(bus)
    Column(Modifier.padding(vertical = VortexSpacing.xs)) {
        ReductionRow("Bass", state.lowDb)
        ReductionRow("Middle", state.midDb)
        ReductionRow("Treble", state.highDb)
        ReductionRow("Limiter", state.limiterDb)
    }
}

@Composable
private fun ReductionRow(label: String, reductionDb: Float) {
    val readout = String.format(Locale.ROOT, "%.1f dB", reductionDb)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = VortexSpacing.lg, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label turned down by $readout" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = InstrumentText.readoutSmall, modifier = Modifier.width(60.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(8.dp),
        ) {
            drawRect(VortexColors.Card)
            val fraction = (-reductionDb / ReductionDisplayState.RANGE_DB).coerceIn(0f, 1f)
            if (fraction > 0f) drawRect(VortexColors.Accent, size = Size(size.width * fraction, size.height))
        }
        Text(readout, style = InstrumentText.readoutSmall, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
    }
}
