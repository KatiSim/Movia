package app.movia.android.domain.playback

object StreamDeduplicator {
    fun normalizeVoice(rawVoice: String?): String {
        val trimmed = rawVoice?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("unknown", ignoreCase = true) || trimmed.equals("none", ignoreCase = true)) {
            return "Не указано"
        }
        return trimmed
    }

    fun normalizeQuality(rawQuality: String?): String {
        val trimmed = rawQuality?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("unknown", ignoreCase = true) || trimmed.equals("none", ignoreCase = true)) {
            return "Не указано"
        }
        return trimmed
    }

    fun deduplicate(candidates: List<StreamCandidate>): List<StreamCandidate> {
        val result = mutableListOf<StreamCandidate>()
        val indexes = mutableMapOf<String, Int>()

        for (candidate in candidates) {
            val normalizedVoice = normalizeVoice(candidate.voice)
            val normalizedQuality = normalizeQuality(candidate.quality)
            val clean = candidate.copy(voice = normalizedVoice, quality = normalizedQuality)
            val semanticKey = clean.variantIdentity()
            val existingIndex = indexes[semanticKey]
            if (existingIndex == null) {
                indexes[semanticKey] = result.size
                result.add(clean)
                continue
            }

            // Mirror/extractor fan-out can return the same locator with
            // different metadata completeness. Keep the first stable identity
            // and merge useful transport/health fields instead of dropping them.
            val existing = result[existingIndex]
            result[existingIndex] = existing.copy(
                seeders = maxOf(existing.seeders, clean.seeders),
                providerItemId = existing.providerItemId ?: clean.providerItemId,
                infoHash = existing.infoHash ?: clean.infoHash,
                fileIndex = existing.fileIndex ?: clean.fileIndex,
                filePath = existing.filePath ?: clean.filePath,
                language = if (existing.language.isNotBlank()) existing.language else clean.language,
                codec = existing.codec ?: clean.codec,
                mimeType = existing.mimeType ?: clean.mimeType,
                drmScheme = existing.drmScheme ?: clean.drmScheme,
                drmLicenseUrl = existing.drmLicenseUrl ?: clean.drmLicenseUrl,
                userAgent = existing.userAgent ?: clean.userAgent,
                headers = existing.headers + clean.headers,
                subtitles = (existing.subtitles + clean.subtitles).distinctBy { it.url + "|" + it.language },
                hasInternalSubtitles = existing.hasInternalSubtitles || clean.hasInternalSubtitles,
                videoTrackIndex = existing.videoTrackIndex ?: clean.videoTrackIndex,
                audioTrackIndex = existing.audioTrackIndex ?: clean.audioTrackIndex,
                durationMs = existing.durationMs ?: clean.durationMs,
                sizeBytes = existing.sizeBytes ?: clean.sizeBytes,
                reloadSupported = existing.reloadSupported || clean.reloadSupported,
                reloadData = existing.reloadData ?: clean.reloadData,
                logicalSourceId = existing.logicalSourceId ?: clean.logicalSourceId,
                sourceTypeId = existing.sourceTypeId ?: clean.sourceTypeId,
                contentTypeId = existing.contentTypeId ?: clean.contentTypeId,
                sourceId = existing.sourceId ?: clean.sourceId,
                providerId = existing.providerId ?: clean.providerId,
                providerContentId = existing.providerContentId ?: clean.providerContentId,
                transportMetadata = existing.transportMetadata + clean.transportMetadata,
                resolution = existing.resolution ?: clean.resolution,
                resolutionWidth = existing.resolutionWidth ?: clean.resolutionWidth,
                resolutionHeight = existing.resolutionHeight ?: clean.resolutionHeight,
                downloadUrl = existing.downloadUrl ?: clean.downloadUrl,
                downloadHeaders = existing.downloadHeaders + clean.downloadHeaders,
                skipIntervals = if (existing.skipIntervals.isNotEmpty()) existing.skipIntervals else clean.skipIntervals,
                advertisement = existing.advertisement ?: clean.advertisement,
                catalogMediaId = existing.catalogMediaId ?: clean.catalogMediaId,
                canonicalTitle = existing.canonicalTitle ?: clean.canonicalTitle,
                canonicalOriginalTitle = existing.canonicalOriginalTitle ?: clean.canonicalOriginalTitle,
                canonicalYear = existing.canonicalYear ?: clean.canonicalYear,
                canonicalMediaType = existing.canonicalMediaType ?: clean.canonicalMediaType,
                healthScore = maxOf(existing.healthScore, clean.healthScore),
                startupLatencyMs = listOfNotNull(existing.startupLatencyMs, clean.startupLatencyMs).minOrNull(),
                recentFailureCount = maxOf(existing.recentFailureCount, clean.recentFailureCount),
                providerReliability = existing.providerReliability ?: clean.providerReliability,
                isProblematic = existing.isProblematic || clean.isProblematic,
            )
        }
        return result
    }
}
