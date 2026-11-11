package com.vortexx.player

import com.vortexx.player.audio.dsp.DspChain
import com.vortexx.player.audio.dsp.DspParameters
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DspHeadroomTest {
    @Test
    fun overlappingBoostsNeedMoreThanTheLargestIndividualBand() {
        val hot = DspParameters(bandGainsDb = List(10) { 12f })
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            val headroom = hot.recommendedPreampDb(rate)
            assertTrue("rate=$rate headroom=$headroom", headroom < -13f)
            println("Auto headroom all ten +12 dB at $rate Hz: $headroom dB")
            val safe = hot.withPreamp(headroom)
            for (hz in doubleArrayOf(31.5, 88.0, 177.0, 353.0, 707.0, 1414.0, 2828.0, 5656.0, 11000.0, 16000.0)) {
                val chain = DspChain()
                chain.configure(rate, 1)
                chain.apply(safe)
                val samples = FloatArray(rate) { (0.999 * sin(2 * PI * hz * it / rate)).toFloat() }
                assertEquals("rate=$rate hz=$hz headroom=$headroom", 0, chain.process(samples, rate))
            }
        }
    }

    @Test
    fun adjacentBoostsOldRuleClipsAndCombinedHeadroomPreventsIt() {
        val hot = DspParameters().withBand(4, 12f).withBand(5, 12f).withBand(6, 12f)
        fun clips(params: DspParameters): Int {
            val chain = DspChain()
            chain.configure(48_000, 1)
            chain.apply(params)
            val data = FloatArray(48_000) { (0.99 * sin(2 * PI * 1000 * it / 48_000)).toFloat() }
            return chain.process(data, data.size)
        }
        assertTrue(clips(hot.withPreamp(-hot.maxBoostDb)) > 0)
        assertEquals(0, clips(hot.withPreamp(hot.recommendedPreampDb())))
    }

    @Test
    fun sampleCeilingContainsBurstsAndGainChangesWithMultipleBoosts() {
        val chain = DspChain()
        chain.configure(48_000, 2)
        val allBoosts = DspParameters(preampDb = 12f, bandGainsDb = List(10) { 12f })
        chain.apply(DspParameters())
        var clipped = 0
        for (block in 0 until 100) {
            if (block == 10) chain.apply(allBoosts)
            if (block == 60) chain.apply(allBoosts.withPreamp(allBoosts.recommendedPreampDb()))
            val buffer = FloatArray(1024 * 2) { sample ->
                val frame = block * 1024 + sample / 2
                if (frame % 137 < 4) (if (sample % 2 == 0) 1f else -1f)
                else (0.5 * sin(2 * PI * 707 * frame / 48_000) + 0.49 * sin(2 * PI * 1414 * frame / 48_000)).toFloat()
            }
            clipped += chain.process(buffer, 1024)
            assertTrue(buffer.all { it.isFinite() && it in -1f..1f })
        }
        assertTrue("safety ceiling must see transient overs", clipped > 0)
    }

    @Test
    fun flatCutsAndUnusableBoostNeedNoHeadroom() {
        assertEquals(0f, DspParameters().recommendedPreampDb(), 0f)
        assertEquals(0f, DspParameters().withBand(5, -12f).recommendedPreampDb(), 0f)
        assertEquals(0f, DspParameters().withBand(9, 12f).recommendedPreampDb(22_050), 0f)
        val stored = DspParameters().withPreamp(-30f)
        assertEquals(-30f, DspParameters.clampPreamp(stored.preampDb), 0f)
    }
}
