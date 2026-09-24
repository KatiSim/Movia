package app.movia.android.ui.components

import android.os.Build
import android.os.Trace
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import java.util.concurrent.atomic.AtomicInteger

private const val MOVIA_ARTWORK_TRACE = "Movia.Artwork"
private val nextArtworkTraceCookie = AtomicInteger(1)

private class MoviaArtworkTraceListener : ImageRequest.Listener {
    private val traceCookie = AtomicInteger(0)

    override fun onStart(request: ImageRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cookie = nextArtworkTraceCookie.getAndIncrement()
        traceCookie.set(cookie)
        Trace.beginAsyncSection(MOVIA_ARTWORK_TRACE, cookie)
    }

    override fun onCancel(request: ImageRequest) = finishTrace()

    override fun onError(request: ImageRequest, result: ErrorResult) = finishTrace()

    override fun onSuccess(request: ImageRequest, result: SuccessResult) = finishTrace()

    private fun finishTrace() {
        val cookie = traceCookie.getAndSet(0)
        if (cookie != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection(MOVIA_ARTWORK_TRACE, cookie)
        }
    }
}

internal fun ImageRequest.Builder.traceMoviaArtwork(): ImageRequest.Builder =
    listener(MoviaArtworkTraceListener())

/**
 * Shared artwork surface for posters and hero images. AsyncImage resolves the request size
 * from this composable's constraints, cancels work when the target leaves composition, and
 * uses the application's single bounded Coil cache.
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
    val context = LocalContext.current
    val normalizedUrl = remember(url) { normalizeMoviaArtworkUrl(url) }
    val request = remember(context, normalizedUrl) {
        normalizedUrl?.let { imageUrl ->
            ImageRequest.Builder(context)
                .data(imageUrl)
                .traceMoviaArtwork()
                .build()
        }
    }

    Box(modifier = modifier) {
        MediaArtworkPlaceholder(
            modifier = Modifier.fillMaxSize(),
            style = placeholderStyle,
        )

        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )

        overlay()
    }
}

/** Converts API-relative TMDB artwork paths into loadable image URLs. */
internal fun normalizeMoviaArtworkUrl(url: String?): String? {
    val trimmed = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return if (trimmed.startsWith('/') && !trimmed.startsWith("//")) {
        "https://image.tmdb.org/t/p/w500$trimmed"
    } else {
        trimmed
    }
}
