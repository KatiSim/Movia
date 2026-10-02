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
import app.movia.android.ui.player.providerTrackOverride
import app.movia.android.domain.model.videoQualityHeight
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
        val mime = when {
            clean.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            else -> source.mimeType ?: when (source.transport) {
                "hls" -> MimeTypes.APPLICATION_M3U8
                "dash" -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
        val item = MediaItem.Builder().setUri(uri).setMimeType(mime).build()
        val selection = TrackSelectionParameters.Builder(context.applicationContext)
            .setMaxVideoSize(Int.MAX_VALUE, videoQualityHeight(selectedQuality) ?: Int.MAX_VALUE)
            .setForceHighestSupportedBitrate(true).setPreferredAudioLanguage(source.language).build()
        var helper: DownloadHelper? = null
        var offlineAudioIndex: Int? = source.audioTrackIndex
        var offlineAudioLabel: String? = source.transportMetadata["movia_audio_label"]
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
                                val tracks = prepared.getTracks(period)
                                val groups = tracks.groups
                                fun overrideIndex(type: Int, wanted: Int?) {
                                    if (wanted == null || wanted < 0) return
                                    val override = providerTrackOverride(tracks, type, wanted, source.transportMetadata)
                                        ?: throw OfflineSelectionException(if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) "AUDIO_RENDITION_UNAVAILABLE" else "VIDEO_RENDITION_UNAVAILABLE")
                                    params.setOverrideForType(override)
                                    if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                                        // The filtered offline manifest has one selected logical voice.
                                        offlineAudioIndex = 0
                                        offlineAudioLabel = override.mediaTrackGroup.getFormat(override.trackIndices.first()).label?.takeIf { it.isNotBlank() }
                                    }
                                }
                                overrideIndex(androidx.media3.common.C.TRACK_TYPE_AUDIO, source.audioTrackIndex)
                                val height = videoQualityHeight(selectedQuality)
                                if (height == null) overrideIndex(androidx.media3.common.C.TRACK_TYPE_VIDEO, source.videoTrackIndex)
                                else {
                                    val chosen = groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                                        .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) && group.getTrackFormat(it).height == height }
                                            .map { index -> group to index } }
                                        .maxByOrNull { (group,index) -> group.getTrackFormat(index).averageBitrate }
                                    if (chosen == null) throw OfflineSelectionException("QUALITY_UNAVAILABLE")
                                    params.setOverrideForType(androidx.media3.common.TrackSelectionOverride(chosen.first.mediaTrackGroup, chosen.second))
                                }
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
            OfflineMediaStore.complete(context.applicationContext, ref, downloadRequest, source.voice, selectedQuality, offlineAudioIndex, offlineAudioLabel)
            onProgress(100)
        } finally { if (isCancelled()) downloader.cancel() }
        } finally { inFlight.remove(ref.storageKey) }
    }

}

/** An impossible explicit variant is permanent, so WorkManager must not retry it. */
internal class OfflineSelectionException(val code: String) : IOException(code)
