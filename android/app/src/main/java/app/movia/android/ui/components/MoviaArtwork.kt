package app.movia.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Authoritative Movia artwork surface.
 *
 * All ordinary posters and hero artwork use the same memory + disk cache so a card that
 * was already shown on Home can be reused by Catalog, Search and Library without every
 * composable opening its own network connection. A truthful Movia placeholder is kept
 * whenever the source is absent or cannot be decoded.
 */
@Composable
fun MoviaArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderStyle: MediaArtworkPlaceholderStyle = MediaArtworkPlaceholderStyle.POSTER,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val decodeSampleSize = if (placeholderStyle == MediaArtworkPlaceholderStyle.POSTER) 2 else 1
    var bitmap by remember(url, decodeSampleSize) {
        mutableStateOf(MoviaArtworkLoader.peek(url, decodeSampleSize))
    }

    LaunchedEffect(url, decodeSampleSize) {
        bitmap = if (url.isNullOrBlank()) null else {
            MoviaArtworkLoader.load(appContext, url, decodeSampleSize)
        }
    }

    Box(modifier = modifier) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MediaArtworkPlaceholder(
                modifier = Modifier.fillMaxSize(),
                style = placeholderStyle,
            )
        }
        overlay()
    }
}

private object MoviaArtworkLoader {
    private const val MAX_NETWORK_BYTES = 12 * 1024 * 1024
    private val memoryCache = object : LruCache<String, Bitmap>(memoryBudgetKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            ((value.allocationByteCount / 1024).coerceAtLeast(1))
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            "https://image.tmdb.org/t/p/w500$trimmed"
        } else {
            trimmed
        }
    }

    private fun memoryKey(target: String, sampleSize: Int): String =
        "$target#sample=${sampleSize.coerceAtLeast(1)}"

    fun peek(url: String?, sampleSize: Int): Bitmap? {
        val target = normalizeUrl(url) ?: return null
        val key = memoryKey(target, sampleSize)
        return synchronized(memoryCache) { memoryCache.get(key) }
    }

    suspend fun load(context: Context, url: String, sampleSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        val target = normalizeUrl(url) ?: return@withContext null
        val safeSampleSize = sampleSize.coerceAtLeast(1)
        val key = memoryKey(target, safeSampleSize)
        synchronized(memoryCache) { memoryCache.get(key) }?.let { return@withContext it }

        // Home can render the same artwork in several rails at once. Without
        // request coalescing every composable can race through the empty cache
        // and download the same image independently. One URL now has one owner
        // request; all duplicate callers await that result.
        val owner = CompletableDeferred<Bitmap?>()
        val existing = inFlight.putIfAbsent(key, owner)
        if (existing != null) return@withContext existing.await()

        val result = try {
            val cacheDir = File(context.cacheDir, "movia_artwork").apply { mkdirs() }
            val cacheFile = File(cacheDir, sha256(target))

            decodeFile(cacheFile, safeSampleSize)?.also { decoded ->
                synchronized(memoryCache) { memoryCache.put(key, decoded) }
            } ?: run {
                val bytes = download(target) ?: return@run null
                val decoded = decodeBytes(bytes, safeSampleSize) ?: return@run null
                runCatching {
                    val temp = File(cacheDir, "${cacheFile.name}.tmp-${Thread.currentThread().id}")
                    temp.outputStream().use { it.write(bytes) }
                    if (!temp.renameTo(cacheFile)) {
                        cacheFile.outputStream().use { it.write(bytes) }
                        temp.delete()
                    }
                }
                synchronized(memoryCache) { memoryCache.put(key, decoded) }
                decoded
            }
        } catch (_: Throwable) {
            null
        }
        owner.complete(result)
        inFlight.remove(key, owner)
        result
    }

    private fun decodeFile(file: File, sampleSize: Int): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) },
            )
        }.getOrNull() ?: run {
            file.delete()
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray, sampleSize: Int): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) },
        )
    }.getOrNull()

    private fun download(rawUrl: String): ByteArray? = runCatching {
        val connection = URL(rawUrl.replace(" ", "%20")).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", "Movia/0.3")
        connection.setRequestProperty("Accept", "image/*")
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@runCatching null
            val declared = connection.contentLengthLong
            if (declared > MAX_NETWORK_BYTES) return@runCatching null

            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    if (declared in 1..MAX_NETWORK_BYTES.toLong()) declared.toInt() else 64 * 1024,
                )
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_NETWORK_BYTES) return@runCatching null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun memoryBudgetKb(): Int {
        val availableKb = (Runtime.getRuntime().maxMemory() / 1024L / 12L).toInt()
        return availableKb.coerceIn(8 * 1024, 64 * 1024)
    }
}
