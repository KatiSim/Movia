package app.movia.android.domain.provider

import app.movia.android.domain.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MoviaProviderRegistryTest {

    @Test fun backendFailureRemainsProviderErrorRatherThanUnavailability() = runBlocking {
        val service=MoviaBackendProviderAdapter { PlaybackResolverResult.Error("Backend failed") }
        val result=MoviaProviderRegistry(listOf(service)).discover(request)
        assertEquals("PROVIDER_ERROR",result.statuses[service.id])
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun backendNoSourceIsUnavailableAndDoesNotFabricateLeaves() = runBlocking {
        val service=MoviaBackendProviderAdapter { PlaybackResolverResult.NoSource("No source") }
        val result=MoviaProviderRegistry(listOf(service)).discover(request)
        assertEquals("UNAVAILABLE",result.statuses[service.id])
        assertTrue(result.candidates.isEmpty())
    }

    private val request = PlaybackRequest(mediaId="77", title="Example", year=2024)
    private fun candidate(id: String, voice: String="Studio", quality: String="720p", mediaId: String="77") =
        StreamCandidate(stableStreamId=id, provider="Fixture", url="https://cdn.example/$id.mp4",
            voice=voice, quality=quality, catalogMediaId=mediaId, canonicalTitle="Example", canonicalYear=2024,
            audioTrackIndex=0)
    private fun adapter(id: String, node: MoviaVariantNode, wait: suspend () -> Unit = {}): MoviaProviderAdapter =
        object : MoviaProviderAdapter {
            override val id=id
            override suspend fun search(request: PlaybackRequest): List<MoviaProviderArticle> {
                wait()
                return listOf(MoviaProviderArticle(id,"article",request.mediaId,request.year,request.isSeries))
            }
            override suspend fun variants(article: MoviaProviderArticle, request: PlaybackRequest)=node
        }

    @Test fun preservesAllVoiceQualityCombinationsAndTrackZero() = runBlocking {
        val leaves=(0 until 12).flatMap { v -> (0 until 8).map { q ->
            MoviaVariantNode.Leaf(candidate("$v-$q","Studio $v","${240+q*120}p"))
        } }
        val result=MoviaProviderRegistry(listOf(adapter("one",MoviaVariantNode.Folder("all",leaves)))).discover(request)
        assertEquals(96,result.candidates.size)
        assertEquals(12,result.candidates.map { it.voice }.distinct().size)
        assertTrue(result.candidates.all { it.audioTrackIndex==0 })
    }

    @Test fun combinesProvidersInsteadOfReturningFirstSuccess() = runBlocking {
        val result=MoviaProviderRegistry(listOf(
            adapter("one",MoviaVariantNode.Leaf(candidate("one","Studio A"))),
            adapter("two",MoviaVariantNode.Leaf(candidate("two","Studio B"))))).discover(request)
        assertEquals(setOf("Studio A","Studio B"),result.candidates.map { it.voice }.toSet())
    }

    @Test fun deferredBranchesResolveThroughOwnLoader() = runBlocking {
        var loaded=0
        val node=MoviaVariantNode.Deferred("voices") { loaded++;listOf(MoviaVariantNode.Leaf(candidate("resolved"))) }
        val result=MoviaProviderRegistry(listOf(adapter("one",node))).discover(request)
        assertEquals(1,loaded)
        assertEquals("resolved",result.candidates.single().stableStreamId)
    }

    @Test fun foreignIdentityCannotBecomeRequestedMovie() = runBlocking {
        val result=MoviaProviderRegistry(listOf(adapter("one",MoviaVariantNode.Leaf(candidate("bad",mediaId="78"))))).discover(request)
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun ambiguousArticlesNeverPickFirstResult() = runBlocking {
        val provider=object : MoviaProviderAdapter {
            override val id="one"
            override suspend fun search(request: PlaybackRequest)=listOf("a","b").map {
                MoviaProviderArticle(id,it,request.mediaId,request.year,false) }
            override suspend fun variants(article: MoviaProviderArticle,request: PlaybackRequest): MoviaVariantNode =
                error("Must not resolve ambiguous article")
        }
        val result=MoviaProviderRegistry(listOf(provider)).discover(request)
        assertEquals("AMBIGUOUS",result.statuses["one"])
    }

    @Test fun fasterProviderPublishesWhileOtherOneIsPending() = runBlocking {
        val published=CompletableDeferred<Unit>()
        val slow=adapter("slow",MoviaVariantNode.Leaf(candidate("slow"))) { published.await() }
        val fast=adapter("fast",MoviaVariantNode.Leaf(candidate("fast")))
        val snapshots=mutableListOf<List<String>>()
        val result=MoviaProviderRegistry(listOf(slow,fast)).discover(request) { rows ->
            snapshots+=rows.map { it.stableStreamId };published.complete(Unit)
        }
        assertEquals(listOf("fast"),snapshots.first())
        assertEquals(2,result.candidates.size)
    }

    @Test fun timedOutProviderCannotStarveWorkingProvider() = runBlocking {
        val slow=adapter("slow",MoviaVariantNode.Leaf(candidate("slow"))) { delay(500) }
        val fast=adapter("fast",MoviaVariantNode.Leaf(candidate("fast")))
        val result=MoviaProviderRegistry(listOf(slow,fast),providerBudgetMs=30,totalBudgetMs=1000).discover(request)
        assertEquals(listOf("fast"),result.candidates.map { it.stableStreamId })
        assertEquals("TIMEOUT",result.statuses["slow"])
    }

    @Test fun cyclicDeferredTreeDoesNotHangOrReportCompleteInventory() = runBlocking {
        lateinit var node: MoviaVariantNode.Deferred
        node=MoviaVariantNode.Deferred("same") { listOf(node) }
        val result=MoviaProviderRegistry(listOf(adapter("one",node))).discover(request)
        assertEquals("VARIANT_LIMIT",result.statuses["one"])
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun missingEpisodeIdentityRejectsSeriesCardBeforeSearch() = runBlocking {
        val result=MoviaProviderRegistry(listOf(adapter("one",MoviaVariantNode.Leaf(candidate("one")))))
            .discover(request.copy(mediaType=app.movia.android.domain.model.ContentType.SERIES))
        assertEquals("EXACT_EPISODE_REQUIRED",result.statuses["one"])
    }
    @Test fun partialProviderPublishesBeforeCompletionAndKeepsRowsAfterBudget() = runBlocking {
        val provider=object: MoviaProviderAdapter {
            override val id="progressive"
            override suspend fun search(request:PlaybackRequest)=listOf(MoviaProviderArticle(id,"article",request.mediaId,request.year,request.isSeries))
            override suspend fun variants(article:MoviaProviderArticle,request:PlaybackRequest):MoviaVariantNode=error("Progressive route required")
            override suspend fun variants(article:MoviaProviderArticle,request:PlaybackRequest,publish:suspend(List<StreamCandidate>)->Unit):MoviaVariantNode {
                publish(listOf(candidate("cached"),candidate("foreign",mediaId="78")))
                delay(500)
                return MoviaVariantNode.Leaf(candidate("late"))
            }
        }
        val seen=mutableListOf<List<String>>()
        val result=MoviaProviderRegistry(listOf(provider),providerBudgetMs=100,totalBudgetMs=1000).discover(request) { rows -> seen+=rows.map { it.stableStreamId } }
        assertEquals(listOf("cached"),seen.first())
        assertEquals("cached",result.candidates.single().stableStreamId)
        assertEquals("TIMEOUT",result.statuses[provider.id])
    }

    @Test fun partialProviderRefreshesLocatorAndAddsLateVoice() = runBlocking {
        val provider=object: MoviaProviderAdapter {
            override val id="progressive"
            override suspend fun search(request:PlaybackRequest)=listOf(MoviaProviderArticle(id,"article",request.mediaId,request.year,request.isSeries))
            override suspend fun variants(article:MoviaProviderArticle,request:PlaybackRequest):MoviaVariantNode=error("Progressive route required")
            override suspend fun variants(article:MoviaProviderArticle,request:PlaybackRequest,publish:suspend(List<StreamCandidate>)->Unit):MoviaVariantNode {
                publish(listOf(candidate("cached")))
                return MoviaVariantNode.Folder("all",listOf(MoviaVariantNode.Leaf(candidate("cached").copy(url="https://cdn.example/fresh.mp4")),
                    MoviaVariantNode.Leaf(candidate("late",voice="Studio B"))))
            }
        }
        val seen=mutableListOf<List<String>>()
        val result=MoviaProviderRegistry(listOf(provider)).discover(request) { rows -> seen+=rows.map { it.stableStreamId } }
        assertEquals(listOf("cached"),seen.first())
        assertEquals(setOf("cached","late"),seen.last().toSet())
        assertEquals("https://cdn.example/fresh.mp4",result.candidates.first { it.stableStreamId=="cached" }.url)
    }
}
