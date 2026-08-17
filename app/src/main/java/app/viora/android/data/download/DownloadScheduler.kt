package app.viora.android.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadStatus(
    val state: WorkInfo.State? = null,
    val progressPercent: Int = 0,
)

object DownloadScheduler {
    fun enqueue(
        context: Context,
        title: String,
        wifiOnly: Boolean,
    ) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf(OfflineDownloadWorker.KEY_TITLE to title))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .addTag(DOWNLOAD_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(title),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun status(context: Context, title: String): DownloadStatus = withContext(Dispatchers.IO) {
        val info = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(uniqueWorkName(title))
            .get()
            .firstOrNull()
        DownloadStatus(
            state = info?.state,
            progressPercent = info?.progress?.getInt(OfflineDownloadWorker.KEY_PROGRESS, 0) ?: 0,
        )
    }

    fun localFile(context: Context, title: String): File? {
        val file = File(File(context.filesDir, "offline"), OfflineDownloadWorker.fileNameFor(title))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun delete(context: Context, title: String): Boolean {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(title))
        val file = File(File(context.filesDir, "offline"), OfflineDownloadWorker.fileNameFor(title))
        val part = File(File(context.filesDir, "offline"), "${OfflineDownloadWorker.fileNameFor(title)}.part")
        part.delete()
        return !file.exists() || file.delete()
    }

    fun deleteAll(context: Context): Boolean {
        WorkManager.getInstance(context).cancelAllWorkByTag(DOWNLOAD_TAG)
        val directory = File(context.filesDir, "offline")
        return !directory.exists() || directory.deleteRecursively()
    }

    private fun uniqueWorkName(title: String): String = "viora-download-${title.hashCode()}"
    private const val DOWNLOAD_TAG = "viora-offline-download"
}
