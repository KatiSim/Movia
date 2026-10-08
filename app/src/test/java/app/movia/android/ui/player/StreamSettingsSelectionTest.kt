package app.movia.android.ui.player

import app.movia.android.domain.model.StreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSettingsSelectionTest {

    @Test fun selected240pOverridesPrevious1080pRequest() {
        val selected=StreamOption(voice="Original",quality="240p",url="https://example.test/240")
        assertEquals("240p",StreamSettingsSelection.requestedQualityForSwitch(selected,"1080p",true))
    }
    @Test fun voiceOnlySwitchRetainsRequestedQualityAndAuto() {
        val selected=StreamOption(voice="Studio",quality="720p",url="https://example.test/720")
        assertEquals("1080p",StreamSettingsSelection.requestedQualityForSwitch(selected,"1080p",false))
        assertEquals("Auto",StreamSettingsSelection.requestedQualityForSwitch(selected,"Auto",false))
    }
    @Test fun ExplicitAdaptiveVariantDoesNotInheritGlobal1080p() {
        val selected=StreamOption(voice="Studio",quality="Auto",url="https://example.test/master.m3u8")
        assertEquals("Auto",StreamSettingsSelection.requestedQualityForSwitch(selected,"1080p",true))
    }
    @Test fun UnknownSelectedQualityDoesNotBecomeAClaimOf1080p() {
        val selected=StreamOption(voice="Studio",quality="Не указано",url="https://example.test/file")
        assertEquals("Auto",StreamSettingsSelection.requestedQualityForSwitch(selected,"1080p",true))
    }

    @Test fun failedFirstLeafDoesNotHideHealthySameVoiceAndQuality() {
        val failed = StreamOption("Studio", "720p", url = "https://example.test/failed", streamId = "failed",
            transportMetadata = mapOf("playback_verification_status" to "COOLDOWN"))
        val healthy = failed.copy(url = "https://example.test/healthy", streamId = "healthy", transportMetadata = emptyMap())
        assertEquals(healthy, StreamSettingsSelection.select(listOf(failed, healthy), "Studio", "720p"))
        assertEquals(listOf("720p"), StreamSettingsSelection.qualityOptions(listOf(failed, healthy)))
    }
    @Test fun rememberedFailedLeafIsNotRetriedByAVoiceChange() {
        val failed = StreamOption("Studio", "720p", url = "https://example.test/a", streamId = "failed")
        val healthy = failed.copy(url = "https://example.test/b", streamId = "healthy")
        assertEquals(healthy, StreamSettingsSelection.select(listOf(failed, healthy), "Studio", "720p", setOf("failed")))
        assertNull(StreamSettingsSelection.select(listOf(failed), "Studio", "720p", setOf("failed")))
    }
    @Test fun decoderVerifiedAlternativeWinsWithinSameVoiceQuality() {
        val advertised = StreamOption("Studio", "720p", url = "https://example.test/a", streamId = "advertised")
        val decoded = advertised.copy(url = "https://example.test/b", streamId = "decoded",
            transportMetadata = mapOf("playback_verification_status" to "VERIFIED",
                "playback_verification_method" to "MEDIA3_SUCCESS", "playback_decoded" to "true"))
        assertEquals(decoded, StreamSettingsSelection.select(listOf(advertised, decoded), "Studio", "720p"))
        assertEquals(decoded, StreamSettingsSelection.select(listOf(decoded, advertised), "Studio", "720p"))
    }
    @Test fun healthRankingDoesNotReplaceTheUsersAvailableVoice() {
        val requested = StreamOption("Studio A", "720p", url = "https://example.test/a", streamId = "a", healthScore = 0.4)
        val other = requested.copy(voice = "Studio B", url = "https://example.test/b", streamId = "b", healthScore = 1.0)
        assertEquals(requested, StreamSettingsSelection.select(listOf(other, requested), "Studio A", "720p"))
    }
    @Test fun allUnavailableAlternativesAreNotReportedAsSelected() {
        val expired = StreamOption("Studio", "720p", url = "https://example.test/a", streamId = "a",
            transportMetadata = mapOf("playback_verification_status" to "EXPIRED"))
        assertNull(StreamSettingsSelection.select(listOf(expired), "Studio", "720p"))
    }
    @Test fun wholeProfileAndTrackIdentityBelongToTheSelectedHealthyLeaf() {
        val bad = StreamOption("Studio", "720p", url = "https://example.test/shared", streamId = "bad",
            headers = mapOf("Referer" to "https://example.test/bad"), audioTrackIndex = 1,
            transportMetadata = mapOf("playback_verification_status" to "FAILED"))
        val good = bad.copy(streamId = "good", headers = mapOf("Referer" to "https://example.test/good"),
            audioTrackIndex = 3, transportMetadata = emptyMap())
        assertEquals(good, StreamSettingsSelection.select(listOf(bad, good), "Studio", "720p"))
    }

    private val streams = listOf(
        StreamOption(voice = "LostFilm", quality = "1080p", url = "https://a.example/lf-1080", source = "p"),
        StreamOption(voice = "Кубик в Кубе", quality = "720p", url = "https://a.example/kubik-720", source = "p"),
        StreamOption(voice = "Кубик в Кубе", quality = "1080p", url = "https://a.example/kubik-1080", source = "p"),
    )

    @Test
    fun qualityOptionsComeFirstAndAreSortedLowToHigh() {
        val mixed = streams + listOf(
            StreamOption(voice = "Studio", quality = "4K", url = "https://a.example/4k", source = "p"),
            StreamOption(voice = "Studio", quality = "360p", url = "https://a.example/360", source = "p"),
            StreamOption(voice = "Studio", quality = "240p", url = "https://a.example/240", source = "p"),
        )
        assertEquals(listOf("240p", "360p", "720p", "1080p", "4K"), StreamSettingsSelection.qualityOptions(mixed))
    }

    @Test
    fun voiceOptionsAreScopedToSelectedQuality() {
        assertEquals(listOf("Кубик в Кубе"), StreamSettingsSelection.voiceOptions(streams, "720p"))
        assertEquals(listOf("LostFilm", "Кубик в Кубе"), StreamSettingsSelection.voiceOptions(streams, "1080p"))
    }

    @Test
    fun exactVoiceAndQualitySelectsWholeStreamOption() {
        assertEquals(
            "https://a.example/kubik-1080",
            StreamSettingsSelection.select(streams, "Кубик в Кубе", "1080p")?.url,
        )
    }

    @Test
    fun missingVoiceFallsBackToRequestedQualityWithoutFabricatingTrackLabel() {
        assertEquals(
            "https://a.example/lf-1080",
            StreamSettingsSelection.select(streams, "Unknown studio", "1080p")?.url,
        )
        assertNull(StreamSettingsSelection.select(emptyList(), "LostFilm", "1080p"))
    }

    @Test fun knownVoiceIsKeptWhenQualityIsUnavailableForIt() {
        assertEquals("LostFilm", StreamSettingsSelection.select(streams, "LostFilm", "720p")?.voice)
        assertEquals("1080p", StreamSettingsSelection.select(streams, "LostFilm", "720p")?.quality)
    }
    @Test fun adaptiveVoiceIsNotHiddenByAnotherVoicesFixedQuality() {
        val adaptive=StreamOption("Studio A","Auto",url="https://a.example/master.m3u8",transport="hls",audioTrackIndex=0)
        val fixed=StreamOption("Studio B","720p",url="https://b.example/movie.mp4")
        assertEquals(listOf("Studio A","Studio B"),StreamSettingsSelection.voiceOptions(listOf(adaptive,fixed),"720p"))
        assertEquals(adaptive,StreamSettingsSelection.select(listOf(fixed,adaptive),"Studio A","720p"))
    }
    @Test fun noCompatibleQualityDoesNotOfferUnrelatedFixedVoices() {
        assertEquals(emptyList<String>(),StreamSettingsSelection.voiceOptions(streams,"360p"))
    }
    @Test fun sourceQualityAliasesAreConsistentAcrossMenusAndSelection() {
        for((label,height) in listOf("FullHD" to 1080,"HD" to 720,"4K" to 2160,"1920x1080" to 1080,"576p" to 576,"240p" to 240)) {
            assertEquals(height,qualityHeight(label))
            val source=StreamOption("Studio",label,url="https://a.example/movie")
            assertEquals(source,StreamSettingsSelection.select(listOf(source),"Studio","${height}p"))
            assertEquals(listOf("Studio"),StreamSettingsSelection.voiceOptions(listOf(source),"${height}p"))
        }
    }
    @Test fun generatedMatricesKeepVoiceQualityPairsAcrossDifferentProviders() {
        val random=java.util.Random(20261002L)
        repeat(500) { item ->
            val rows=buildList {
                for(studio in 0 until 3+random.nextInt(8)) for(height in listOf(240,360,480,720,1080,2160))
                    if(random.nextBoolean()) add(StreamOption("Studio $studio","${height}p",url="https://p${studio%3}.example/$item/$studio/$height"))
            }
            for(row in rows) {
                val chosen=StreamSettingsSelection.select(rows,row.voice,row.quality)
                assertEquals(row,chosen)
                org.junit.Assert.assertTrue(StreamSettingsSelection.voiceOptions(rows,row.quality).contains(row.voice))
            }
        }
    }
    @Test fun preparedQualitiesAreNotAssignedToAnotherHeaderProfile() {
        val prepared=StreamOption("Studio A","360p",url="https://media.example/master.m3u8",headers=mapOf("Referer" to "https://a.example"))
        val other=prepared.copy(voice="Studio B",headers=mapOf("Referer" to "https://b.example"))
        assertEquals(listOf("Studio A"),StreamSettingsSelection.voiceOptions(listOf(prepared,other),"720p",prepared.url,setOf(360,720),prepared))
    }

    @Test fun manualQualityAfterFallbackAdoptsPreparedLeafWithoutRebindingEpisode() {
        val r=app.movia.android.domain.playback.PlaybackRequest(mediaId="42",title="Series",seasonNumber=1,episodeNumber=2,requestedStreamId="failed-480",requestedVoice="Studio",requestedQuality="480p")
        val q=StreamSettingsSelection.withPreparedQuality(r,"240p","prepared-240")
        assertEquals("prepared-240",q.requestedStreamId);assertEquals("240p",q.requestedQuality)
        assertEquals(r.copy(requestedQuality="240p",requestedStreamId="prepared-240"),q)
    }
    @Test fun explicitAutoAfterFallbackDoesNotRetainOldFailedLeafPin() {
        val r=app.movia.android.domain.playback.PlaybackRequest(mediaId="42",title="Film",requestedStreamId="failed")
        val q=StreamSettingsSelection.withPreparedQuality(r,"Auto","playing")
        assertEquals("playing",q.requestedStreamId);assertEquals("Auto",q.requestedQuality)
    }
    @Test fun absentPreparedLeafDoesNotInventAnotherLogicalSource() {
        val r=app.movia.android.domain.playback.PlaybackRequest(mediaId="42",title="Film",requestedStreamId="known")
        assertEquals("known",StreamSettingsSelection.withPreparedQuality(r,"Auto",null).requestedStreamId)
    }
}
