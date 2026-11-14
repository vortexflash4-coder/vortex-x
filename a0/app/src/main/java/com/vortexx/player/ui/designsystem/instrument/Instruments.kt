package com.vortexx.player.ui.designsystem.instrument

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortexx.player.audio.dsp.MeterBus
import com.vortexx.player.audio.dsp.GraphicEqBands
import com.vortexx.player.audio.dsp.MeterReading
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** Instrument-panel styles: monospace with tabular digits so changing numbers do not jitter. */
object InstrumentText {
    val readout = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 13.sp, color = VortexColors.TextPrimary)
    val readoutSmall = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 11.sp, color = VortexColors.TextSecondary)
    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = VortexColors.TextSecondary)
}

fun formatDb(db: Float, signed: Boolean = true): String =
    if (signed) String.format(Locale.ROOT, "%+.1f", db) else String.format(Locale.ROOT, "%.1f", db)

/** Uppercase section label with a crimson tick, used between instrument blocks. */
@Composable
fun PanelLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 2.dp, height = 12.dp)
                .background(VortexColors.Accent),
        )
        Spacer(Modifier.width(VortexSpacing.sm))
        Text(text.uppercase(Locale.ROOT), style = InstrumentText.label, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ---------------------------------------------------------------- meters

/** Display ballistics for a stereo meter, updated once per frame from the [MeterBus]. */
@Stable
class MeterDisplayState {
    var peakLeftDb by mutableFloatStateOf(FLOOR_DB)
    var peakRightDb by mutableFloatStateOf(FLOOR_DB)
    var rmsLeftDb by mutableFloatStateOf(FLOOR_DB)
    var rmsRightDb by mutableFloatStateOf(FLOOR_DB)
    var holdLeftDb by mutableFloatStateOf(FLOOR_DB)
    var holdRightDb by mutableFloatStateOf(FLOOR_DB)
    var clipLatched by mutableStateOf(false)
    var clippedTotal by mutableLongStateOf(0L)
    var signalPresent by mutableStateOf(false)

    private var holdLeftAge = 0f
    private var holdRightAge = 0f

    internal fun update(reading: MeterReading, dtSeconds: Float) {
        val decay = PEAK_FALL_DB_PER_SECOND * dtSeconds
        peakLeftDb = ballistic(peakLeftDb, toDb(reading.peakLeft), decay, reading.framesSinceLast)
        peakRightDb = ballistic(peakRightDb, toDb(reading.peakRight), decay, reading.framesSinceLast)
        rmsLeftDb = ballistic(rmsLeftDb, toDb(reading.rmsLeft), decay, reading.framesSinceLast)
        rmsRightDb = ballistic(rmsRightDb, toDb(reading.rmsRight), decay, reading.framesSinceLast)

        holdLeftAge += dtSeconds
        holdRightAge += dtSeconds
        if (peakLeftDb >= holdLeftDb) {
            holdLeftDb = peakLeftDb
            holdLeftAge = 0f
        } else if (holdLeftAge > HOLD_SECONDS) {
            holdLeftDb = max(peakLeftDb, holdLeftDb - decay)
        }
        if (peakRightDb >= holdRightDb) {
            holdRightDb = peakRightDb
            holdRightAge = 0f
        } else if (holdRightAge > HOLD_SECONDS) {
            holdRightDb = max(peakRightDb, holdRightDb - decay)
        }
        if (reading.clippedSinceLast > 0) clipLatched = true
        clippedTotal = reading.clippedTotal
        signalPresent = reading.signalPresent
    }

    private fun ballistic(displayed: Float, incoming: Float, decay: Float, frames: Long): Float {
        val target = if (frames > 0) incoming else FLOOR_DB
        return if (target >= displayed) target else max(target, displayed - decay)
    }

    companion object {
        const val FLOOR_DB = -72f
        const val PEAK_FALL_DB_PER_SECOND = 24f
        const val HOLD_SECONDS = 1.5f

        fun toDb(linear: Float): Float = if (linear <= 0f) FLOOR_DB else max(FLOOR_DB, 20f * log10(linear))
    }
}

/** Drains [bus] every display frame while composed. */
@Composable
fun rememberMeterDisplay(bus: MeterBus): MeterDisplayState {
    val state = remember(bus) { MeterDisplayState() }
    val reading = remember(bus) { MeterReading() }
    LaunchedEffect(bus) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                bus.drain(reading, System.nanoTime())
                val dt = if (lastFrame == 0L) 0f else ((frameNanos - lastFrame) / 1_000_000_000.0).toFloat()
                lastFrame = frameNanos
                state.update(reading, dt.coerceIn(0f, 0.25f))
            }
        }
    }
    return state
}

