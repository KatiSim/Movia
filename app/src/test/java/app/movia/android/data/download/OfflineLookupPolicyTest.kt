package app.movia.android.data.download

import app.movia.android.domain.model.MediaRef
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OfflineLookupPolicyTest {
    @Test fun missingRemakeDoesNotReadTheLegacyFileOfTheSameTitle() {
        val directory = Files.createTempDirectory("movia-offline-identity").toFile()
        try {
            val legacy = directory.resolve("same-title.mp4").apply { writeText("other year") }
            var legacyRead = false
            val result = OfflineLookupPolicy.find(MediaRef("remake-2025"), { directory.resolve("2025.mp4").takeIf { it.exists() } }) {
                legacyRead = true
                legacy
            }
            assertNull(result)
            assertFalse(legacyRead)
            assertEquals("other year", legacy.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun deletingOneEpisodePreservesOtherEpisodesAndUnboundLegacyArtifacts() {
        val directory = Files.createTempDirectory("movia-offline-delete").toFile()
        try {
            val exact = directory.resolve("s1e2.mp4").apply { writeText("episode two") }
            val neighbour = directory.resolve("s1e1.mp4").apply { writeText("episode one") }
            val legacy = directory.resolve("same-title.mp4").apply { writeText("unbound") }
            OfflineLookupPolicy.candidates(MediaRef("series", 1, 2), exact, legacy).forEach { it.delete() }
            assertFalse(exact.exists())
            assertEquals("episode one", neighbour.readText())
            assertEquals("unbound", legacy.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun boundDownloadUsesItsExactWorkRecordEvenIfLegacyLooksSuccessful() {
        assertEquals("RUNNING_EXACT", OfflineLookupPolicy.find(MediaRef("42"), { "RUNNING_EXACT" }) { "SUCCEEDED_OTHER_YEAR" })
        assertNull(OfflineLookupPolicy.find<String>(MediaRef("42"), { null }) { "SUCCEEDED_OTHER_YEAR" })
    }
    @Test fun unresolvedLegacyDownloadsRemainAddressable() {
        assertEquals("unbound-file", OfflineLookupPolicy.find<String>(null, { null }) { "unbound-file" })
        assertEquals(setOf("current-unbound", "older-unbound"), OfflineLookupPolicy.candidates(null, "current-unbound", "older-unbound"))
    }
}
