package app.movia.android.domain.backend

import app.movia.android.domain.playback.MeasuredSourceEvidence
import org.json.JSONObject

/** Used by both catalog reads and the active on-demand playback boundary. */
fun sourcePlaybackEvidence(row: JSONObject): MeasuredSourceEvidence {
    val truth = row.optJSONObject("sourceTruth")
    return MeasuredSourceEvidence(
        sourceId = truth?.optString("sourceId")?.takeIf { it.isNotBlank() && it != "null" },
        status = truth?.optString("verificationStatus"),
        method = truth?.optString("verificationMethod"),
        actualQuality = truth?.optString("actualQuality")?.takeIf { it.isNotBlank() && it != "null" },
        actualQualities = truth?.optJSONArray("actualQualities")?.let { values ->
            (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() && it != "null" }
        }.orEmpty(),
    )
}
