@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.movia.android.data.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import app.movia.android.domain.model.MediaRef
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A completed adaptive download is a cache and track keys, never a fake MP4. */
object OfflineMediaStore {
    data class Selection(val voice: String, val quality: String, val audioIndex: Int?, val audioLabel: String?)
    private val caches = linkedMapOf<String, SimpleCache>()
    private var database: StandaloneDatabaseProvider? = null
    fun key(ref: MediaRef): String = OfflineDownloadWorker.fileNameFor(ref, "").removeSuffix(".mp4")
    fun marker(context: Context, ref: MediaRef): File = File(context.filesDir, "offline/" + key(ref) + ".offline")

    @Synchronized
    fun cache(context: Context, ref: MediaRef): SimpleCache {
        return caches.getOrPut(key(ref)) {
            val provider = database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
            SimpleCache(File(context.filesDir, "offline/" + key(ref) + ".cache"), NoOpCacheEvictor(), provider)
        }
    }

    fun request(context: Context, ref: MediaRef): DownloadRequest? = runCatching {
        val file = marker(context, ref)
        if (!file.isFile || file.length() > 128_000L) return null
        val directory = File(context.filesDir, "offline/" + key(ref) + ".cache")
        if (!directory.isDirectory || cache(context, ref).cacheSpace <= 0L) return null
        val json = JSONObject(file.readText())
        if (json.getString("mediaKey") != ref.storageKey || !json.getBoolean("complete")) return null
        val keys = json.getJSONArray("streamKeys")
        DownloadRequest.Builder(ref.storageKey, Uri.parse(json.getString("uri")))
            .setMimeType(json.optString("mimeType").takeIf { it.isNotBlank() })
            .setStreamKeys((0 until keys.length()).map { i -> keys.getJSONArray(i).let {
                StreamKey(it.getInt(0), it.getInt(1), it.getInt(2))
            } }).build()
    }.getOrNull()

    fun selection(context: Context, ref: MediaRef): Selection? = runCatching {
        val file = marker(context, ref)
        if (!file.isFile || file.length() > 128_000L) return null
        val json = JSONObject(file.readText())
        if (json.getString("mediaKey") != ref.storageKey || !json.getBoolean("complete")) return null
        Selection(json.optString("voice", "Офлайн"), json.optString("quality", "Auto"),
            json.optInt("audioIndex", -1).takeIf { it >= 0 }, json.optString("audioLabel").takeIf { it.isNotBlank() })
    }.getOrNull()

    fun complete(context: Context, ref: MediaRef, request: DownloadRequest, voice: String, quality: String, audioIndex: Int? = null, audioLabel: String? = null): File {
        val file = marker(context, ref)
        file.parentFile?.mkdirs()
        val data = JSONObject().put("version", 2).put("complete", true).put("mediaKey", ref.storageKey)
            .put("uri", request.uri.toString()).put("mimeType", request.mimeType ?: "")
            .put("voice", voice).put("quality", quality).put("audioIndex", audioIndex).put("audioLabel", audioLabel).put("streamKeys", JSONArray().apply {
                request.streamKeys.forEach { put(JSONArray(listOf(it.periodIndex, it.groupIndex, it.streamIndex))) }
            })
        val temporary = File(file.parentFile, file.name + ".part")
        temporary.writeText(data.toString())
        check(temporary.renameTo(file)) { "Could not finalize offline metadata" }
        return file
    }

    fun playbackFactory(context: Context, ref: MediaRef): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context, ref)).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)

    @Synchronized
    fun delete(context: Context, ref: MediaRef): Boolean {
        caches.remove(key(ref))?.release()
        val file = marker(context, ref)
        val directory = File(context.filesDir, "offline/" + key(ref) + ".cache")
        return (!file.exists() || file.delete()) && (!directory.exists() || directory.deleteRecursively())
    }

    @Synchronized
    fun releaseAll() { caches.values.forEach { it.release() }; caches.clear() }
}
