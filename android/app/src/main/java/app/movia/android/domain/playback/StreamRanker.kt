package app.movia.android.domain.playback

object StreamRanker {
    private fun voicePreferenceScore(voice: String): Int {
        val v = voice.lowercase()
        return when {
            v.contains("дубляж") || v.contains("дублирован") || v.contains("dub.ru") -> 0
            v.contains("lostfilm") -> 1
            v.contains("red head sound") || v.contains("rhs") -> 2
            v.contains("hdrezka") || v.contains("rezka") -> 3
            v.contains("кубик") -> 4
            v.contains("кураж") -> 5
            v.contains("newstudio") -> 6
            v.contains("jaskier") || v.contains("яскьер") -> 6
            v.contains("alexfilm") || v.contains("tvshows") || v.contains("flarrow") || v.contains("le-vitation") -> 6
            v.contains("профессиональн") || v.contains("мво") || v.contains("двухголос") || v.contains("дво") -> 7
            v.contains("русск") || v.contains("rus") -> 8
            v.contains("укра") || v.contains("ukr") || v.contains("dnipro") -> 15
            v.contains("original") || v.contains("english") -> 20
            v.contains("не указано") -> 25
            else -> 10
        }
    }

    private fun qualityRank(quality: String): Int {
        val q = quality.lowercase()
        return when {
            q.contains("2160") || q.contains("4k") -> 2160
            q.contains("1440") -> 1440
            q.contains("1080") -> 1080
            q.contains("720") -> 720
            q.contains("480") -> 480
            q.contains("360") -> 360
            else -> 0
        }
    }

    private fun defaultQualityRank(quality: String): Int {
        val q = quality.lowercase()
        return when {
            q.contains("1080") -> 0
            q.contains("720") -> 1
            q.contains("2160") || q.contains("4k") -> 2
            q.contains("480") -> 3
            q.contains("360") -> 4
            else -> 5
        }
    }

    private fun isDirectStream(candidate: StreamCandidate): Boolean {
        val u = candidate.url.lowercase()
        return (u.startsWith("http://") || u.startsWith("https://")) &&
            !u.contains("127.0.0.1:8888/stream?") &&
            !u.contains("localhost:8888/stream?")
    }

    private fun providerPreferenceRank(candidate: StreamCandidate): Int {
        val p = candidate.provider.lowercase()
        return when {
            isDirectStream(candidate) || p.contains("zona") -> 0
            p == "rutor" -> 1
            p == "yts" -> 2
            p == "apibay" -> 3
            else -> 4
        }
    }

    fun rankCandidates(
        candidates: List<StreamCandidate>,
        failedStreamIds: Set<String> = emptySet(),
    ): List<StreamCandidate> {
        return candidates.sortedWith(
            compareBy<StreamCandidate> { if (failedStreamIds.contains(it.stableStreamId) || it.isProblematic) 1 else 0 }
                .thenBy { if (isDirectStream(it)) 0 else 1 }
                .thenBy { voicePreferenceScore(it.voice) }
                .thenBy { providerPreferenceRank(it) }
                .thenBy { defaultQualityRank(it.quality) }
                .thenByDescending { it.seeders }
        )
    }

    fun selectBest(
        candidates: List<StreamCandidate>,
        requestedVoice: String?,
        requestedQuality: String?,
        failedStreamIds: Set<String> = emptySet(),
    ): StreamCandidate? {
        val healthy = candidates.filter { !failedStreamIds.contains(it.stableStreamId) && !it.isProblematic }
        val pool = if (healthy.isNotEmpty()) healthy else candidates
        if (pool.isEmpty()) return null

        val reqVoice = requestedVoice?.trim()?.takeUnless { it.isBlank() || it.equals("Auto", ignoreCase = true) }
        val reqQuality = requestedQuality?.trim()?.takeUnless { it.isBlank() || it.equals("Auto", ignoreCase = true) }

        if (reqVoice != null && reqQuality != null) {
            val exactBoth = pool.firstOrNull {
                it.voice.equals(reqVoice, ignoreCase = true) &&
                    (it.quality.equals(reqQuality, ignoreCase = true) || qualityRank(it.quality) == qualityRank(reqQuality))
            }
            if (exactBoth != null) return exactBoth
        }

        if (reqVoice != null) {
            val exactVoice = pool.filter { it.voice.equals(reqVoice, ignoreCase = true) }
            if (exactVoice.isNotEmpty()) {
                return rankCandidates(exactVoice, failedStreamIds).firstOrNull()
            }
        }

        if (reqQuality != null) {
            val exactQuality = pool.filter {
                it.quality.equals(reqQuality, ignoreCase = true) ||
                    (qualityRank(it.quality) == qualityRank(reqQuality) && qualityRank(reqQuality) > 0)
            }
            if (exactQuality.isNotEmpty()) {
                return rankCandidates(exactQuality, failedStreamIds).firstOrNull()
            }
        }

        return rankCandidates(pool, failedStreamIds).firstOrNull()
    }
}
