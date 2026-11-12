package com.vortexx.player.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import com.vortexx.player.ai.audio.PcmDecoder
import com.vortexx.player.ai.audio.Wav
import com.vortexx.player.ai.audio.WavWriter
import com.vortexx.player.ai.dsp.Resampler
import com.vortexx.player.ai.karaoke.SpleeterSeparator
import com.vortexx.player.ai.lyrics.Lrc
import com.vortexx.player.ai.lyrics.LyricLine
import com.vortexx.player.ai.lyrics.PhraseSegmenter
import com.vortexx.player.ai.lyrics.WhisperTranscriber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** The on-device models, packaged in the APK under assets/ai (see .github/scripts/ci-models.sh). */
@Singleton
class AiModels @Inject constructor(@ApplicationContext private val context: Context) {

    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** True when this build carries the vocal-separation model. */
    val karaokeAvailable: Boolean by lazy { has(SPLEETER_VOCALS) && has(SPLEETER_ACCOMPANIMENT) }

    /** True when this build carries the speech-recognition model. */
    val lyricsAvailable: Boolean by lazy { has(WHISPER_ENCODER) && has(WHISPER_DECODER) && has(WHISPER_TOKENS) }

    /** Size of the packaged models in bytes (0 when none). */
    val packagedBytes: Long by lazy {
        listOf(SPLEETER_VOCALS, SPLEETER_ACCOMPANIMENT, WHISPER_ENCODER, WHISPER_DECODER).sumOf { name ->
            runCatching { context.assets.openFd("$DIR/$name").use { it.length } }.getOrDefault(0L)
        }
    }

    /**
     * Opens a model straight from the APK (memory-mapped; the asset is stored
     * uncompressed), so nothing is copied to storage first.
     */
    fun open(name: String): OrtSession {
        context.assets.openFd("$DIR/$name").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val model = channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                val options = OrtSession.SessionOptions()
                options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                return env.createSession(model, options)
            }
        }
    }

    fun whisperTokens(): List<String> = context.assets.open("$DIR/$WHISPER_TOKENS").bufferedReader().use { it.readLines() }

    private fun has(name: String): Boolean = runCatching { context.assets.open("$DIR/$name").close() }.isSuccess

    companion object {
        const val DIR = "ai"
        const val SPLEETER_VOCALS = "spleeter-vocals.onnx"
        const val SPLEETER_ACCOMPANIMENT = "spleeter-accompaniment.onnx"
        const val WHISPER_ENCODER = "whisper-encoder.onnx"
        const val WHISPER_DECODER = "whisper-decoder.onnx"
        const val WHISPER_TOKENS = "whisper-tokens.txt"
    }
}

/** Where the results live: files/ai/<key>/music.wav, vocals16k.wav and lyrics.lrc. */
@Singleton
class AiStore @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.filesDir, "ai")
    private val _changes = MutableStateFlow(0)

    /** Increases whenever a result is added or deleted. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun dir(key: String): File = File(root, key).apply { mkdirs() }

    /** The song without the voice (44.1 kHz stereo). */
    fun music(key: String): File = File(File(root, key), "music.wav")

    /** The voice alone (16 kHz mono), which the lyrics model listens to. */
    fun vocals(key: String): File = File(File(root, key), "vocals16k.wav")

    fun lyrics(key: String): File = File(File(root, key), "lyrics.lrc")

    fun hasKaraoke(key: String): Boolean = music(key).length() > 44 && vocals(key).length() > 44

    fun hasLyrics(key: String): Boolean = lyrics(key).length() > 0

    fun readLyrics(key: String): List<LyricLine> =
        runCatching { Lrc.parse(lyrics(key).readText()) }.getOrDefault(emptyList())

    fun bytes(key: String): Long = File(root, key).listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteKaraoke(key: String) {
        music(key).delete()
        vocals(key).delete()
        notifyChanged()
    }

    fun deleteLyrics(key: String) {
        lyrics(key).delete()
        notifyChanged()
    }

    fun notifyChanged() {
        _changes.value = _changes.value + 1
    }

    companion object {
        private val KEY_IN_PATH = Regex("/ai/(song-\\d+)/")

        fun keyFor(songId: Long): String = "song-$songId"

        /**
         * The key for what is playing: a library song by its id, or one of this
         * store's own files (the karaoke track) by its folder.
         */
        fun keyFor(songId: Long?, contentUri: String?): String? =
            songId?.let { keyFor(it) } ?: contentUri?.let { KEY_IN_PATH.find(it)?.groupValues?.get(1) }
    }
}

