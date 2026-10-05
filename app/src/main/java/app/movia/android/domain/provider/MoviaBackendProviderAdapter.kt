package app.movia.android.domain.provider

import app.movia.android.domain.playback.*

/**
 * Android adapter for Movia's owned provider service. The service performs
 * registry -> exact search -> article -> deferred VariantTree resolution.
 * Android receives concrete leaves and retains their track and transport data.
 */
class MoviaBackendProviderAdapter(
    private val resolve: suspend (PlaybackRequest) -> PlaybackResolverResult = {
        DomainPlaybackResolver.resolveStreams(it, forceRefresh = true)
    },
) : MoviaProviderAdapter {
    override val id = "movia:provider-service"

    override suspend fun search(request: PlaybackRequest): List<MoviaProviderArticle> =
        if (request.mediaId.isBlank()) emptyList()
        else listOf(MoviaProviderArticle(id,request.canonicalEpisodeKey,request.mediaId,request.year,request.isSeries))

    override suspend fun variants(article: MoviaProviderArticle, request: PlaybackRequest): MoviaVariantNode {
        require(article.catalogMediaId==request.mediaId && article.isSeries==request.isSeries &&
            article.year==request.year) { "ARTICLE_IDENTITY_MISMATCH" }
        val result=resolve(request)
        val candidates=if (result is PlaybackResolverResult.Success) result.candidates else emptyList()
        return MoviaVariantNode.Folder("Providers",
            candidates.groupBy { it.providerId ?: it.provider }.map { (provider, rows) ->
                MoviaVariantNode.Folder(provider,rows.groupBy { it.voice }.map { (voice, voices) ->
                    MoviaVariantNode.Folder(voice,voices.groupBy { it.quality }.map { (quality, qualities) ->
                        MoviaVariantNode.Folder(quality,qualities.map { MoviaVariantNode.Leaf(it) })
                    })
                })
            })
    }
}