private const val METER_MIN_DB = -60f
private const val METER_HOT_DB = -6f

private fun meterFraction(db: Float): Float = ((db - METER_MIN_DB) / -METER_MIN_DB).coerceIn(0f, 1f)

/**
 * Stereo output meter: RMS bar, peak bar, 1.5 s peak hold, and a latching clip LED
 * (tap the LED to clear it). White up to −6 dBFS, crimson above.
 */
@Composable
fun StereoLevelMeter(state: MeterDisplayState, modifier: Modifier = Modifier, onClearClip: () -> Unit = {}) {
    Column(modifier.padding(horizontal = VortexSpacing.lg)) {
        MeterRow("L", state, left = true, onClearClip = onClearClip)
        Spacer(Modifier.height(6.dp))
        MeterRow("R", state, left = false, onClearClip = onClearClip)
        Spacer(Modifier.height(4.dp))
        MeterScale()
    }
}

@Composable
private fun MeterRow(channel: String, state: MeterDisplayState, left: Boolean, onClearClip: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(channel, style = InstrumentText.readoutSmall, modifier = Modifier.width(16.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(14.dp)
                .semantics {
                    val peak = if (left) state.peakLeftDb else state.peakRightDb
                    contentDescription = "$channel channel level"
                    stateDescription = if (peak <= METER_MIN_DB) "silent" else "peak ${peak.roundToInt()} dB full scale"
                },
        ) {
            val rms = if (left) state.rmsLeftDb else state.rmsRightDb
            val peak = if (left) state.peakLeftDb else state.peakRightDb
            val hold = if (left) state.holdLeftDb else state.holdRightDb
            val w = size.width
            val h = size.height
            val hotX = meterFraction(METER_HOT_DB) * w
            drawRoundRect(VortexColors.Card, cornerRadius = CornerRadius(3f, 3f))
            // Peak (dim) then RMS (bright), each split at the hot threshold.
            drawLevel(meterFraction(peak) * w, hotX, h, VortexColors.TextPrimary.copy(alpha = 0.28f), VortexColors.Accent.copy(alpha = 0.45f))
            drawLevel(meterFraction(rms) * w, hotX, h, VortexColors.TextPrimary.copy(alpha = 0.92f), VortexColors.AccentActive)
            if (hold > METER_MIN_DB) {
                val x = meterFraction(hold) * w
                drawRect(if (hold >= METER_HOT_DB) VortexColors.AccentActive else VortexColors.TextPrimary, topLeft = Offset(x - 1.5f, 0f), size = Size(3f, h))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (state.clipLatched) Color(0xFFFF1F3D) else VortexColors.Card)
                .border(1.dp, VortexColors.Divider, CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Clear clip indicator") {
                    state.clipLatched = false
                    onClearClip()
                }
                .semantics { contentDescription = if (state.clipLatched) "Clipping detected" else "No clipping" },
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLevel(x: Float, hotX: Float, h: Float, cool: Color, hot: Color) {
    if (x <= 0f) return
    drawRect(cool, topLeft = Offset(0f, 0f), size = Size(minOf(x, hotX), h))
    if (x > hotX) drawRect(hot, topLeft = Offset(hotX, 0f), size = Size(x - hotX, h))
}

@Composable
private fun MeterScale() {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp)) {
        // −60, −48, −36, −24, −12, 0 dBFS are evenly spaced on this linear-dB scale.
        listOf("-60", "-48", "-36", "-24", "-12", "0").forEachIndexed { index, text ->
            Text(
                text,
                style = InstrumentText.readoutSmall.copy(fontSize = 9.sp),
                textAlign = when (index) {
                    0 -> TextAlign.Start
                    5 -> TextAlign.End
                    else -> TextAlign.Center
                },
                modifier = Modifier.weight(if (index == 0 || index == 5) 0.5f else 1f),
            )
        }
    }
}

// ---------------------------------------------------------------- faders

/** Fixed 56 dp controls stay distinct on compact phones; the bank scrolls sideways. */
@Composable
fun EqFaderBank(
    bandGainsDb: List<Float>,
    sampleRate: Int,
    onChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = VortexSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GraphicEqBands.LABELS.forEachIndexed { index, label ->
            EqFader(
                label = label,
                gainDb = bandGainsDb[index],
                usable = sampleRate == 0 || GraphicEqBands.isUsable(index, sampleRate),
                onChange = { onChange(index, it) },
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

/**
 * Vertical EQ fader. Drag to change, double-tap to reset to 0 dB. TalkBack
 * sees a range control (−[maxDb]…+[maxDb]) and can set the value directly.
 */
@Composable
fun EqFader(
    label: String,
    gainDb: Float,
    usable: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    maxDb: Float = 12f,
    trackHeight: androidx.compose.ui.unit.Dp = 168.dp,
) {
    val current by rememberUpdatedState(gainDb)
    val change by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (usable) formatDb(gainDb) else "off",
            style = InstrumentText.readoutSmall.copy(
                fontSize = 12.sp,
                color = when {
                    !usable -> VortexColors.TextSecondary.copy(alpha = 0.5f)
                    gainDb != 0f -> VortexColors.AccentActive
                    else -> VortexColors.TextSecondary
                },
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .semantics {
                    contentDescription = "$label hertz band"
                    stateDescription = if (usable) "${formatDb(gainDb)} decibels" else "not available at this sample rate"
                    progressBarRangeInfo = ProgressBarRangeInfo(gainDb, -maxDb..maxDb)
                    if (usable) {
                        setProgress { value ->
                            change(value.coerceIn(-maxDb, maxDb))
                            true
                        }
                    } else disabled()
                }
                .pointerInput(usable) {
                    if (!usable) return@pointerInput
                    var dragValue = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragValue = current },
                        onVerticalDrag = { pointer, amount ->
                            pointer.consume()
                            dragValue = (dragValue - amount / size.height * (2 * maxDb)).coerceIn(-maxDb, maxDb)
                            change(dragValue)
                        },
                    )
                }
                .pointerInput(usable) {
                    if (!usable) return@pointerInput
                    detectTapGestures(onDoubleTap = { change(0f) })
                }
                .drawBehind {
                    val cx = size.width / 2f
                    val h = size.height
                    val zeroY = h / 2f
                    val valueY = zeroY - (gainDb / maxDb) * (h / 2f)
                    // Track and centre (0 dB) line.
                    drawRect(VortexColors.Card, topLeft = Offset(cx - 2f, 0f), size = Size(4f, h))
                    drawRect(VortexColors.Divider, topLeft = Offset(cx - 9f, zeroY - 0.5f), size = Size(18f, 1f))
                    if (usable && gainDb != 0f) {
                        val top = minOf(zeroY, valueY)
                        drawRect(VortexColors.Accent, topLeft = Offset(cx - 2f, top), size = Size(4f, kotlin.math.abs(valueY - zeroY)))
                    }
                    // Thumb.
                    val thumbW = minOf(size.width - 6f, 34f)
                    drawRoundRect(
                        color = if (usable) VortexColors.TextPrimary else VortexColors.Divider,
                        topLeft = Offset(cx - thumbW / 2f, valueY - 5f),
                        size = Size(thumbW, 10f),
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                    drawRect(VortexColors.Background, topLeft = Offset(cx - thumbW / 2f + 3f, valueY - 0.75f), size = Size(thumbW - 6f, 1.5f))
                },
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = InstrumentText.readoutSmall.copy(fontSize = 12.sp), maxLines = 1)
    }
}

// ---------------------------------------------------------------- precision slider

/**
 * Horizontal precision slider with a numeric readout and ±[step] buttons.
 * Drag anywhere on the track; double-tap resets to [resetValue].
 */
@Composable
fun PrecisionSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    resetValue: Float = 0f,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    Row(modifier.padding(horizontal = VortexSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { change((current - step).coerceIn(range)) }) {
            Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label")
        }
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = "${formatDb(value)} $unit"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                    setProgress { v ->
                        change(v.coerceIn(range))
                        true
                    }
                }
                .pointerInput(Unit) {
                    var dragValue = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragValue = current },
                        onHorizontalDrag = { pointer, amount ->
                            pointer.consume()
                            dragValue = (dragValue + amount / size.width * span).coerceIn(range)
                            change(dragValue)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { change(resetValue) })
                }
                .drawBehind {
                    val cy = size.height / 2f
                    val w = size.width
                    val zeroX = ((resetValue - range.start) / span) * w
                    val x = ((value - range.start) / span) * w
                    drawRect(VortexColors.Card, topLeft = Offset(0f, cy - 2f), size = Size(w, 4f))
                    drawRect(VortexColors.Divider, topLeft = Offset(zeroX - 0.5f, cy - 9f), size = Size(1f, 18f))
                    if (value != resetValue) {
                        drawRect(VortexColors.Accent, topLeft = Offset(minOf(x, zeroX), cy - 2f), size = Size(kotlin.math.abs(x - zeroX), 4f))
                    }
                    drawRoundRect(VortexColors.TextPrimary, topLeft = Offset(x - 5f, cy - 12f), size = Size(10f, 24f), cornerRadius = CornerRadius(3f, 3f))
                },
            contentAlignment = Alignment.CenterEnd,
        ) {}
        IconButton(onClick = { change((current + step).coerceIn(range)) }) {
            Icon(Icons.Rounded.Add, contentDescription = "Increase $label")
        }
        Text("${formatDb(value)} $unit", style = InstrumentText.readout, modifier = Modifier.width(76.dp), textAlign = TextAlign.End)
    }
}

