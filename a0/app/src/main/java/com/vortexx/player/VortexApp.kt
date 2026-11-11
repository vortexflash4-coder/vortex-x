package com.vortexx.player

import android.app.Application
import com.vortexx.player.data.library.ArtworkLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VortexApp : Application() {

    @Inject lateinit var artworkLoader: ArtworkLoader

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Thumbnails are the largest re-creatable memory consumer.
        if (::artworkLoader.isInitialized) artworkLoader.onTrimMemory(level)
    }
}
