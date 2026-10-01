@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.movia.android.data.download

import kotlinx.coroutines.sync.withPermit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.library.LibraryRepository
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.playback.DomainPlaybackResolver
import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.PlaybackResolverResult
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamRanker
import app.movia.android.domain.playback.StreamRequestProfile
import app.movia.android.ui.player.DynamicHeaderDataSourceFactory
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared segment downloader, used by WorkManager and real Android regression tests. */
object AdaptiveOfflineDownloader {
    private val permits = kotlinx.coroutines.sync.Semaphore(2)
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun hasDownloadsInProgress(): Boolean = inFlight.isNotEmpty()
    fun isDownloading(ref: MediaRef): Boolean = ref.storageKey in inFlight
    suspend fun download(context: Context, ref: MediaRef, source: StreamCandidate, selectedQuality: String, isCancelled: () -> Boolean = { false }, onProgress: suspend (Int) -> Unit = {}) = permits.withPermit {
        check(inFlight.add(ref.storageKey)) { "Download already active" }
        try {
        val uri = source.downloadUrl?.trim()?.takeIf { it.isNotBlank() } ?: source.url.trim()
        require(uri.startsWith("https://") || uri.startsWith("http://")) { "Unsupported offline transport" }
        val candidate = source.copy(url = uri, headers = source.headers + source.downloadHeaders)
        val upstream = DynamicHeaderDataSourceFactory(context.applicationContext).apply {
            setRequestProfile(StreamRequestProfile.from(candidate, uri))
        }
        val clean = uri.substringBefore('?').lowercase()
        val mime = source.mimeType ?: when {
            clean.contains(".m3u8") || source.transport == "hls" -> MimeTypes.APPLICATION_M3U8
            clean.contains(".mpd") || source.transport == "dash" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = MediaItem.Builder().setUri(uri).setMimeType(mime).build()
        val selection = TrackSelectionParameters.Builder(context.applicationContext)
            .setMaxVideoSize(Int.MAX_VALUE, qualityHeight(selectedQuality) ?: 1080)
            .setForceHighestSupportedBitrate(true).setPreferredAudioLanguage(source.language).build()
        var helper: DownloadHelper? = null
        val downloadRequest = try {
            withTimeout(20_000L) { withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<DownloadRequest> { continuation ->
                    val current = DownloadHelper.forMediaItem(item, selection, DefaultRenderersFactory(context.applicationContext), upstream)
                    helper = current
                    continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { current.release() } }
                    current.prepare(object : DownloadHelper.Callback {
                        override fun onPrepared(prepared: DownloadHelper, tracksInformationAvailable: Boolean) {
                            try {
                            if (tracksInformationAvailable) for (period in 0 until prepared.periodCount) {
                                val params = selection.buildUpon()
                                val groups = prepared.getTracks(period).groups
                                fun overrideIndex(type: Int, wanted: Int?) {
                                    if (wanted == null || wanted < 0) return
                                    var offset = wanted
                                    for (group in groups.filter { it.type == type }) {
                                        if (offset < group.length) {
                                            if (group.isTrackSupported(offset)) params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, offset))
                                            return
                                        }
                                        offset -= group.length
                                    }
                                }
                                overrideIndex(androidx.media3.common.C.TRACK_TYPE_AUDIO, source.audioTrackIndex)
                                if (qualityHeight(selectedQuality) == null) overrideIndex(androidx.media3.common.C.TRACK_TYPE_VIDEO, source.videoTrackIndex)
                                prepared.replaceTrackSelections(period, params.build())
                            }
                            if (continuation.isActive) continuation.resume(prepared.getDownloadRequest(ref.storageKey, null))
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                        override fun onPrepareError(prepared: DownloadHelper, error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    })
                }
            } }
        } finally { withContext(NonCancellable + Dispatchers.Main) { helper?.release() } }
        val factory = CacheDataSource.Factory().setCache(OfflineMediaStore.cache(context.applicationContext, ref)).setUpstreamDataSourceFactory(upstream)
        val downloader = DefaultDownloaderFactory(factory, Executor { it.run() }).createDownloader(downloadRequest)
        val progress = AtomicInteger(0)
        try {
            coroutineScope {
                val reporter = launch { while (isActive) { onProgress(progress.get()); delay(750L) } }
                try {
                    runInterruptible(Dispatchers.IO) { downloader.download { _, _, percent ->
                        if (isCancelled()) { downloader.cancel(); throw IOException("Cancelled") }
                        if (percent >= 0f) progress.set(percent.toInt().coerceIn(0, 99))
                    } }
                } finally { reporter.cancel() }
            }
            check(OfflineMediaStore.cache(context.applicationContext, ref).cacheSpace > 0L) { "Empty offline media" }
            OfflineMediaStore.complete(context.applicationContext, ref, downloadRequest, source.voice, selectedQuality)
            onProgress(100)
        } finally { if (isCancelled()) downloader.cancel() }
        } finally { inFlight.remove(ref.storageKey) }
    }

    private fun qualityHeight(value: String): Int? = if (value.equals("4K", true)) 2160 else Regex("\\d{3,4}").find(value)?.value?.toIntOrNull()
}
