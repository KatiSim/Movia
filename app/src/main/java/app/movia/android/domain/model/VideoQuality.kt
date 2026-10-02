package app.movia.android.domain.model

/** One quality vocabulary for provider menus, Media3 preferences and downloads. */
internal fun videoQualityHeight(value: String?): Int? {
    val text = value.orEmpty().trim().lowercase()
    val dimensions = Regex("""(?:^|\D)(\d{2,5})\s*[xх×]\s*(\d{2,5})(?:$|\D)""").find(text)
    dimensions?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    return when {
        Regex("""\b(?:8k|4320p?)\b""").containsMatchIn(text) -> 4320
        Regex("""\b(?:4k|uhd|2160p?)\b""").containsMatchIn(text) -> 2160
        Regex("""\b(?:2k|1440p?)\b""").containsMatchIn(text) -> 1440
        text == "fullhd" || text == "full hd" || text == "fhd" -> 1080
        text == "hd" -> 720
        text == "sd" -> 480
        else -> Regex("""(?<!\d)(\d{3,4})p?(?!\d)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }
}
