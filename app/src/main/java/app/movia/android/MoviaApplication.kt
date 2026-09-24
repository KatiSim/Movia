package app.movia.android

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import coil3.ImageLoader
import app.movia.android.data.catalog.DemoCatalogRepository
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toPath

class MoviaApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // Start local Room-backed media hydration immediately; network prewarm remains after first frame.
        DemoCatalogRepository.init(applicationContext)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("movia_coil_artwork").absolutePath.toPath())
                    .maxSizeBytes(128L * 1024L * 1024L)
                    .build()
            }
            .build()
}
