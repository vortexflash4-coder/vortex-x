package com.vortexx.player.audio.dsp

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lock-free hand-over of level data from the audio thread to the UI.
 * The audio thread only does compare-and-set loops on atomics (no locks, no
 * allocation); the UI drains the accumulated values once per frame.
 * Fields are drained one by one, so a reading may mix two adjacent audio
 * buffers; that is invisible on a meter.
 */
class MeterBus {
    private val peakBits = arrayOf(AtomicInteger(0), AtomicInteger(0))
    private val sumSquaresBits = arrayOf(AtomicLong(0), AtomicLong(0))
    private val frames = AtomicLong(0)
    private val clippedTotal = AtomicLong(0)
    private val clippedSinceDrain = AtomicLong(0)

    @Volatile
    var lastPublishNanos: Long = 0L
        private set

    /** Audio thread. [channel] is 0 (left) or 1 (right). */
    fun publish(
        peakLeft: Float,
        peakRight: Float,
        sumSquaresLeft: Double,
        sumSquaresRight: Double,
        frameCount: Int,
        clippedSamples: Int,
        nowNanos: Long,
    ) {
        maxFloat(peakBits[0], peakLeft)
        maxFloat(peakBits[1], peakRight)
        addDouble(sumSquaresBits[0], sumSquaresLeft)
        addDouble(sumSquaresBits[1], sumSquaresRight)
        frames.addAndGet(frameCount.toLong())
        if (clippedSamples > 0) {
            clippedTotal.addAndGet(clippedSamples.toLong())
            clippedSinceDrain.addAndGet(clippedSamples.toLong())
        }
        lastPublishNanos = nowNanos
    }

    /** UI thread: moves the accumulated values into [out] and resets them. */
    fun drain(out: MeterReading, nowNanos: Long) {
        val n = frames.getAndSet(0)
        out.peakLeft = Float.fromBits(peakBits[0].getAndSet(0))
        out.peakRight = Float.fromBits(peakBits[1].getAndSet(0))
        val sqL = Double.fromBits(sumSquaresBits[0].getAndSet(0))
        val sqR = Double.fromBits(sumSquaresBits[1].getAndSet(0))
        out.rmsLeft = if (n > 0) sqrt(sqL / n).toFloat() else 0f
        out.rmsRight = if (n > 0) sqrt(sqR / n).toFloat() else 0f
        out.framesSinceLast = n
        out.clippedSinceLast = clippedSinceDrain.getAndSet(0)
        out.clippedTotal = clippedTotal.get()
        out.signalPresent = n > 0 || nowNanos - lastPublishNanos < STALE_NANOS
    }

    fun resetClipCounter() {
        clippedTotal.set(0)
        clippedSinceDrain.set(0)
    }

    private fun maxFloat(target: AtomicInteger, value: Float) {
        while (true) {
            val current = target.get()
            val next = max(Float.fromBits(current), value)
            if (target.compareAndSet(current, next.toRawBits())) return
        }
    }

    private fun addDouble(target: AtomicLong, value: Double) {
        while (true) {
            val current = target.get()
            val next = Double.fromBits(current) + value
            if (target.compareAndSet(current, next.toRawBits())) return
        }
    }

    private companion object {
        const val STALE_NANOS = 250_000_000L
    }
}

/** Reusable, mutable reading filled by [MeterBus.drain] (no allocation per frame). */
class MeterReading {
    var peakLeft = 0f
    var peakRight = 0f
    var rmsLeft = 0f
    var rmsRight = 0f
    var framesSinceLast = 0L
    var clippedSinceLast = 0L
    var clippedTotal = 0L
    var signalPresent = false
}

/**
 * Processing-load measurement: time spent in the DSP versus the duration of
 * the audio it processed. Audio thread records, UI drains.
 */
class LoadMeter {
    private val busyNanos = AtomicLong(0)
    private val audioNanos = AtomicLong(0)
    private val worstPerMille = AtomicInteger(0)

    fun record(elapsedNanos: Long, frames: Int, sampleRate: Int) {
        if (frames <= 0 || sampleRate <= 0) return
        val audio = frames * 1_000_000_000L / sampleRate
        busyNanos.addAndGet(elapsedNanos)
        audioNanos.addAndGet(audio)
        val perMille = if (audio > 0) (elapsedNanos * 1000 / audio).toInt() else 0
        while (true) {
            val current = worstPerMille.get()
            if (perMille <= current || worstPerMille.compareAndSet(current, perMille)) return
        }
    }

    /** Average and worst-buffer load since the last drain, as percentages; null when nothing ran. */
    fun drain(): LoadReading? {
        val busy = busyNanos.getAndSet(0)
        val audio = audioNanos.getAndSet(0)
        val worst = worstPerMille.getAndSet(0)
        if (audio <= 0) return null
        return LoadReading(averagePercent = busy * 100.0 / audio, worstBufferPercent = worst / 10.0)
    }
}

data class LoadReading(val averagePercent: Double, val worstBufferPercent: Double)
