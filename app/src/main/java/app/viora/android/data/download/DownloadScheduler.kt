package app.viora.android.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

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
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(title),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun delete(context: Context, title: String): Boolean {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(title))
        val file = File(File(context.filesDir, "offline"), OfflineDownloadWorker.fileNameFor(title))
        val part = File(File(context.filesDir, "offline"), "${OfflineDownloadWorker.fileNameFor(title)}.part")
        part.delete()
        return !file.exists() || file.delete()
    }

    private fun uniqueWorkName(title: String): String = "viora-download-${title.hashCode()}"
}
