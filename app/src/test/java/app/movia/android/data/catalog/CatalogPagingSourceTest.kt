package app.movia.android.data.catalog

import androidx.paging.PagingSource
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CatalogPagingSourceTest {
    @Test
    fun appendStartsAtOffsetAfterLastServerRow() = runBlocking {
        val repository = FakePagedCatalogRepository { offset ->
            when (offset) {
                0 -> CatalogPage(listOf(media("a"), media("b")), total = 3)
                2 -> CatalogPage(listOf(media("c")), total = 3)
                else -> error("Unexpected offset: $offset")
            }
        }
        val source = source(repository)

        val first = source.load(refresh()) as PagingSource.LoadResult.Page
        assertEquals(listOf("a", "b"), first.data.map(MediaContent::id))
        assertEquals(2, first.nextKey)

        val second = source.load(append(first.nextKey!!)) as PagingSource.LoadResult.Page
        assertEquals(listOf("c"), second.data.map(MediaContent::id))
        assertNull(second.nextKey)
        assertEquals(listOf(0, 2), repository.requestedOffsets)
    }

    @Test
    fun recommendationsSkipUnmatchedServerPagesAndAdvanceByServerRows() = runBlocking {
        val repository = FakePagedCatalogRepository { offset ->
            when (offset) {
                0 -> CatalogPage(listOf(media("a"), media("b")), total = 4)
                2 -> CatalogPage(listOf(media("c"), media("d")), total = 4)
                else -> error("Unexpected offset: $offset")
            }
        }
        val progress = mutableListOf<CatalogPagingProgress>()
        val source = source(repository, recommendedIds = setOf("d"), progress::add)

        val result = source.load(refresh()) as PagingSource.LoadResult.Page

        assertEquals(listOf("d"), result.data.map(MediaContent::id))
        assertNull(result.nextKey)
        assertEquals(listOf(0, 2), repository.requestedOffsets)
        assertEquals(4, progress.last().nextOffset)
        assertFalse(progress.last().hasMore)
    }

    @Test
    fun restoredSnapshotIsServedBeforeRepositoryIsCalled() = runBlocking {
        val repository = FakePagedCatalogRepository { error("Repository must not be called for restored page") }
        val restored = CatalogPagingSnapshot(
            items = listOf(media("a"), media("b")),
            totalCount = 5,
            nextOffset = 2,
            hasMore = true,
        )
        val result = source(repository, restored = restored).load(refresh()) as PagingSource.LoadResult.Page

        assertEquals(listOf("a", "b"), result.data.map(MediaContent::id))
        assertEquals(2, result.nextKey)
        assertTrue(repository.requestedOffsets.isEmpty())
    }

    private fun source(
        repository: FakePagedCatalogRepository,
        recommendedIds: Set<String> = emptySet(),
        onProgress: (CatalogPagingProgress) -> Unit = {},
        restored: CatalogPagingSnapshot? = null,
    ) = CatalogPagingSource(
        repository = repository,
        query = CatalogLoadQuery(
            sort = CatalogSort.POPULAR,
            category = null,
            filter = CatalogFilter(type = null),
            recommendedIds = recommendedIds,
        ),
        restored = restored,
        onProgress = onProgress,
    )

    private fun refresh() = PagingSource.LoadParams.Refresh<Int>(
        key = null,
        loadSize = CatalogPagingSource.PAGE_SIZE,
        placeholdersEnabled = false,
    )

    private fun append(key: Int) = PagingSource.LoadParams.Append(
        key = key,
        loadSize = CatalogPagingSource.PAGE_SIZE,
        placeholdersEnabled = false,
    )

    private fun media(id: String) = MediaContent(
        id = id,
        title = id,
        type = ContentType.MOVIE,
        year = 2025,
        rating = 8.0,
        genres = emptySet(),
        country = "",
        quality = "1080p",
        durationMinutes = 90,
        category = CatalogCategory.MOVIES,
    )
}

private class FakePagedCatalogRepository(
    private val pageForOffset: (Int) -> CatalogPage,
) : CatalogRepository {
    val requestedOffsets = mutableListOf<Int>()

    override suspend fun getCatalogPage(
        limit: Int,
        offset: Int,
        sort: CatalogSort,
        category: CatalogCategory?,
        filter: CatalogFilter?,
        query: String?,
    ): CatalogPage {
        requestedOffsets += offset
        return pageForOffset(offset)
    }

    override suspend fun getPopular(limit: Int): List<MediaContent> = emptyList()
    override suspend fun getNew(limit: Int): List<MediaContent> = emptyList()
    override suspend fun getPaged(
        limit: Int,
        offset: Int,
        sort: CatalogSort,
        category: CatalogCategory?,
        filter: CatalogFilter?,
        query: String?,
    ): List<MediaContent> = emptyList()
    override suspend fun getRecommendationCandidates(
        genres: Set<String>,
        directors: Set<String>,
        excludedIds: Set<String>,
        limit: Int,
    ): List<MediaContent> = emptyList()
    override suspend fun getSimilar(current: MediaContent, limit: Int): List<MediaContent> = emptyList()
    override suspend fun getSequelsAndPrequels(movieId: String, limit: Int): List<MediaContent> = emptyList()
    override fun getAllGenres(): List<String> = emptyList()
    override suspend fun search(query: String, limit: Int): List<MediaContent> = emptyList()
    override suspend fun searchFts(query: String, limit: Int): List<MediaContent> = emptyList()
    override suspend fun searchPeople(query: String, limit: Int): List<app.movia.android.domain.model.Person> = emptyList()
    override fun findByTitle(title: String): MediaContent? = null
    override fun findById(id: String): MediaContent? = null
}
