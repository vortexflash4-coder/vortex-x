package com.vortexx.player.domain.playback

import androidx.media3.common.PlaybackException

/** Rules for "resume where you left off" and "continue watching". */
object ResumePolicy {
    private const val MIN_RESUME_MS = 10_000L
    private const val END_GUARD_MS = 15_000L

    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_RESUME_MS) return false
        if (durationMs <= 0L) return true
        if (positionMs >= durationMs - END_GUARD_MS) return false
        return positionMs.toDouble() / durationMs.toDouble() < 0.97
    }

    /** Fraction watched, or null if the item should not appear in "Continue watching". */
    fun continueWatchingFraction(positionMs: Long, durationMs: Long): Float? {
        if (durationMs <= 0L) return null
        val fraction = positionMs.toFloat() / durationMs.toFloat()
        return if (fraction in 0.02f..0.95f) fraction else null
    }
}

/** Speed and sleep-timer choices exposed in the Control Center. */
object PlaybackOptions {
    val speeds: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val sleepTimerMinutes: List<Int> = listOf(15, 30, 45, 60, 90)
}

/**
 * Converts Media3 error codes into actionable explanations. Kept free of
 * Android framework types so it is unit-testable.
 */
object PlaybackErrorExplainer {

    data class Explanation(val title: String, val detail: String, val canRetry: Boolean)

    fun explain(errorCode: Int): Explanation = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> Explanation(
            title = "Format not supported on this device",
            detail = "No installed decoder can play this stream's codec or profile. Check Diagnostics to see which decoders your device has.",
            canRetry = false,
        )

        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> Explanation(
            title = "Beyond this device's decoder limits",
            detail = "A decoder exists, but this file's resolution, frame rate or profile is higher than the hardware supports.",
            canRetry = false,
        )

        PlaybackException.ERROR_CODE_DECODING_FAILED -> Explanation(
            title = "Decoding failed",
            detail = "The decoder stopped while reading this file. The file may be damaged; retrying sometimes helps.",
            canRetry = true,
        )

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Explanation(
            title = "File not found",
            detail = "The file was moved or deleted. Refresh the library from Media Explorer.",
            canRetry = false,
        )

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Explanation(
            title = "No permission to read this file",
            detail = "Grant media access in Android settings, or open the file again from Media Explorer.",
            canRetry = false,
        )

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> Explanation(
            title = "Unreadable or unsupported container",
            detail = "The file's container could not be parsed. It may be corrupt or use an unsupported variant.",
            canRetry = false,
        )

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> Explanation(
            title = "Audio output error",
            detail = "The audio output could not be opened. This can happen when the output device changes; try again.",
            canRetry = true,
        )

        else -> Explanation(
            title = "Playback error",
            detail = "Something went wrong while playing this item. Try again or skip to the next one.",
            canRetry = true,
        )
    }
}
