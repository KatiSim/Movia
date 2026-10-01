package app.movia.android.data.catalog

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.MediaContent

data class CatalogPagingSnapshot(
    val items: List<MediaContent>,
    val totalCount: Int,
    val nextOffset: Int,
    val hasMore: Boolean,
)

data class CatalogPagingProgress(
    val totalCount: Int,
    val nextOffset: Int,
    val hasMore: Boolean,
    val warning: String? = null,
)

/** Offset-backed source. It advances by server rows, even when a client-side preset filters rows out. */
class CatalogPagingSource(
    private val repository: CatalogRepository,
    private val query: CatalogLoadQuery,
    private val restored: CatalogPagingSnapshot?,
    private val onProgress: (CatalogPagingProgress) -> Unit,
) : PagingSource<Int, MediaContent>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaContent> {
        val requestedOffset = (params.key ?: 0).coerceAtLeast(0)
        if (params.key == null && restored != null) {
            val snapshot = restored
            onProgress(
                CatalogPagingProgress(
                    totalCount = snapshot.totalCount,
                    nextOffset = snapshot.nextOffset,
                    hasMore = snapshot.hasMore,
                ),
            )
            return LoadResult.Page(
                data = snapshot.items,
                prevKey = null,
                nextKey = snapshot.nextOffset.takeIf { snapshot.hasMore && it > 0 },
                itemsAfter = (snapshot.totalCount - snapshot.nextOffset).coerceAtLeast(0),
            )
        }

        return try {
            var offset = requestedOffset
            var page: CatalogPage
            var visibleItems: List<MediaContent>
            var hasMore: Boolean
            do {
                page = repository.getCatalogPage(
                    limit = PAGE_SIZE,
                    offset = offset,
                    sort = query.sort,
                    category = query.category,
                    filter = query.filter,
                    query = query.query,
                )
                visibleItems = if (query.recommendedIds.isEmpty()) {
                    page.items
                } else {
                    page.items.filter { it.id in query.recommendedIds }
                }
                offset += page.items.size
                hasMore = page.items.isNotEmpty() &&
                    (if (page.total > 0) offset < page.total else page.items.size >= PAGE_SIZE)
                if (visibleItems.isNotEmpty() || !hasMore || query.recommendedIds.isEmpty()) break
            } while (true)

            onProgress(
                CatalogPagingProgress(
                    totalCount = page.total,
                    nextOffset = offset,
                    hasMore = hasMore,
                    warning = page.errorMessage,
                ),
            )
            LoadResult.Page(
                data = visibleItems,
                prevKey = null,
                nextKey = offset.takeIf { hasMore },
                itemsAfter = (page.total - offset).coerceAtLeast(0),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, MediaContent>): Int? = null

    companion object {
        const val PAGE_SIZE = 40
    }
}

data class CatalogLoadQuery(
    val sort: CatalogSort,
    val category: CatalogCategory?,
    val filter: CatalogFilter,
    val recommendedIds: Set<String>,
    val query: String? = null,
)
