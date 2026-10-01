@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.movia.android.data.download

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
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return failed("MISSING_TITLE")
        val contentId = inputData.getString(KEY_CONTENT_ID)?.takeIf { it.isNotBlank() } ?: return failed("MISSING_IDENTITY")
        val season = inputData.getInt(KEY_SEASON, 0).takeIf { it > 0 }
        val episode = inputData.getInt(KEY_EPISODE, 0).takeIf { it > 0 }
        if ((season == null) != (episode == null)) return failed("INCOMPLETE_EPISODE_IDENTITY")
        val ref = MediaRef(contentId, season, episode)
        OfflineMediaStore.request(applicationContext, ref)?.let {
            return Result.success(Data.Builder().putString(KEY_FILE_PATH, OfflineMediaStore.marker(applicationContext, ref).absolutePath).build())
        }
        return try {
            setForeground(downloadForeground(title))
            val content = withContext(Dispatchers.IO) { DemoCatalogRepository.findFullById(contentId) ?: DemoCatalogRepository.findById(contentId) }
                ?: return failed("MEDIA_NOT_FOUND")
            val preferenceRepository = app.movia.android.data.preferences.MoviaPreferencesRepository(applicationContext)
            val playbackPreferences = preferenceRepository.playbackPreferences.first()
            val titlePreferences = preferenceRepository.titlePlaybackPreferences(content.title).first()
            val request = PlaybackRequest(content.id, content.title, content.type, content.year,
                seasonNumber = season, episodeNumber = episode,
                requestedVoice = inputData.getString(KEY_VOICE) ?: titlePreferences.audio ?: playbackPreferences.audio,
                requestedQuality = inputData.getString(KEY_QUALITY) ?: titlePreferences.quality ?: playbackPreferences.quality,
                requestedStreamId = inputData.getString(KEY_STREAM_ID))
            val seeds = content.streams.filter {
                if (season == null) it.seasonNumber == null && it.episodeNumber == null
                else it.seasonNumber == season && it.episodeNumber == episode
            }.map { StreamCandidate.fromStreamOption(it, season, episode) }
            var refreshed = false
            while (true) {
                val resolved = try {
                    withTimeoutOrNull(20_000L) { DomainPlaybackResolver.resolveStreams(request, seeds, forceRefresh = refreshed) }
                } catch (_: IOException) { null }
                val legacySelection = request.requestedStreamId?.startsWith("legacy:") == true
                val backendCandidates = (resolved as? PlaybackResolverResult.Success)?.candidates.orEmpty()
                val nativeCandidates = if (backendCandidates.isEmpty() || legacySelection || refreshed) {
                    app.movia.android.domain.legacy.LegacyPlaybackResolver.discover(applicationContext, request)
                } else emptyList()
                val candidates = app.movia.android.domain.playback.StreamDeduplicator.deduplicate(backendCandidates + nativeCandidates)
                if (candidates.isEmpty()) return failed("NO_SOURCE")
                val source = request.requestedStreamId?.let { id -> candidates.firstOrNull { it.stableStreamId == id } }
                    ?: StreamRanker.selectBest(candidates, request.requestedVoice, request.requestedQuality)
                    ?: return failed("NO_SOURCE")
                if (source.drmScheme != null) return failed("OFFLINE_LICENSE_REQUIRED")
                val playable = app.movia.android.domain.legacy.LegacyPlaybackResolver.playable(applicationContext, source)
                    ?: return failed("NO_PLAYABLE_SOURCE")
                try {
                    AdaptiveOfflineDownloader.download(applicationContext, ref, playable, request.requestedQuality ?: "Auto", { isStopped }) { percent ->
                        setProgress(Data.Builder().putInt(KEY_PROGRESS, percent).build())
                    }
                    val marker = OfflineMediaStore.marker(applicationContext, ref)
                    LibraryRepository(applicationContext).setDownloaded(ref, title, true, marker.absolutePath)
                    return Result.success(Data.Builder().putString(KEY_FILE_PATH, marker.absolutePath).build())
                } catch (error: IOException) {
                    val response = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    if (response == null) throw error
                    when (downloadHttpAction(response.responseCode, runAttemptCount, refreshed)) {
                        DownloadHttpAction.REFRESH_SOURCE -> { refreshed = true; continue }
                        DownloadHttpAction.RETRY -> {
                            val header = response.headerFields.entries.firstOrNull { it.key.equals("Retry-After",true) }?.value?.firstOrNull()
                            val wait = boundedRetryAfterMillis(header)
                            if (wait > 0L) delay(wait)
                            return Result.retry()
                        }
                        DownloadHttpAction.FAIL -> return failed("HTTP_" + response.responseCode)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IOException) {
            if (runAttemptCount < 2) Result.retry() else failed("NETWORK_RETRIES_EXHAUSTED")
        } catch (_: Exception) { failed("DOWNLOAD_FAILED") }
    }

    private fun downloadForeground(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("movia_downloads", "Загрузки Movia", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, "movia_downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Скачиваем для офлайн-просмотра")
            .setContentText(title).setOngoing(true).build()
        return ForegroundInfo(2801, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    private fun failed(code: String) = Result.failure(Data.Builder().putString(KEY_ERROR, code).build())
    companion object {
        const val KEY_TITLE = "title"
        const val KEY_CONTENT_ID = "content_id"
        const val KEY_SEASON = "season"
        const val KEY_EPISODE = "episode"
        const val KEY_URL = "download_url"
        const val KEY_QUALITY = "quality"
        const val KEY_VOICE = "voice"
        const val KEY_STREAM_ID = "stream_id"
        const val KEY_ERROR = "error_code"
        const val KEY_PROGRESS = "progress"
        const val KEY_FILE_PATH = "file_path"
        private fun qualityHeight(value: String): Int? = if (value.equals("4K", true)) 2160 else Regex("\\d{3,4}").find(value)?.value?.toIntOrNull()
        fun fileNameFor(title: String): String = title.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(60) + ".mp4"
        fun fileNameFor(contentId: String?, title: String): String = fileNameFor(MediaRef.from(contentId, title), title)
        fun fileNameFor(mediaRef: MediaRef?, title: String): String {
            if (mediaRef == null) return fileNameFor(title)
            return java.security.MessageDigest.getInstance("SHA-256").digest(mediaRef.storageKey.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".mp4"
        }
    }
}
