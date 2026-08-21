package app.movia.android.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import app.movia.android.data.library.LibraryRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val DEMO_VIDEO_URL = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"

class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE).orEmpty()
        if (title.isBlank()) return Result.failure()

        val directory = File(applicationContext.filesDir, "offline").apply { mkdirs() }
        val finalFile = File(directory, fileNameFor(title))
        val tempFile = File(directory, "${fileNameFor(title)}.part")

        return try {
            val connection = (URL(DEMO_VIDEO_URL).openConnection() as HttpURLConnection).apply {
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
            LibraryRepository(applicationContext).setDownloaded(title, true, finalFile.absolutePath)
            Result.success(Data.Builder().putString(KEY_FILE_PATH, finalFile.absolutePath).build())
        } catch (error: IOException) {
            tempFile.delete()
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_PROGRESS = "progress"
        const val KEY_FILE_PATH = "file_path"

        fun fileNameFor(title: String): String {
            val safe = title.lowercase().map { ch -> if (ch.isLetterOrDigit()) ch else '_' }.joinToString("")
            return "${safe.take(60)}.mp4"
        }
    }
}
