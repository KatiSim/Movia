package app.movia.android.domain.provider

import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.DomainPlaybackResolver
import app.movia.android.domain.playback.StreamDeduplicator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Movia-owned contracts. No reference application classes or bytecode are loaded. */
data class MoviaProviderArticle(
    val providerId: String,
    val itemId: String,
    val catalogMediaId: String,
    val year: Int?,
    val isSeries: Boolean,
)
sealed interface MoviaVariantNode {
    data class Folder(val label: String, val children: List<MoviaVariantNode>) : MoviaVariantNode
    data class Deferred(val reference: String, val load: suspend () -> List<MoviaVariantNode>) : MoviaVariantNode
    data class Leaf(val candidate: StreamCandidate) : MoviaVariantNode
}
interface MoviaProviderAdapter {
    val id: String
    suspend fun search(request: PlaybackRequest): List<MoviaProviderArticle>
    suspend fun variants(article: MoviaProviderArticle, request: PlaybackRequest): MoviaVariantNode
    suspend fun variants(article: MoviaProviderArticle, request: PlaybackRequest,
        publish: suspend (List<StreamCandidate>) -> Unit): MoviaVariantNode = variants(article, request)
}
data class MoviaProviderResult(
    val candidates: List<StreamCandidate>,
    val statuses: Map<String, String>,
)

/**
 * Runs independent adapters concurrently and publishes every completed inventory.
 * Budgets constrain work, never the number of voices or quality menu entries.
 * A leaf must carry its own verified episode coordinates; folders do not fabricate them.
 */
class MoviaProviderRegistry(
    private val adapters: List<MoviaProviderAdapter>,
    private val parallelism: Int = 3,
    private val providerBudgetMs: Long = 12_000,
    private val totalBudgetMs: Long = 15_000,
    private val maximumNodes: Int = 20_000,
) {
    init {
        require(adapters.map { it.id }.distinct().size == adapters.size)
        require(parallelism > 0 && providerBudgetMs > 0 && totalBudgetMs > 0 && maximumNodes > 0)
    }

    suspend fun discover(
        request: PlaybackRequest,
        publish: suspend (List<StreamCandidate>) -> Unit = {},
    ): MoviaProviderResult {
        require(request.mediaId.isNotBlank()) { "EXACT_CATALOG_ID_REQUIRED" }
        val requiresEpisode = request.mediaType == app.movia.android.domain.model.ContentType.SERIES ||
            request.seasonNumber != null || request.episodeNumber != null
        if (requiresEpisode && ((request.seasonNumber ?: 0) <= 0 || (request.episodeNumber ?: 0) <= 0))
            return MoviaProviderResult(emptyList(), adapters.associate { it.id to "EXACT_EPISODE_REQUIRED" })
        val mutex = Mutex()
        val semaphore = Semaphore(parallelism)
        val found = linkedMapOf<String, List<StreamCandidate>>()
        val statuses = linkedMapOf<String, String>()
        withTimeoutOrNull(totalBudgetMs) {
            supervisorScope {
                adapters.map { adapter ->
                    async {
                        semaphore.withPermit {
                            val result = withTimeoutOrNull(providerBudgetMs) {
                                try {
                                    val articles = adapter.search(request).filter { article ->
                                        article.providerId == adapter.id &&
                                        article.catalogMediaId == request.mediaId &&
                                        article.isSeries == request.isSeries &&
                                        (request.year == null || article.year == request.year)
                                    }.distinctBy { it.itemId }
                                    if (articles.size != 1) {
                                        mutex.withLock { statuses[adapter.id] = if (articles.isEmpty()) "NO_EXACT_MATCH" else "AMBIGUOUS" }
                                        return@withTimeoutOrNull emptyList<StreamCandidate>()
                                    }
                                    flatten(adapter.variants(articles.single(), request) { incoming ->
                                        val safe = DomainPlaybackResolver.validatedCandidates(request, incoming)
                                        if (safe.isNotEmpty()) mutex.withLock {
                                            found[adapter.id] = StreamDeduplicator.deduplicate(
                                                DomainPlaybackResolver.preferDiscoveredCandidates(
                                                    found[adapter.id].orEmpty(), safe, request))
                                            publish(StreamDeduplicator.deduplicate(found.values.flatten()))
                                        }
                                    }, request)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: VariantLimitException) {
                                    mutex.withLock { statuses[adapter.id] = "VARIANT_LIMIT" }
                                    emptyList()
                                } catch (error: Exception) {
                                    mutex.withLock { statuses[adapter.id] = "PROVIDER_ERROR" }
                                    emptyList()
                                }
                            }
                            mutex.withLock {
                                if (result == null) statuses[adapter.id] = "TIMEOUT"
                                else {
                                    statuses.putIfAbsent(adapter.id, if (result.isEmpty()) "UNAVAILABLE" else "RESOLVED")
                                    found[adapter.id] = StreamDeduplicator.deduplicate(
                                        DomainPlaybackResolver.preferDiscoveredCandidates(found[adapter.id].orEmpty(), result, request))
                                    if (found[adapter.id].orEmpty().isNotEmpty()) publish(StreamDeduplicator.deduplicate(found.values.flatten()))
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return mutex.withLock {
            adapters.forEach { statuses.putIfAbsent(it.id, "TIMEOUT") }
            MoviaProviderResult(StreamDeduplicator.deduplicate(found.values.flatten()), statuses.toMap())
        }
    }

    private suspend fun flatten(root: MoviaVariantNode, request: PlaybackRequest): List<StreamCandidate> {
        val leaves = mutableListOf<StreamCandidate>()
        var visited = 0
        suspend fun visit(node: MoviaVariantNode, path: Set<String>, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (++visited > maximumNodes || depth > 64) throw VariantLimitException()
            when (node) {
                is MoviaVariantNode.Leaf -> leaves += DomainPlaybackResolver.validatedCandidates(request, listOf(node.candidate))
                is MoviaVariantNode.Folder -> node.children.forEach { visit(it, path, depth + 1) }
                is MoviaVariantNode.Deferred -> {
                    if (node.reference in path) throw VariantLimitException()
                    node.load().forEach { visit(it, path + node.reference, depth + 1) }
                }
            }
        }
        visit(root, emptySet(), 0)
        return StreamDeduplicator.deduplicate(leaves)
    }

    private class VariantLimitException : Exception()
}
