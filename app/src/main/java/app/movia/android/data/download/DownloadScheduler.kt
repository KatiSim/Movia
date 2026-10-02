package app.movia.android.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.MediaRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadStatus(
    val state: WorkInfo.State? = null,
    val progressPercent: Int = 0,
    val errorCode: String? = null,
)

object DownloadScheduler {
    fun enqueue(
        context: Context,
        title: String,
        wifiOnly: Boolean,
        contentId: String? = null,
    ) {
        if (title.isBlank()) return
        enqueueInternal(context, title, wifiOnly, resolveMediaRef(contentId, title))
    }

    fun enqueue(
        context: Context,
        mediaRef: MediaRef,
        title: String,
        wifiOnly: Boolean,
    ) {
        if (title.isBlank()) return
        enqueueInternal(context, title, wifiOnly, mediaRef)
    }

    private fun enqueueInternal(
        context: Context,
        title: String,
        wifiOnly: Boolean,
        mediaRef: MediaRef?,
    ) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val session = app.movia.android.ui.player.MoviaPlaybackRegistry.current
        val active = session?.state?.value
        val selection = active?.takeIf { MediaRef(it.mediaId, it.seasonNumber, it.episodeNumber) == mediaRef }?.activeStreamSelection
        val audio = if (selection != null) session?.selectedDownloadAudio() else null
        val input = workDataOf(
            OfflineDownloadWorker.KEY_TITLE to title,
            OfflineDownloadWorker.KEY_CONTENT_ID to mediaRef?.contentId,
            OfflineDownloadWorker.KEY_SEASON to (mediaRef?.season ?: 0),
            OfflineDownloadWorker.KEY_EPISODE to (mediaRef?.episode ?: 0),
            OfflineDownloadWorker.KEY_QUALITY to selection?.requestedQuality,
            OfflineDownloadWorker.KEY_VOICE to selection?.activeVoice,
            OfflineDownloadWorker.KEY_STREAM_ID to selection?.activeStreamId,
            OfflineDownloadWorker.KEY_AUDIO_INDEX to (audio?.first ?: -1),
            OfflineDownloadWorker.KEY_AUDIO_LABEL to audio?.second,
        )
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(input)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30L, java.util.concurrent.TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .addTag(DOWNLOAD_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(title, mediaRef),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun status(context: Context, title: String, contentId: String? = null): DownloadStatus =
        status(context, title, resolveMediaRef(contentId, title))

    suspend fun status(context: Context, title: String, mediaRef: MediaRef?): DownloadStatus =
        withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            val current = manager.getWorkInfosForUniqueWork(uniqueWorkName(title, mediaRef))
                .get()
                .firstOrNull()
            val info = current ?: manager.getWorkInfosForUniqueWork(legacyUniqueWorkName(title))
                .get()
                .firstOrNull()
            DownloadStatus(
                state = info?.state,
                progressPercent = info?.progress?.getInt(OfflineDownloadWorker.KEY_PROGRESS, 0) ?: 0,
                errorCode = info?.outputData?.getString(OfflineDownloadWorker.KEY_ERROR),
            )
        }

    fun localFile(context: Context, title: String, contentId: String? = null): File? {
        val mediaRef = resolveMediaRef(contentId, title)
        return localFile(context, title, mediaRef)
    }

    fun localFile(context: Context, mediaRef: MediaRef, title: String): File? =
        localFile(context, title, mediaRef)

    private fun localFile(context: Context, title: String, mediaRef: MediaRef?): File? {
        if (mediaRef != null && OfflineMediaStore.request(context, mediaRef) != null) return OfflineMediaStore.marker(context, mediaRef)
        val directory = File(context.filesDir, "offline")
        val current = File(directory, OfflineDownloadWorker.fileNameFor(mediaRef, title))
        if (current.isFile && current.length() > 0L) return current

        // Continue to recognize files created by the title-keyed download implementation.
        val legacy = File(directory, OfflineDownloadWorker.fileNameFor(title))
        return legacy.takeIf { it.isFile && it.length() > 0L }
    }

    fun delete(context: Context, title: String, contentId: String? = null): Boolean {
        return delete(context, title, resolveMediaRef(contentId, title))
    }

    fun delete(context: Context, mediaRef: MediaRef, title: String): Boolean =
        delete(context, title, mediaRef)

    private fun delete(context: Context, title: String, mediaRef: MediaRef?): Boolean {
        val active = app.movia.android.ui.player.MoviaPlaybackRegistry.current
        if (active?.isOffline == true && active.state.value.let { MediaRef(it.mediaId, it.seasonNumber, it.episodeNumber) } == mediaRef) return false
        if (mediaRef != null && AdaptiveOfflineDownloader.isDownloading(mediaRef)) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(title, mediaRef))
            return false
        }
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(uniqueWorkName(title, mediaRef))
        manager.cancelUniqueWork(legacyUniqueWorkName(title))

        val directory = File(context.filesDir, "offline")
        val filenames = setOf(
            OfflineDownloadWorker.fileNameFor(mediaRef, title),
            OfflineDownloadWorker.fileNameFor(title),
        )
        var allRemoved = mediaRef?.let { OfflineMediaStore.delete(context, it) } ?: true
        filenames.forEach { filename ->
            val file = File(directory, filename)
            val partial = File(directory, "$filename.part")
            if (partial.exists() && !partial.delete()) allRemoved = false
            if (file.exists() && !file.delete()) allRemoved = false
        }
        return allRemoved
    }

    fun deleteAll(context: Context): Boolean {
        if (app.movia.android.ui.player.MoviaPlaybackRegistry.current?.isOffline == true || AdaptiveOfflineDownloader.hasDownloadsInProgress()) return false
        OfflineMediaStore.releaseAll()
        WorkManager.getInstance(context).cancelAllWorkByTag(DOWNLOAD_TAG)
        val directory = File(context.filesDir, "offline")
        return !directory.exists() || directory.deleteRecursively()
    }

    private fun resolveMediaRef(contentId: String?, title: String): MediaRef? {
        val candidate = contentId?.takeIf { it.isNotBlank() }
            ?: DemoCatalogRepository.findByTitle(title.substringBefore(" · S").substringBefore(" · E"))?.id
        return MediaRef.from(candidate, title)
    }

    private fun uniqueWorkName(title: String, mediaRef: MediaRef?): String {
        val identity = mediaRef?.storageKey ?: MediaRef.legacyStorageKey(title)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "movia-download-$digest"
    }

    private fun legacyUniqueWorkName(title: String): String = "movia-download-${title.hashCode()}"

    private const val DOWNLOAD_TAG = "movia-offline-download"
}
