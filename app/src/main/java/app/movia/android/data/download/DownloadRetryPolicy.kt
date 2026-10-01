package app.movia.android.data.download

internal enum class DownloadHttpAction { REFRESH_SOURCE, RETRY, FAIL }
internal fun downloadHttpAction(code: Int, attempt: Int, refreshed: Boolean): DownloadHttpAction = when {
    code in setOf(401,403,410) && !refreshed -> DownloadHttpAction.REFRESH_SOURCE
    (code in setOf(408,429) || code in 500..599) && attempt < 2 -> DownloadHttpAction.RETRY
    else -> DownloadHttpAction.FAIL
}
internal fun boundedRetryAfterMillis(header: String?, now: Long = System.currentTimeMillis()): Long {
    val seconds = header?.trim()?.toLongOrNull()
    val millis = seconds?.coerceIn(0,300)?.times(1000) ?: runCatching {
        (java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceIn(0,300_000)
    }.getOrDefault(0)
    return millis
}