enum class AiTask { Karaoke, Lyrics }

/** The song a job works on. [uri] is its content URI. */
data class AiTarget(val key: String, val title: String, val artist: String, val uri: String)

sealed interface AiState {
    data object Idle : AiState
    data class Running(val target: AiTarget, val task: AiTask, val stage: String, val progress: Float) : AiState
    data class Failed(val target: AiTarget, val task: AiTask, val message: String) : AiState
    data class Finished(val target: AiTarget, val task: AiTask, val seconds: Int) : AiState
}

/**
 * Runs the on-device AI jobs, one at a time, off the main thread:
 * - Karaoke: decode the song, separate voice and music (Spleeter), save the
 *   music and a small voice track.
 * - Lyrics: find the sung phrases in the voice track and transcribe each one
 *   (Whisper); the phrase start is the line's time.
 * Everything stays on the phone.
 */
@Singleton
class AiEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: AiModels,
    private val store: AiStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<AiState>(AiState.Idle)
    val state: StateFlow<AiState> = _state.asStateFlow()
    private var job: Job? = null

    /** Starts [task] for [target] unless a job is already running. [language] is a Whisper code or null for automatic. */
    fun start(target: AiTarget, task: AiTask, language: String? = null) {
        if (job?.isActive == true) return
        _state.value = AiState.Running(target, task, "Starting", 0f)
        job = scope.launch {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vortexx:ai")
            wakeLock.acquire(WAKE_LOCK_MS)
            val began = SystemClock.elapsedRealtime()
            try {
                if (!store.hasKaraoke(target.key)) makeKaraoke(target, task)
                if (task == AiTask.Lyrics) makeLyrics(target, language)
                val seconds = ((SystemClock.elapsedRealtime() - began) / 1000L).toInt()
                _state.value = AiState.Finished(target, task, seconds)
            } catch (e: CancellationException) {
                _state.value = AiState.Idle
                throw e
            } catch (e: Throwable) {
                // Includes OutOfMemoryError and a missing native library: report, never crash the app.
                _state.value = AiState.Failed(target, task, e.message ?: e.javaClass.simpleName)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                store.notifyChanged()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished or failed result from [state]. */
    fun dismiss() {
        if (_state.value !is AiState.Running) _state.value = AiState.Idle
    }

    private fun report(target: AiTarget, task: AiTask, stage: String, progress: Float) {
        _state.value = AiState.Running(target, task, stage, progress.coerceIn(0f, 1f))
    }

    private suspend fun makeKaraoke(target: AiTarget, task: AiTask) {
        val running = currentCoroutineContext()[Job]
        val cancelled = { running?.isActive == false }
        val dir = store.dir(target.key)
        val mix = File(dir, "mix.tmp")
        val musicTmp = File(dir, "music.tmp")
        val vocalsTmp = File(dir, "vocals.tmp")
        try {
            // 1. Decode to 44.1 kHz stereo on disk, so memory use does not depend on the song's length.
            report(target, task, "Reading the song", 0f)
            var frames = 0L
            BufferedOutputStream(FileOutputStream(mix), 1 shl 16).use { out ->
                var channels = 2
                var durationUs = -1L
                var resampleLeft: Resampler? = null
                var resampleRight: Resampler? = null
                var left = FloatArray(0)
                var right = FloatArray(0)
                PcmDecoder(context).decode(
                    uri = Uri.parse(target.uri),
                    isCancelled = cancelled,
                    onFormat = { format ->
                        channels = format.channels.coerceAtLeast(1)
                        durationUs = format.durationUs
                        if (format.sampleRate != SpleeterSeparator.SAMPLE_RATE) {
                            resampleLeft = Resampler(format.sampleRate, SpleeterSeparator.SAMPLE_RATE)
                            resampleRight = Resampler(format.sampleRate, SpleeterSeparator.SAMPLE_RATE)
                        }
                    },
                    onPcm = { pcm, count ->
                        val n = count / channels
                        if (left.size < n) {
                            left = FloatArray(n)
                            right = FloatArray(n)
                        }
                        for (i in 0 until n) {
                            val base = i * channels
                            var l = pcm[base] / 32768f
                            var r = if (channels > 1) pcm[base + 1] / 32768f else l
                            if (channels > 2) {
                                // Surround: fold the centre channel (where the voice sits) into both sides.
                                val centre = pcm[base + 2] / 32768f * 0.7f
                                l = (l + centre) * 0.6f
                                r = (r + centre) * 0.6f
                            }
                            left[i] = l
                            right[i] = r
                        }
                        val rl = resampleLeft
                        val rr = resampleRight
                        frames += if (rl != null && rr != null) {
                            val a = rl.process(left, n)
                            val b = rr.process(right, n)
                            writeStereo(out, a, b, minOf(a.size, b.size))
                        } else {
                            writeStereo(out, left, right, n)
                        }
                        if (durationUs > 0) {
                            report(target, task, "Reading the song", frames * 1_000_000f / SpleeterSeparator.SAMPLE_RATE / durationUs)
                        }
                    },
                )
                val rl = resampleLeft
                val rr = resampleRight
                if (rl != null && rr != null) {
                    val a = rl.flush()
                    val b = rr.flush()
                    frames += writeStereo(out, a, b, minOf(a.size, b.size))
                }
            }
            if (frames <= 0) throw IOException("The song could not be decoded")

            // 2. Separate, block by block.
            report(target, task, "Separating voice and music", 0f)
            val vocalsModel = models.open(AiModels.SPLEETER_VOCALS)
            val musicModel = try {
                models.open(AiModels.SPLEETER_ACCOMPANIMENT)
            } catch (e: Throwable) {
                vocalsModel.close()
                throw e
            }
            try {
                val separator = SpleeterSeparator(models.env, vocalsModel, musicModel)
                val bytes = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
                val toSpeechRate = Resampler(SpleeterSeparator.SAMPLE_RATE, VOCALS_RATE)
                var stereo = FloatArray(0)
                var mono = FloatArray(0)
                FileInputStream(mix).channel.use { channel ->
                    WavWriter(musicTmp, SpleeterSeparator.SAMPLE_RATE, 2).use { music ->
                        WavWriter(vocalsTmp, VOCALS_RATE, 1).use { vocals ->
                            separator.separate(
                                totalFrames = frames,
                                source = SpleeterSeparator.Source { l, r, offset, count -> readStereo(channel, bytes, l, r, offset, count) },
                                sink = SpleeterSeparator.Sink { vocalsLeft, vocalsRight, mixLeft, mixRight, offset, count ->
                                    if (stereo.size < count * 2) {
                                        stereo = FloatArray(count * 2)
                                        mono = FloatArray(count)
                                    }
                                    for (i in 0 until count) {
                                        val j = offset + i
                                        stereo[2 * i] = mixLeft[j] - vocalsLeft[j]
                                        stereo[2 * i + 1] = mixRight[j] - vocalsRight[j]
                                        mono[i] = (vocalsLeft[j] + vocalsRight[j]) * 0.5f
                                    }
                                    music.write(stereo, count * 2)
                                    val speech = toSpeechRate.process(mono, count)
                                    vocals.write(speech, speech.size)
                                },
                                onProgress = { p -> report(target, task, "Separating voice and music", p) },
                                isCancelled = cancelled,
                            )
                            val tail = toSpeechRate.flush()
                            vocals.write(tail, tail.size)
                        }
                    }
                }
            } finally {
                vocalsModel.close()
                musicModel.close()
            }
            if (cancelled()) throw CancellationException("Cancelled")
            if (!musicTmp.renameTo(store.music(target.key)) || !vocalsTmp.renameTo(store.vocals(target.key))) {
                throw IOException("The result could not be saved")
            }
        } finally {
            mix.delete()
            musicTmp.delete()
            vocalsTmp.delete()
        }
    }

    private suspend fun makeLyrics(target: AiTarget, language: String?) {
        val running = currentCoroutineContext()[Job]
        report(target, AiTask.Lyrics, "Finding the sung phrases", 0f)
        val wav = Wav.read(store.vocals(target.key))
        val samples = wav.mono()
        val phrases = PhraseSegmenter.phrases(samples, wav.sampleRate)
        if (phrases.isEmpty()) throw IOException("No singing was found in this song")

        val encoder = models.open(AiModels.WHISPER_ENCODER)
        val decoder = try {
            models.open(AiModels.WHISPER_DECODER)
        } catch (e: Throwable) {
            encoder.close()
            throw e
        }
        try {
            val whisper = WhisperTranscriber(models.env, encoder, decoder, models.whisperTokens().asSequence())
            var chosen = language
            val lines = ArrayList<LyricLine>()
            for ((index, phrase) in phrases.withIndex()) {
                if (running?.isActive == false) throw CancellationException("Cancelled")
                val piece = samples.copyOfRange(phrase.start, phrase.end)
                val heard = whisper.transcribe(piece, piece.size, chosen)
                val text = LyricsText.clean(heard.text)
                if (text.isNotEmpty()) {
                    // The first phrase with words fixes the language for the rest of the song.
                    if (chosen == null) chosen = heard.language
                    if (text != lines.lastOrNull()?.text) lines.add(LyricLine(phrase.start * 1000L / wav.sampleRate, text))
                }
                report(target, AiTask.Lyrics, "Writing the lyrics", (index + 1f) / phrases.size)
            }
            if (lines.isEmpty()) throw IOException("The model heard no words in this song")
            store.lyrics(target.key).writeText(Lrc.format(lines, language = chosen, by = "VORTEX X on-device AI (Whisper)"))
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    private var scratch = ByteArray(0)

    /** Appends [count] frames as 16-bit little-endian stereo. Returns [count]. */
    private fun writeStereo(out: OutputStream, left: FloatArray, right: FloatArray, count: Int): Int {
        if (scratch.size < count * 4) scratch = ByteArray(count * 4)
        for (i in 0 until count) {
            val l = (left[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            val r = (right[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            scratch[4 * i] = l.toByte()
            scratch[4 * i + 1] = (l shr 8).toByte()
            scratch[4 * i + 2] = r.toByte()
            scratch[4 * i + 3] = (r shr 8).toByte()
        }
        out.write(scratch, 0, count * 4)
        return count
    }

    /** Reads up to [count] stereo frames into the arrays at [offset]. Returns the frames read. */
    private fun readStereo(channel: FileChannel, bytes: ByteBuffer, left: FloatArray, right: FloatArray, offset: Int, count: Int): Int {
        var done = 0
        while (done < count) {
            bytes.clear()
            bytes.limit(minOf(bytes.capacity(), (count - done) * 4))
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) break
            }
            bytes.flip()
            val frames = bytes.remaining() / 4
            if (frames == 0) break
            for (i in 0 until frames) {
                left[offset + done + i] = bytes.getShort(i * 4) / 32768f
                right[offset + done + i] = bytes.getShort(i * 4 + 2) / 32768f
            }
            done += frames
        }
        return done
    }

    private companion object {
        const val VOCALS_RATE = 16_000
        const val WAKE_LOCK_MS = 45L * 60L * 1000L
    }
}

/** Tidies what the speech model returns before it becomes a lyric line. */
object LyricsText {
    private val BRACKETED = Regex("""[\[(（【][^\])）】]{0,40}[\])）】]""")
    private val SPACES = Regex("""\s+""")

    /** Removes sound descriptions such as "(music)" and stray note signs; empty when nothing sung remains. */
    fun clean(text: String): String {
        val stripped = BRACKETED.replace(text, " ").replace('♪', ' ').replace('♫', ' ')
        val collapsed = SPACES.replace(stripped, " ").trim()
        return if (collapsed.any { it.isLetterOrDigit() }) collapsed else ""
    }
}
