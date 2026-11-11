package com.vortexx.player.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide sleep timer. The UI sets the deadline; PlaybackService observes it
 * and pauses its player when it expires, so it keeps working with the screen off.
 */
@Singleton
class SleepTimer @Inject constructor() {
    private val _deadlineElapsedMs = MutableStateFlow<Long?>(null)

    /** Deadline on the SystemClock.elapsedRealtime() clock, or null when off. */
    val deadlineElapsedMs: StateFlow<Long?> = _deadlineElapsedMs.asStateFlow()

    fun start(minutes: Int) {
        require(minutes > 0) { "Sleep timer needs a positive duration" }
        _deadlineElapsedMs.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun cancel() {
        _deadlineElapsedMs.value = null
    }

    fun remainingMs(): Long? = _deadlineElapsedMs.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
}
