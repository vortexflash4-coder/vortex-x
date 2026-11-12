package com.vortexx.player.ai.lyrics

import kotlin.math.log10
import kotlin.math.sqrt

/** One timed line of lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

/** A stretch of singing in the vocal track, in samples. */
data class Phrase(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/**
 * Finds the sung phrases in a separated vocal track by loudness, so each
 * phrase can be transcribed on its own and its start time used as the line's
 * time. Pure arithmetic on the samples; no model.
 */
object PhraseSegmenter {
    private const val FRAME_MS = 20
    private const val GAP_TO_MERGE_MS = 350
    private const val MIN_PHRASE_MS = 400
    private const val PAD_MS = 150
    private const val MAX_PHRASE_MS = 14_000

    /** Phrases of [samples] (mono, [sampleRate] Hz), in order, none longer than 14 s. */
    fun phrases(samples: FloatArray, sampleRate: Int): List<Phrase> {
        val frame = sampleRate * FRAME_MS / 1000
        val count = samples.size / frame
        if (frame <= 0 || count == 0) return emptyList()
        val level = FloatArray(count)
        for (f in 0 until count) {
            var sum = 0.0
            val base = f * frame
            for (i in 0 until frame) sum += samples[base + i] * samples[base + i]
            level[f] = (10.0 * log10(sum / frame + 1e-12)).toFloat()
        }
        // Singing is judged against the loud parts of this track, not an absolute level.
        val sorted = level.copyOf().also { it.sort() }
        val loud = sorted[(count * 0.95f).toInt().coerceAtMost(count - 1)]
        val threshold = maxOf(loud - 28f, -55f)
        if (loud < -55f) return emptyList()

        val runs = ArrayList<IntArray>()
        var start = -1
        for (f in 0 until count) {
            val voiced = level[f] > threshold
            if (voiced && start < 0) start = f
            if (!voiced && start >= 0) {
                runs.add(intArrayOf(start, f))
                start = -1
            }
        }
        if (start >= 0) runs.add(intArrayOf(start, count))

        val gap = GAP_TO_MERGE_MS / FRAME_MS
        val merged = ArrayList<IntArray>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && run[0] - last[1] <= gap) last[1] = run[1] else merged.add(run)
        }

        val pad = PAD_MS / FRAME_MS
        val minFrames = MIN_PHRASE_MS / FRAME_MS
        val maxFrames = MAX_PHRASE_MS / FRAME_MS
        val out = ArrayList<Phrase>()
        for (run in merged) {
            if (run[1] - run[0] < minFrames) continue
            split(level, run[0], run[1], maxFrames, minFrames) { from, to ->
                val a = maxOf(0, from - pad) * frame
                val b = minOf(count, to + pad) * frame
                out.add(Phrase(a, b))
            }
        }
        return out
    }

    /** Cuts [from, to) at its quietest inner frame until every piece is at most [maxFrames] long. */
    private fun split(level: FloatArray, from: Int, to: Int, maxFrames: Int, minFrames: Int, emit: (Int, Int) -> Unit) {
        if (to - from <= maxFrames) {
            emit(from, to)
            return
        }
        // Look for the cut away from the ends so no piece becomes tiny.
        val margin = maxOf(minFrames, (to - from) / 5)
        var cut = from + (to - from) / 2
        var quietest = Float.MAX_VALUE
        for (f in from + margin until to - margin) {
            if (level[f] < quietest) {
                quietest = level[f]
                cut = f
            }
        }
        split(level, from, cut, maxFrames, minFrames, emit)
        split(level, cut, to, maxFrames, minFrames, emit)
    }

    /** RMS of a phrase, for tests and diagnostics. */
    fun rms(samples: FloatArray, phrase: Phrase): Float {
        var sum = 0.0
        for (i in phrase.start until phrase.end) sum += samples[i] * samples[i]
        return sqrt(sum / maxOf(1, phrase.length)).toFloat()
    }
}

/** Reads and writes the common .lrc format: one "[mm:ss.xx] text" per line. */
object Lrc {
    private val LINE = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\](.*)$""")

    fun format(lines: List<LyricLine>, language: String? = null, by: String? = null): String = buildString {
        if (by != null) append("[by:").append(by).append("]\n")
        if (language != null) append("[la:").append(language).append("]\n")
        for (line in lines) {
            val total = maxOf(0L, line.timeMs)
            val minutes = total / 60_000
            val seconds = total / 1000 % 60
            val hundredths = total % 1000 / 10
            append('[')
            append(minutes.toString().padStart(2, '0')).append(':')
            append(seconds.toString().padStart(2, '0')).append('.')
            append(hundredths.toString().padStart(2, '0'))
            append(']').append(line.text.replace('\n', ' ').trim()).append('\n')
        }
    }

    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val match = LINE.matchEntire(raw.trim()) ?: continue
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.toLong()
            }
            val words = match.groupValues[4].trim()
            if (words.isNotEmpty()) out.add(LyricLine(minutes * 60_000 + seconds * 1000 + millis, words))
        }
        return out.sortedBy { it.timeMs }
    }

    /** The "[la:xx]" tag of an .lrc text, if it has one. */
    fun language(text: String): String? =
        Regex("""^\[la:([A-Za-z-]{2,8})\]""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    /** Index of the line being sung at [positionMs], or −1 before the first line. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
