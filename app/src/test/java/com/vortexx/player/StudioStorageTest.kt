package com.vortexx.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import com.vortexx.player.audio.dsp.PresetResult
import com.vortexx.player.audio.dsp.ReverbType
import com.vortexx.player.audio.engine.AudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Dynamics tab's settings (compressor, balance, room) and presets that hold them, in a real Jetpack
 * DataStore file: written by one engine, read back by a second engine over a copy of the file (a restart).
 */
class StudioStorageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val thresholdKey = floatPreferencesKey("dsp_studio_threshold_db")

    private class Session(file: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val engine = AudioEngine(store, scope)
    }

    private suspend fun until(what: String, condition: suspend () -> Boolean) {
        val reached = withTimeoutOrNull(10_000) {
            while (!condition()) delay(25)
            true
        }
        assertNotNull("timed out waiting for: $what", reached)
    }

    private suspend fun open(file: File): Session {
        val session = Session(file)
        until("the engine to load its settings") { session.engine.loaded.value }
        return session
    }

    @Test
    fun theDynamicsSettingsAndPresetsComeBackAfterARestart() {
        runBlocking {
            val file = File(folder.newFolder("first"), "vortex.preferences_pb")
            val first = open(file)
            val engine = first.engine
            engine.setStudio(true)
            engine.setThreshold(-24f)
            engine.setRatio(3.5f)
            engine.setAttack(8)
            engine.setRelease(220)
            engine.setBalance(-30)
            engine.setReverb(ReverbType.SmallRoom)
            engine.setReverbMix(15)
            assertEquals(PresetResult.Saved, engine.saveUserPreset("Live"))
            until("the settings to reach the file") { first.store.data.first()[thresholdKey] == -24f }

            val copy = File(folder.newFolder("second"), "vortex.preferences_pb")
            file.copyTo(copy)
            val second = open(copy)

            val state = second.engine.state.value
            assertEquals(true, state.studio)
            assertEquals(-24f, state.thresholdDb, 0f)
            assertEquals(3.5f, state.ratio, 0f)
            assertEquals(8, state.attackMs)
            assertEquals(220, state.releaseMs)
            assertEquals(-30, state.balance)
            assertEquals(ReverbType.SmallRoom, state.reverb)
            assertEquals(15, state.reverbMixPercent)
            assertEquals(engine.state.value, state)

            // The saved sound carries the studio controls too.
            val live = second.engine.userPresets.value.single()
            assertEquals("Live", live.name)
            assertEquals(3.5f, live.parameters.ratio, 0f)
            assertEquals(ReverbType.SmallRoom, live.parameters.reverb)

            // Loading it onto a different sound brings the studio controls back with it.
            second.engine.setStudio(false)
            second.engine.setReverb(ReverbType.Off)
            second.engine.applyPreset(live)
            assertEquals(true, second.engine.state.value.studio)
            assertEquals(ReverbType.SmallRoom, second.engine.state.value.reverb)

            first.scope.cancel()
            second.scope.cancel()
        }
    }
}