// ---------------------------------------------------------------- selectors and status

/** Two-or-more option selector drawn as a single instrument strip. */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(horizontal = VortexSpacing.lg)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, VortexColors.Divider, RoundedCornerShape(8.dp)),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(if (isSelected) VortexColors.AccentContainer else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .semantics { stateDescription = if (isSelected) "Selected" else "Not selected" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = InstrumentText.readout.copy(
                        fontFamily = FontFamily.Default,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) VortexColors.TextPrimary else VortexColors.TextSecondary,
                    ),
                )
            }
        }
    }
}

enum class StatusTone { Live, Neutral, Warning, Off }

/** Compact status indicator: LED dot plus label. */
@Composable
fun StatusLed(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val color = when (tone) {
        StatusTone.Live -> VortexColors.Success
        StatusTone.Neutral -> VortexColors.TextSecondary
        StatusTone.Warning -> VortexColors.Caution
        StatusTone.Off -> VortexColors.AccentActive
    }
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text.uppercase(Locale.ROOT), style = InstrumentText.label.copy(color = VortexColors.TextPrimary))
    }
}

/** Two-column grid of technical readouts separated by hairlines. */
@Composable
fun ReadoutGrid(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = VortexSpacing.lg)) {
        rows.forEachIndexed { index, (name, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(VortexColors.Divider.copy(alpha = 0.6f)),
                )
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Text(name.uppercase(Locale.ROOT), style = InstrumentText.label, modifier = Modifier.weight(0.38f).padding(top = 2.dp))
                Text(value, style = InstrumentText.readout, modifier = Modifier.weight(0.62f))
            }
        }
    }
}
