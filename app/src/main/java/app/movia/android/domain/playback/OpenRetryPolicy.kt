package app.movia.android.domain.playback

/**
 * Zona's DataSource wrapper retries one failed open after rebuilding the
 * underlying request. Movia keeps the same bounded shape without putting
 * provider/network resolution logic onto Media3's loader thread.
 */
internal inline fun <T> openWithSingleRetry(
    resetBeforeRetry: () -> Unit,
    open: () -> T,
): T {
    return try {
        open()
    } catch (_: Exception) {
        resetBeforeRetry()
        open()
    }
}
