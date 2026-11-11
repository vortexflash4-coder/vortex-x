package com.vortexx.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Exercises the same ExoPlayer configuration the app uses.
 *
 * - [generatedWavReachesReadyAndPlays] needs no fixtures (emulator OK).
 * - Fixture tests look for files pushed to the app's external files dir:
 *     adb push sample.mp3 /sdcard/Android/data/com.vortexx.player.debug/files/fixtures/
 *   and are skipped (not failed) when a fixture is absent. See docs/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val main = Handler(Looper.getMainLooper())

    private data class Outcome(val readyDurationMs: Long?, val error: PlaybackException?, val advancedMs: Long)

    private fun play(uri: Uri, timeoutSec: Long = 15): Outcome {
        val ready = CountDownLatch(1)
        var player: ExoPlayer? = null
        var duration: Long? = null
        var error: PlaybackException? = null
        main.post {
            val p = ExoPlayer.Builder(context).build()
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && duration == null) {
                        duration = p.duration
                        ready.countDown()
                    }
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    ready.countDown()
                }
            })
            p.setMediaItem(MediaItem.fromUri(uri))
            p.volume = 0f
            p.prepare()
            p.play()
        }
        ready.await(timeoutSec, TimeUnit.SECONDS)
        Thread.sleep(600)
        val positionLatch = CountDownLatch(1)
        var position = 0L
        main.post {
            position = player?.currentPosition ?: 0L
            player?.release()
            positionLatch.countDown()
        }
        positionLatch.await(5, TimeUnit.SECONDS)
        return Outcome(duration, error, position)
    }

    @Test
    fun generatedWavReachesReadyAndPlays() {
        val wav = File(context.cacheDir, "tone.wav").also { writeSineWav(it, seconds = 2) }
        val outcome = play(Uri.fromFile(wav))
        assertNull("Unexpected error: ${outcome.error?.errorCodeName}", outcome.error)
        val duration = outcome.readyDurationMs
        assertNotNull("player never became ready", duration)
        assertTrue("duration was $duration", duration!! in 1_900L..2_100L)
        assertTrue("playback did not advance", outcome.advancedMs > 0)
    }

    @Test
    fun mp3FixturePlays() = fixtureMustPlay("sample.mp3")

    @Test
    fun flacFixturePlays() = fixtureMustPlay("sample.flac")

    @Test
    fun mp4FixturePlays() = fixtureMustPlay("sample.mp4")

    @Test
    fun unsupportedFixtureReportsAnError() {
        val file = fixture("unsupported.bin")
        assumeTrue("Push fixtures/unsupported.bin to run this test", file.exists())
        val outcome = play(Uri.fromFile(file))
        assertNotNull("Expected a playback error", outcome.error)
    }

    private fun fixtureMustPlay(name: String) {
        val file = fixture(name)
        assumeTrue("Push fixtures/$name to run this test", file.exists())
        val outcome = play(Uri.fromFile(file))
        assertNull("Error for $name: ${outcome.error?.errorCodeName}", outcome.error)
        assertNotNull(outcome.readyDurationMs)
    }

    private fun fixture(name: String) = File(context.getExternalFilesDir("fixtures"), name)

    /** Writes a 16-bit mono 44.1 kHz PCM WAV containing a 440 Hz tone. */
    private fun writeSineWav(file: File, seconds: Int) {
        val sampleRate = 44_100
        val samples = sampleRate * seconds
        val dataBytes = samples * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (i in 0 until samples) {
            buffer.putShort((sin(2 * PI * 440 * i / sampleRate) * 8_000).toInt().toShort())
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
    }

    @Test
    fun wavHeaderIsWellFormed() {
        val file = File(context.cacheDir, "header.wav").also { writeSineWav(it, seconds = 1) }
        assertEquals(44L + 44_100 * 2, file.length())
    }
}
