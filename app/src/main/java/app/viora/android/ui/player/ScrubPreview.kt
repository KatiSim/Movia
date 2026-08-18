package app.viora.android.ui.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun loadScrubPreviewFrame(
    context: Context,
    sourceUri: String?,
    positionMs: Long,
): ImageBitmap? = withContext(Dispatchers.IO) {
    if (sourceUri.isNullOrBlank()) return@withContext null
    val retriever = MediaMetadataRetriever()
    try {
        if (sourceUri.startsWith("http://") || sourceUri.startsWith("https://")) {
            retriever.setDataSource(sourceUri, emptyMap())
        } else {
            retriever.setDataSource(context, Uri.parse(sourceUri))
        }
        val bitmap = retriever.getFrameAtTime(
            positionMs.coerceAtLeast(0L) * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return@withContext null
        val scaled = if (bitmap.width > 320) {
            val targetHeight = (bitmap.height * (320f / bitmap.width)).toInt().coerceAtLeast(1)
            android.graphics.Bitmap.createScaledBitmap(bitmap, 320, targetHeight, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else bitmap
        scaled.asImageBitmap()
    } catch (_: Throwable) {
        null
    } finally {
        retriever.release()
    }
}
