package io.github.sasakiy84.podcast

import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow

class PodcastApplication : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(128L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()
    val repository: PodcastRepository by lazy { PodcastRepository(this) }
    internal val mobileDataPlayback by lazy { MobileDataPlayback(this, repository.settings) }
    internal val playbackErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

}

