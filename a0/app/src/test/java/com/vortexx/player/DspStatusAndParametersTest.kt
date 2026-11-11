package com.vortexx.player

import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.GraphicEqBands
import com.vortexx.player.audio.engine.DspStatusKind
import com.vortexx.player.audio.engine.DspStatusResolver
import com.vortexx.player.audio.engine.OutputModePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Audio Lab may call the DSP "active" only when processed PCM is actually being played. */
class DspStatusAndParametersTest {

    private fun status(
        outputOpen: Boolean = true,
        pcm: Boolean = true,
        offload: Boolean = false,
        processorActive: Boolean = true,
        enabled: Boolean = true,
        mode: OutputModePreference = OutputModePreference.Dsp,
    ) = DspStatusResolver.resolve(outputOpen, pcm, offload, processorActive, null, enabled, mode)

    @Test
    fun activeOnlyForPcmOutputWithActiveProcessor() {
        assertEquals(DspStatusKind.Active, status().kind)
        assertTrue(status().isProcessing)
    }

    @Test
    fun offloadAndPassthroughAreNeverShownAsActive() {
        assertEquals(DspStatusKind.Offload, status(offload = true).kind)
        assertEquals(DspStatusKind.Passthrough, status(pcm = false).kind)
        assertFalse(status(offload = true).isProcessing)
        assertFalse(status(pcm = false).isProcessing)
        // Even with the processor reporting active, a compressed output wins.
        assertEquals(DspStatusKind.Passthrough, status(pcm = false, processorActive = true).kind)
    }

    @Test
    fun inactiveProcessorAndUserBypassAreReportedSeparately() {
        assertEquals(DspStatusKind.FormatNotSupported, status(processorActive = false).kind)
        assertEquals(DspStatusKind.BatteryMode, status(processorActive = false, mode = OutputModePreference.Offload).kind)
        assertEquals(DspStatusKind.BatteryMode, status(processorActive = true, mode = OutputModePreference.Offload).kind)
        assertEquals(DspStatusKind.BypassedByUser, status(enabled = false).kind)
        assertEquals(DspStatusKind.Idle, status(outputOpen = false).kind)
    }

    @Test
    fun bandsAreClampedRoundedAndRoundTripThroughStorage() {
        val p = DspParameters().withBand(0, 30f).withBand(1, -30f).withBand(2, 3.14159f).withPreamp(-20f)
        assertEquals(12f, p.bandGainsDb[0])
        assertEquals(-12f, p.bandGainsDb[1])
        assertEquals(3.1f, p.bandGainsDb[2])
        assertEquals(-20f, p.preampDb)
        assertEquals(p.bandGainsDb, DspParameters.parseBands(DspParameters.formatBands(p.bandGainsDb)))
        assertEquals(12f, p.maxBoostDb)
    }

    @Test
    fun invalidStoredBandsFallBackToFlat() {
        assertEquals(DspParameters.FLAT, DspParameters.parseBands(null))
        assertEquals(DspParameters.FLAT, DspParameters.parseBands("1,2,3"))
        assertEquals(DspParameters.FLAT, DspParameters.parseBands("a,b,c,d,e,f,g,h,i,j"))
        assertEquals(GraphicEqBands.COUNT, DspParameters.FLAT.size)
        assertTrue(DspParameters().isFlat)
    }
}
