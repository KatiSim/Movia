package app.movia.android.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.library.LibraryRepository
import app.movia.android.domain.model.MediaRef
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE).orEmpty()
        if (title.isBlank()) return Result.failure()
        val contentId = inputData.getString(KEY_CONTENT_ID).orEmpty()
        val season = inputData.getInt(KEY_SEASON, 0).takeIf { it > 0 }
        val episode = inputData.getInt(KEY_EPISODE, 0).takeIf { it > 0 }
        val mediaRef = contentId.takeIf { it.isNotBlank() }?.let { MediaRef(it, season, episode) }

        val content = mediaRef?.let { DemoCatalogRepository.findById(it.contentId) }
            ?: DemoCatalogRepository.findByTitle(title.substringBefore(" · S").substringBefore(" · E"))
        val matchingEpisodeStream = content?.streams.orEmpty().firstOrNull { stream ->
            mediaRef?.season != null && mediaRef.episode != null &&
                stream.seasonNumber == mediaRef.season && stream.episodeNumber == mediaRef.episode
        }
        val downloadUrl = inputData.getString(KEY_URL)
            ?: matchingEpisodeStream?.downloadUrl?.takeIf { it.isNotBlank() }
            ?: content?.playbackUrl
            ?: matchingEpisodeStream?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: content?.streams.orEmpty().firstOrNull {
                it.url.startsWith("http://") || it.url.startsWith("https://")
            }?.url

        if (downloadUrl.isNullOrBlank()) {
            return Result.failure()
        }

        val directory = File(applicationContext.filesDir, "offline").apply { mkdirs() }
        val finalFileName = fileNameFor(mediaRef, title)
        val finalFile = File(directory, finalFileName)
        val tempFile = File(directory, "$finalFileName.part")

        return try {
            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return Result.retry()
            }

            val total = connection.contentLengthLong.coerceAtLeast(0L)
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        if (isStopped) throw IOException("Download stopped")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0L) {
                            setProgress(Data.Builder().putInt(KEY_PROGRESS, ((copied * 100L) / total).toInt()).build())
                        }
                    }
                }
            }
            connection.disconnect()

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) throw IOException("Could not finalize download")
            if (mediaRef != null) {
                LibraryRepository(applicationContext).setDownloaded(mediaRef, title, true, finalFile.absolutePath)
            } else {
                LibraryRepository(applicationContext).setDownloaded(contentId, title, true, finalFile.absolutePath)
            }
            Result.success(Data.Builder().putString(KEY_FILE_PATH, finalFile.absolutePath).build())
        } catch (error: IOException) {
            tempFile.delete()
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_CONTENT_ID = "content_id"
        const val KEY_SEASON = "season"
        const val KEY_EPISODE = "episode"
        const val KEY_URL = "download_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_FILE_PATH = "file_path"

        fun fileNameFor(title: String): String {
            val safe = title.lowercase().map { ch -> if (ch.isLetterOrDigit()) ch else '_' }.joinToString("")
            return "${safe.take(60)}.mp4"
        }

        fun fileNameFor(contentId: String?, title: String): String {
            if (contentId.isNullOrBlank()) return fileNameFor(title)
            return fileNameFor(MediaRef.from(contentId, title), title)
        }

        fun fileNameFor(mediaRef: MediaRef?, title: String): String {
            if (mediaRef == null) return fileNameFor(title)
            val identity = mediaRef.storageKey
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "$digest.mp4"
        }
    }
}
