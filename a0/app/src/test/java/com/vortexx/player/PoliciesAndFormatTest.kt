package com.vortexx.player

import androidx.media3.common.PlaybackException
import com.vortexx.player.core.util.Format
import com.vortexx.player.domain.playback.PlaybackErrorExplainer
import com.vortexx.player.domain.playback.ResumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesAndFormatTest {

    @Test
    fun durationFormatting() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("0:00", Format.duration(-5))
        assertEquals("0:59", Format.duration(59_999))
        assertEquals("3:07", Format.duration(187_000))
        assertEquals("1:02:03", Format.duration(3_723_000))
    }

    @Test
    fun longDurationFormatting() {
        assertEquals("0 min", Format.longDuration(30_000))
        assertEquals("45 min", Format.longDuration(45 * 60_000L))
        assertEquals("2 h", Format.longDuration(120 * 60_000L))
        assertEquals("3 h 12 min", Format.longDuration(192 * 60_000L))
    }

    @Test
    fun byteFormatting() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.5 KB", Format.bytes(1536))
        assertEquals("820 MB", Format.bytes(820L * 1024 * 1024))
        assertEquals("1.4 GB", Format.bytes((1.4 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun resolutionBadges() {
        assertEquals("4K", Format.resolutionBadge(3840, 2160))
        assertEquals("4K", Format.resolutionBadge(2160, 3840))
        assertEquals("1080p", Format.resolutionBadge(1920, 1080))
        assertEquals("720p", Format.resolutionBadge(1280, 720))
        assertNull(Format.resolutionBadge(0, 0))
    }

    @Test
    fun speedLabels() {
        assertEquals("1x", Format.speed(1f))
        assertEquals("1.25x", Format.speed(1.25f))
        assertEquals("0.5x", Format.speed(0.5f))
    }

    @Test
    fun resumeOnlyInsideSensibleWindow() {
        assertFalse(ResumePolicy.shouldResume(5_000, 600_000))
        assertTrue(ResumePolicy.shouldResume(120_000, 600_000))
        assertFalse(ResumePolicy.shouldResume(590_000, 600_000))
        assertTrue(ResumePolicy.shouldResume(60_000, 0))
    }

    @Test
    fun continueWatchingWindow() {
        assertNull(ResumePolicy.continueWatchingFraction(1_000, 600_000))
        assertNull(ResumePolicy.continueWatchingFraction(590_000, 600_000))
        assertEquals(0.5f, ResumePolicy.continueWatchingFraction(300_000, 600_000)!!, 0.001f)
        assertNull(ResumePolicy.continueWatchingFraction(10_000, 0))
    }

    @Test
    fun unsupportedCodecIsNotRetryable() {
        val e = PlaybackErrorExplainer.explain(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        assertFalse(e.canRetry)
        assertTrue(e.title.contains("not supported"))
    }

    @Test
    fun audioOutputErrorIsRetryable() {
        assertTrue(PlaybackErrorExplainer.explain(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED).canRetry)
    }

    @Test
    fun unknownErrorFallsBackToGeneric() {
        val e = PlaybackErrorExplainer.explain(999_999)
        assertEquals("Playback error", e.title)
        assertTrue(e.canRetry)
    }
}
