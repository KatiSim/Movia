package app.movia.android.data.catalog

import android.content.Context
import android.util.Log
import android.util.LruCache
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import app.movia.android.domain.model.StreamAdvertisement
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.StreamSkipInterval
import app.movia.android.domain.model.StreamSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

interface CatalogRepository {
    fun getPopular(limit: Int = 40): List<MediaContent>
    fun getNew(limit: Int = 40): List<MediaContent>
    fun getPaged(
        limit: Int = 40,
        offset: Int = 0,
        sort: CatalogSort = CatalogSort.POPULAR,
        category: CatalogCategory? = null,
        filter: CatalogFilter? = null,
        query: String? = null,
    ): List<MediaContent>
    fun getTotalCount(
        category: CatalogCategory? = null,
        filter: CatalogFilter? = null,
        query: String? = null,
    ): Int
    fun getRecommendationCandidates(
        genres: Set<String>,
        directors: Set<String>,
        excludedIds: Set<String>,
        limit: Int = 240,
    ): List<MediaContent>
    fun getSimilar(current: MediaContent, limit: Int = 8): List<MediaContent>
    fun getSequelsAndPrequels(movieId: String, limit: Int = 15): List<MediaContent>
    fun getAllGenres(): List<String>
    fun search(query: String, limit: Int = 20): List<MediaContent>
    fun searchFts(query: String, limit: Int = 30): List<MediaContent>
    fun searchPeople(query: String, limit: Int = 20): List<Person>
    fun findByTitle(title: String): MediaContent?
    fun findById(id: String): MediaContent?

    /** Compatibility defaults for lightweight repositories and tests. */
    fun all(): List<MediaContent> = getPaged(limit = Int.MAX_VALUE)
    fun findFullById(id: String): MediaContent? = findById(id)
    fun findFullByTitle(title: String): MediaContent? = findByTitle(title)
}

enum class SearchStatus {
    OK,
    EMPTY_QUERY,
    NO_RESULTS,
    NETWORK_ERROR,
    BACKEND_DOWN,
    PROVIDER_TIMEOUT,
    RATE_LIMIT,
    INVALID_RESPONSE,
    DB_ERROR,
    BACKEND_ERROR,
}

data class CatalogSearchResult(
    val status: SearchStatus,
    val items: List<MediaContent> = emptyList(),
    val people: List<Person> = emptyList(),
    val total: Int = 0,
    val weakLocal: Boolean = false,
    val errorMessage: String? = null,
    val source: String = "LOCAL",
)

private data class CatalogHttpResponse(
    val code: Int,
    val body: String?,
    val errorMessage: String? = null,
)

object DemoCatalogRepository : CatalogRepository {
    private const val TAG = "HttpCatalogRepo"
    private const val BASE_URL = "http://127.0.0.1:8888"
    private const val HOME_REFRESH_MS = 5 * 60 * 1000L

    @Volatile
    private var lastHomeFetchMs: Long = 0L

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)
    private val homeRefreshInFlight = AtomicBoolean(false)
    private val genresRefreshInFlight = AtomicBoolean(false)

    private val movieCache = LruCache<String, MediaContent>(500)
    private val titleCache = LruCache<String, MediaContent>(500)

    @Volatile
    private var cachedPopular: List<MediaContent> = emptyList()

    @Volatile
    private var cachedNew: List<MediaContent> = emptyList()

    @Volatile
    private var cachedAnimations: List<MediaContent> = emptyList()

    @Volatile
    private var cachedSeries: List<MediaContent> = emptyList()

    @Volatile
    private var cachedForYou: List<MediaContent> = emptyList()

    @Volatile
    private var cachedHero: List<MediaContent> = emptyList()

    @Volatile
    private var cachedGenres: List<String> = emptyList()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            prewarm()
        }
    }

    fun prewarm() {
        refreshHomeAsync()
        refreshGenresAsync()
    }

    private fun refreshHomeAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val hasHomeData = cachedPopular.isNotEmpty() || cachedNew.isNotEmpty() || cachedSeries.isNotEmpty()
        if (!force && hasHomeData && now - lastHomeFetchMs < HOME_REFRESH_MS) return
        if (!homeRefreshInFlight.compareAndSet(false, true)) return
        repositoryScope.launch {
            try {
                fetchHome(force)
            } catch (e: Exception) {
                Log.w(TAG, "Home refresh failed: ${e.message}")
            } finally {
                homeRefreshInFlight.set(false)
            }
        }
    }

    private fun refreshGenresAsync() {
        if (cachedGenres.isNotEmpty() || !genresRefreshInFlight.compareAndSet(false, true)) return
        repositoryScope.launch {
            try {
                fetchAllGenres()
            } catch (e: Exception) {
                Log.w(TAG, "Genres refresh failed: ${e.message}")
            } finally {
                genresRefreshInFlight.set(false)
            }
        }
    }

    private fun fetchHome(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val hasHomeData = cachedPopular.isNotEmpty() || cachedNew.isNotEmpty() || cachedSeries.isNotEmpty()
        if (!force && hasHomeData && now - lastHomeFetchMs < HOME_REFRESH_MS) return
        val jsonStr = httpGet("/api/home") ?: return
        try {
            val json = JSONObject(jsonStr)
            val popArr = json.optJSONArray("popular")
            val newArr = json.optJSONArray("newReleases") ?: json.optJSONArray("new")
            val serArr = json.optJSONArray("series")
            val forYouArr = json.optJSONArray("forYou") ?: json.optJSONArray("for_you")
            val heroArr = json.optJSONArray("heroBanners") ?: json.optJSONArray("hero")
            val featuredObj = json.optJSONObject("featured")

            val popList = parseMediaList(popArr)
            val newList = parseMediaList(newArr)
            val serList = parseMediaList(serArr)
            val forYouList = parseMediaList(forYouArr)
            val heroList = parseMediaList(heroArr)
            val featuredList = listOfNotNull(featuredObj?.let(::parseMediaObject))

            val sectionsArr = json.optJSONArray("sections")
            val sectionItems = mutableListOf<MediaContent>()
            if (sectionsArr != null) {
                for (i in 0 until sectionsArr.length()) {
                    val secObj = sectionsArr.optJSONObject(i) ?: continue
                    sectionItems.addAll(parseMediaList(secObj.optJSONArray("items")))
                }
            }

            if (popList.isNotEmpty()) cachedPopular = popList
            if (newList.isNotEmpty()) cachedNew = newList
            if (serList.isNotEmpty()) cachedSeries = serList
            if (forYouList.isNotEmpty()) cachedForYou = forYouList
            if (heroList.isNotEmpty()) cachedHero = heroList

            (popList + newList + serList + forYouList + heroList + featuredList + sectionItems).forEach { cacheItem(it) }
            lastHomeFetchMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing /api/home", e)
        }
    }

    private fun fetchAllGenres() {
        val jsonStr = httpGet("/api/genres") ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            if (list.isNotEmpty()) cachedGenres = list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing /api/genres", e)
        }
    }

    private fun cacheItem(item: MediaContent) {
        movieCache.put(item.id, item)
        titleCache.put(CanonicalTextNormalizer.normalize(item.title), item)
    }

    private fun httpGet(path: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(if (path.startsWith("http")) path else "$BASE_URL$path")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 12_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} for $path")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP GET failed for $path: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun <T> runSafe(block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Repository error", e)
            throw e
        }
    }

    override fun getPaged(
        limit: Int,
        offset: Int,
        sort: CatalogSort,
        category: CatalogCategory?,
        filter: CatalogFilter?,
        query: String?,
    ): List<MediaContent> = runSafe {
        val params = StringBuilder("/api/catalog?limit=$limit&offset=$offset")
        params.append("&sort=${sort.name}")
        category?.let { params.append("&category=${it.name}") }
        filter?.let { f ->
            if (f.genres.isNotEmpty()) params.append("&genre=${URLEncoder.encode(f.genres.first(), "UTF-8")}")
            f.yearFrom?.let { params.append("&yearFrom=$it") }
            f.yearTo?.let { params.append("&yearTo=$it") }
            f.minRating?.let { params.append("&minRating=$it") }
            f.type?.let { type ->
                val mediaType = if (type == ContentType.SERIES || type == ContentType.TV) "tv" else "movie"
                params.append("&mediaType=$mediaType")
            }
            f.country?.let { params.append("&country=${URLEncoder.encode(it, "UTF-8")}") }
        }
        query?.takeIf { it.isNotBlank() }?.let {
            params.append("&query=${URLEncoder.encode(it.trim(), "UTF-8")}")
        }

        val resp = httpGet(params.toString())
        val items = parseCatalogResponse(resp).first
        items.forEach { cacheItem(it) }
        items
    }

    override fun getTotalCount(
        category: CatalogCategory?,
        filter: CatalogFilter?,
        query: String?,
    ): Int = runSafe {
        val params = StringBuilder("/api/catalog?limit=1&offset=0")
        category?.let { params.append("&category=${it.name}") }
        filter?.let { f ->
            if (f.genres.isNotEmpty()) params.append("&genre=${URLEncoder.encode(f.genres.first(), "UTF-8")}")
            f.yearFrom?.let { params.append("&yearFrom=$it") }
            f.yearTo?.let { params.append("&yearTo=$it") }
            f.minRating?.let { params.append("&minRating=$it") }
            f.type?.let { type ->
                val mediaType = if (type == ContentType.SERIES || type == ContentType.TV) "tv" else "movie"
                params.append("&mediaType=$mediaType")
            }
            f.country?.let { params.append("&country=${URLEncoder.encode(it, "UTF-8")}") }
        }
        query?.takeIf { it.isNotBlank() }?.let {
            params.append("&query=${URLEncoder.encode(it.trim(), "UTF-8")}")
        }

        val resp = httpGet(params.toString())
        parseCatalogResponse(resp).second
    }

    override fun getPopular(limit: Int): List<MediaContent> = runSafe {
        refreshHomeAsync()
        cachedPopular.take(limit)
    }

    override fun getNew(limit: Int): List<MediaContent> = runSafe {
        refreshHomeAsync()
        cachedNew.take(limit)
    }

    fun getSeries(limit: Int = 12): List<MediaContent> = runSafe {
        refreshHomeAsync()
        cachedSeries.take(limit)
    }

    fun getForYou(limit: Int = 12): List<MediaContent> = runSafe {
        refreshHomeAsync()
        cachedForYou.take(limit)
    }

    fun getHero(limit: Int = 3): List<MediaContent> = runSafe {
        refreshHomeAsync()
        cachedHero.take(limit)
    }

    private fun cachedCatalogItems(): List<MediaContent> =
        (cachedPopular + cachedNew + cachedAnimations + cachedSeries + cachedForYou + cachedHero)
            .distinctBy { it.id }

    override fun all(): List<MediaContent> {
        val list = cachedCatalogItems()
        if (list.isNotEmpty()) return list
        return getPopular(40)
    }

    override fun getRecommendationCandidates(
        genres: Set<String>,
        directors: Set<String>,
        excludedIds: Set<String>,
        limit: Int,
    ): List<MediaContent> = runSafe {
        val genreParam = genres.firstOrNull()?.let { "&genre=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val resp = httpGet("/api/catalog?limit=$limit&sort=RATING$genreParam")
        val items = parseCatalogResponse(resp).first
        items.filter { it.id !in excludedIds }
    }

    override fun getSimilar(current: MediaContent, limit: Int): List<MediaContent> = runSafe {
        val resp = httpGet("/api/movie/${current.id}")
        if (resp != null) {
            val json = JSONObject(resp)
            val simArr = json.optJSONArray("similar")
            val items = parseMediaList(simArr)
            items.forEach { cacheItem(it) }
            items.take(limit)
        } else {
            emptyList()
        }
    }

    override fun getSequelsAndPrequels(movieId: String, limit: Int): List<MediaContent> = runSafe {
        val resp = httpGet("/api/movie/$movieId")
        if (resp != null) {
            val json = JSONObject(resp)
            val seqArr = json.optJSONArray("sequels_and_prequels") ?: json.optJSONArray("sequels")
            val items = parseMediaList(seqArr)
            items.forEach { cacheItem(it) }
            items.take(limit)
        } else {
            emptyList()
        }
    }

    override fun getAllGenres(): List<String> {
        if (cachedGenres.isNotEmpty()) return cachedGenres
        refreshGenresAsync()
        return listOf("аниме", "боевик", "детектив", "драма", "комедия", "мультфильм", "триллер", "ужасы", "фантастика")
    }

    private fun httpGetDetailed(path: String): CatalogHttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(if (path.startsWith("http")) path else "$BASE_URL$path")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 12_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            CatalogHttpResponse(code = code, body = body)
        } catch (e: Exception) {
            CatalogHttpResponse(code = 0, body = null, errorMessage = e.message)
        } finally {
            conn?.disconnect()
        }
    }

    private fun searchStatus(raw: String?): SearchStatus = when (raw?.uppercase()) {
        "OK" -> SearchStatus.OK
        "EMPTY_QUERY" -> SearchStatus.EMPTY_QUERY
        "NO_RESULTS" -> SearchStatus.NO_RESULTS
        "NETWORK_ERROR" -> SearchStatus.NETWORK_ERROR
        "BACKEND_DOWN" -> SearchStatus.BACKEND_DOWN
        "PROVIDER_TIMEOUT" -> SearchStatus.PROVIDER_TIMEOUT
        "RATE_LIMIT" -> SearchStatus.RATE_LIMIT
        "INVALID_RESPONSE" -> SearchStatus.INVALID_RESPONSE
        "DB_ERROR" -> SearchStatus.DB_ERROR
        else -> SearchStatus.BACKEND_ERROR
    }

    fun searchDetailed(
        query: String,
        limit: Int = 20,
        discover: Boolean = false,
    ): CatalogSearchResult {
        val normalized = CanonicalTextNormalizer.normalize(query)
        if (normalized.isBlank()) {
            return CatalogSearchResult(SearchStatus.EMPTY_QUERY)
        }
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val discoverParam = if (discover) 1 else 0
        val response = httpGetDetailed(
            "/api/search?query=$encoded&limit=$limit&discover=$discoverParam",
        )
        val body = response.body
        if (body.isNullOrBlank()) {
            val cached = searchCatalogLocally(cachedCatalogItems(), normalized, limit)
            return if (cached.isNotEmpty()) {
                CatalogSearchResult(SearchStatus.OK, items = cached, source = "CACHE")
            } else {
                val status = when {
                    response.code == 429 -> SearchStatus.RATE_LIMIT
                    response.code == 408 || response.code == 504 -> SearchStatus.PROVIDER_TIMEOUT
                    response.code >= 500 -> SearchStatus.BACKEND_DOWN
                    else -> SearchStatus.NETWORK_ERROR
                }
                CatalogSearchResult(
                    status = status,
                    errorMessage = response.errorMessage ?: ("HTTP " + response.code),
                )
            }
        }
        return try {
            val json = JSONObject(body)
            val items = parseMediaList(json.optJSONArray("movies"))
            val people = mutableListOf<Person>()
            json.optJSONArray("people")?.let { arr ->
                for (index in 0 until arr.length()) {
                    val person = arr.optJSONObject(index) ?: continue
                    people += Person(
                        name = person.optString("name"),
                        photoUrl = person.optString("photo_url")
                            .takeIf { it.isNotBlank() }
                            ?: person.optString("photoUrl").takeIf { it.isNotBlank() },
                        role = person.optString("role").takeIf { it.isNotBlank() },
                    )
                }
            }
            items.forEach(::cacheItem)
            CatalogSearchResult(
                status = searchStatus(json.optString("status", "BACKEND_ERROR")),
                items = items,
                people = people,
                total = json.optInt("total", items.size),
                weakLocal = json.optBoolean("weakLocal", false),
                errorMessage = json.optString("error").takeIf { it.isNotBlank() },
                source = json.optString("source", "LOCAL"),
            )
        } catch (e: Exception) {
            CatalogSearchResult(
                status = SearchStatus.INVALID_RESPONSE,
                errorMessage = e.message ?: "Invalid search response",
            )
        }
    }

    override fun search(query: String, limit: Int): List<MediaContent> =
        searchDetailed(query, limit, discover = true).items

    override fun searchFts(query: String, limit: Int): List<MediaContent> =
        searchDetailed(query, limit, discover = false).items

    override fun searchPeople(query: String, limit: Int): List<Person> =
        searchDetailed(query, limit, discover = true).people

    /**
     * Fast cache-only lookup. UI composition and player surface attachment must
     * never perform implicit network I/O. Call findFullBy* explicitly from an
     * IO dispatcher when complete metadata is required.
     */
    override fun findByTitle(title: String): MediaContent? =
        titleCache.get(CanonicalTextNormalizer.normalize(title))

    /** See findByTitle: this is deliberately cache-only. */
    override fun findById(id: String): MediaContent? = movieCache.get(id)

    override fun findFullById(id: String): MediaContent? = runSafe {
        val resp = httpGet("/api/movie/$id") ?: return@runSafe null
        val json = JSONObject(resp)
        val mObj = json.optJSONObject("movie") ?: json
        val item = parseMediaObject(mObj)
        cacheItem(item)
        item
    }

    override fun findFullByTitle(title: String): MediaContent? = runSafe {
        val encoded = URLEncoder.encode(title.trim(), "UTF-8")
        val resp = httpGet("/api/movie/$encoded")
        if (resp != null) {
            val json = JSONObject(resp)
            val mObj = json.optJSONObject("movie") ?: json
            val item = parseMediaObject(mObj)
            cacheItem(item)
            return@runSafe item
        }
        // Fallback: search title
        val searchList = search(title, limit = 3)
        val match = searchList.firstOrNull { it.title.equals(title, ignoreCase = true) } ?: searchList.firstOrNull()
        if (match != null) {
            findFullById(match.id) ?: match
        } else {
            null
        }
    }

    private fun parseCatalogResponse(jsonStr: String?): Pair<List<MediaContent>, Int> {
        if (jsonStr == null) return Pair(emptyList(), 0)
        return try {
            val obj = JSONObject(jsonStr)
            val total = obj.optInt("total", 0)
            val itemsArr = obj.optJSONArray("items")
            val list = parseMediaList(itemsArr)
            Pair(list, total)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing catalog response", e)
            Pair(emptyList(), 0)
        }
    }

    private fun parseMediaList(arr: JSONArray?): List<MediaContent> {
        if (arr == null) return emptyList()
        val list = mutableListOf<MediaContent>()
        for (i in 0 until arr.length()) {
            val itemObj = arr.optJSONObject(i) ?: continue
            list.add(parseMediaObject(itemObj))
        }
        return list
    }

    private fun isStructurallyPlayableUrl(url: String, source: String? = null): Boolean {
        val value = url.trim()
        if (value.isBlank()) return false
        if (value.startsWith("magnet:?", ignoreCase = true)) {
            if (source.isNullOrBlank()) return false
            val hash = Regex("(?:^|[?&])xt=urn:btih:([^&\\s]+)", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.getOrNull(1).orEmpty()
            val hex40 = Regex("^[0-9A-Fa-f]{40}$")
            val base32 = Regex("^[A-Z2-7]{32}$", RegexOption.IGNORE_CASE)
            return hex40.matches(hash) || base32.matches(hash)
        }
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true)
    }

    private val allowedStreamHeaderNames = setOf(
        "accept",
        "accept-language",
        "cache-control",
        "content-type",
        "if-modified-since",
        "if-none-match",
        "origin",
        "range",
        "referer",
        "sec-fetch-dest",
        "sec-fetch-mode",
        "sec-fetch-site",
        "user-agent",
        "x-requested-with",
    )

    private fun isSafeStreamHeader(name: String, value: String): Boolean =
        name.trim().lowercase() in allowedStreamHeaderNames &&
            value.isNotBlank() &&
            value.length <= 2048 &&
            !value.contains("\r") &&
            !value.contains("\n")

    private fun parseStreamHeaders(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val headerValue = value.optString(name).trim()
            if (isSafeStreamHeader(name, headerValue)) {
                result[name] = headerValue
            }
        }
        return result
    }

    private fun parseStreamSubtitles(value: JSONArray?): List<StreamSubtitle> {
        if (value == null) return emptyList()
        val result = mutableListOf<StreamSubtitle>()
        for (i in 0 until minOf(value.length(), 16)) {
            val obj = value.optJSONObject(i) ?: continue
            val url = obj.optString("url").ifBlank {
                obj.optString("uri").ifBlank { obj.optString("src") }
            }.trim()
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) continue
            result += StreamSubtitle(
                url = url,
                language = obj.optString("language").ifBlank {
                    obj.optString("lang", "ru")
                },
                label = obj.optString("label").ifBlank {
                    obj.optString("name", "Русские")
                },
                mimeType = obj.optString("mime_type").ifBlank {
                    obj.optString("mimeType", "text/vtt")
                },
            )
        }
        return result
    }

    private fun parseStreamMap(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next().trim()
            val item = value.optString(key).trim()
            if (key.isBlank() || item.isBlank() || key.length > 128 || item.length > 2048) continue
            if (key.any { it == '\r' || it == '\n' } || item.any { it == '\r' || it == '\n' }) continue
            if (Regex("(?i)(token|authorization|cookie|password|secret|signature|private[_-]?key)")
                    .containsMatchIn(key)
            ) continue
            result[key] = item
        }
        return result
    }

    private fun parseStreamSkipIntervals(value: JSONArray?): List<StreamSkipInterval> {
        if (value == null) return emptyList()
        val result = mutableListOf<StreamSkipInterval>()
        for (i in 0 until minOf(value.length(), 32)) {
            val item = value.optJSONObject(i) ?: continue
            val start = item.optLong("startMs", item.optLong("start_ms", item.optLong("start", -1L)))
            val end = item.optLong("endMs", item.optLong("end_ms", item.optLong("end", -1L)))
            if (start >= 0L && end >= start && end <= 86_400_000L) {
                result += StreamSkipInterval(start, end)
            }
        }
        return result
    }

    private fun parseStreamAdvertisement(value: Any?): StreamAdvertisement? {
        if (value == null || value == JSONObject.NULL) return null
        val raw = value.toString().trim()
        if (raw.isBlank() || raw.length > 4096) return null
        return if (value is JSONObject) {
            StreamAdvertisement(raw = raw, metadata = parseStreamMap(value))
        } else {
            StreamAdvertisement(raw = raw)
        }
    }

    private fun parseStreamReloadData(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return null
        val raw = value.toString().trim()
        if (raw.isBlank() || raw.length > 4096) return null
        if (Regex("(?i)(token|authorization|cookie|password|secret|signature|private[_-]?key)")
                .containsMatchIn(raw)
        ) return null
        return raw
    }

    private fun firstStreamString(obj: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { obj.optString(it).trim() }.firstOrNull { it.isNotBlank() }

    private fun parseMediaStringList(obj: JSONObject?, vararg keys: String): List<String> {
        if (obj == null) return emptyList()
        val values = mutableListOf<String>()
        keys.forEach { key ->
            obj.optJSONArray(key)?.let { array ->
                for (index in 0 until array.length()) {
                    val nested = array.optJSONObject(index)
                    val raw = when {
                        nested != null -> firstStreamString(nested, "name", "title", "full_name", "fullName")
                        else -> array.optString(index).trim().takeIf { it.isNotBlank() }
                    }
                    raw?.let(values::add)
                }
            }
            obj.optString(key).trim().takeIf { it.isNotBlank() }?.let { raw ->
                raw.split(';', '|')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach(values::add)
            }
        }
        return values.distinct()
    }

    private fun streamResolutionDimension(value: String?, width: Boolean): Int? {
        val raw = value?.trim().orEmpty()
        val match = Regex("(\\d{2,5})\\s*[xх×]\\s*(\\d{2,5})").find(raw) ?: return null
        return match.groupValues[if (width) 1 else 2].toIntOrNull()
    }

    private fun parseMediaObject(obj: JSONObject): MediaContent {
        val id = obj.optString("id", "")
        val title = obj.optString("title", "Без названия")
        val typeStr = obj.optString("type", "MOVIE").uppercase()
        val type = if (typeStr == "SERIES" || typeStr == "TV") ContentType.SERIES else ContentType.MOVIE

        val year = obj.optInt("year", 2024)
        val rating = obj.optDouble("rating", 0.0)
        val country = obj.optString("country", "Зарубежный")
        val quality = obj.optString("quality", "1080p")
        val duration = obj.optInt("durationMinutes", obj.optInt("duration", 90))
        val isNew = obj.optBoolean("isNew", year >= 2024)
        val popularity = obj.optInt("popularity", 100)
        val ageRating = obj.optInt("ageRating", 16)
        
        val synopsis = (obj.optString("description").takeIf { it.isNotBlank() } 
            ?: obj.optString("synopsis")).takeIf { it.isNotBlank() } ?: ""
            
        val originalTitle = (obj.optString("original_title").takeIf { it.isNotBlank() }
            ?: obj.optString("originalTitle")).takeIf { it.isNotBlank() }
            
        val crewObj = obj.optJSONObject("crew")
        val director = obj.optString("director").takeIf { it.isNotBlank() }
            ?: parseMediaStringList(crewObj, "director", "directors").firstOrNull()
        val writers = (
            parseMediaStringList(obj, "writers", "writer", "screenwriters", "screenwriter", "screenplay") +
                parseMediaStringList(crewObj, "writers", "writer", "screenwriters", "screenwriter", "screenplay")
            ).distinct()
        val producers = (
            parseMediaStringList(obj, "producers", "producer") +
                parseMediaStringList(crewObj, "producers", "producer")
            ).distinct()
        val cinematographers = (
            parseMediaStringList(obj, "cinematographers", "cinematographer", "cinematography", "operator", "operators") +
                parseMediaStringList(crewObj, "cinematographers", "cinematographer", "cinematography", "operator", "operators")
            ).distinct()
        val composers = (
            parseMediaStringList(obj, "composers", "composer", "music_by", "musicBy") +
                parseMediaStringList(crewObj, "composers", "composer", "music_by", "musicBy")
            ).distinct()
        val premiereDate = firstStreamString(
            obj,
            "premiere_date",
            "premiereDate",
            "release_date",
            "releaseDate",
            "premiere",
        )
        val budget = firstStreamString(obj, "budget", "production_budget", "productionBudget")
        val boxOffice = firstStreamString(obj, "box_office", "boxOffice", "gross", "revenue")
        
        val posterUrl = (obj.optString("poster_url").takeIf { it.isNotBlank() }
            ?: obj.optString("posterUrl")).takeIf { it.isNotBlank() }
            
        val backdropUrl = (obj.optString("backdrop_url").takeIf { it.isNotBlank() }
            ?: obj.optString("backdropUrl")).takeIf { it.isNotBlank() } ?: posterUrl
            
        val rawPlaybackUrl = obj.optString("playbackUrl").takeIf { it.isNotBlank() }

        val categoryStr = obj.optString("category", "MOVIES")
        val category = try {
            CatalogCategory.valueOf(categoryStr)
        } catch (_: Exception) {
            CatalogCategory.MOVIES
        }

        val genresSet = mutableSetOf<String>()
        obj.optJSONArray("genres")?.let { gArr ->
            for (i in 0 until gArr.length()) {
                genresSet.add(gArr.getString(i))
            }
        }

        val castList = mutableListOf<Person>()
        val castArr = obj.optJSONArray("actors") ?: obj.optJSONArray("cast")
        castArr?.let { cArr ->
            for (i in 0 until cArr.length()) {
                val cObj = cArr.optJSONObject(i)
                if (cObj != null) {
                    val pUrl = (cObj.optString("photo_url").takeIf { it.isNotBlank() }
                        ?: cObj.optString("photoUrl")).takeIf { it.isNotBlank() }
                    castList.add(
                        Person(
                            name = cObj.optString("name", ""),
                            photoUrl = pUrl,
                            role = cObj.optString("role").takeIf { it.isNotBlank() }
                        )
                    )
                } else {
                    val name = cArr.optString(i)
                    if (name.isNotBlank()) castList.add(Person(name = name))
                }
            }
        }

        val streamsList = mutableListOf<StreamOption>()
        obj.optJSONArray("streams")?.let { sArr ->
            for (i in 0 until sArr.length()) {
                val sObj = sArr.optJSONObject(i) ?: continue
                val source = sObj.optString("source").takeIf { it.isNotBlank() }
                val streamUrl = sObj.optString("url", "").trim()
                if (!isStructurallyPlayableUrl(streamUrl, source)) continue
                val subtitles = parseStreamSubtitles(
                    sObj.optJSONArray("subtitle_list")
                        ?: sObj.optJSONArray("subtitleList")
                        ?: sObj.optJSONArray("subtitles")
                )
                val headers = parseStreamHeaders(
                    sObj.optJSONObject("headers")
                        ?: sObj.optJSONObject("http_headers")
                        ?: sObj.optJSONObject("httpHeaders")
                )
                val userAgent = sObj.optString("user_agent").takeIf { it.isNotBlank() }
                    ?: sObj.optString("userAgent").takeIf { it.isNotBlank() }
                val videoTrackIndex = sObj.optInt("video_track_index", -1).takeIf { it >= 0 }
                    ?: sObj.optInt("videoTrackIndex", -1).takeIf { it >= 0 }
                val audioTrackIndex = sObj.optInt("audio_track_index", -1).takeIf { it >= 0 }
                    ?: sObj.optInt("audioTrackIndex", -1).takeIf { it >= 0 }
                val durationMs = sObj.optLong("duration_ms", 0L).takeIf { it > 0L }
                    ?: sObj.optLong("duration", 0L).takeIf { it > 0L }
                val sizeBytes = sObj.optLong("size_bytes", 0L).takeIf { it > 0L }
                    ?: sObj.optLong("size", 0L).takeIf { it > 0L }
                val resolution = firstStreamString(sObj, "resolution", "video_resolution", "videoResolution")
                streamsList.add(
                    StreamOption(
                        voice = firstStreamString(sObj, "voice", "translation") ?: "Не указано",
                        quality = firstStreamString(sObj, "quality") ?: "Не указано",
                        seeders = sObj.optInt("seeders", sObj.optInt("seeds", 0)),
                        url = streamUrl,
                        source = source,
                        streamId = sObj.optString("stream_id").takeIf { it.isNotBlank() }
                            ?: sObj.optString("streamId").takeIf { it.isNotBlank() }.orEmpty(),
                        logicalSourceId = firstStreamString(sObj, "logical_source_id", "logicalSourceId"),
                        providerItemId = sObj.optString("provider_item_id").takeIf { it.isNotBlank() }
                            ?: sObj.optString("providerItemId").takeIf { it.isNotBlank() },
                        infoHash = sObj.optString("info_hash").takeIf { it.isNotBlank() }
                            ?: sObj.optString("infoHash").takeIf { it.isNotBlank() },
                        sourceTypeId = sObj.optInt("source_type_id", -1).takeIf { it >= 0 }
                            ?: sObj.optInt("video_source_type_id", -1).takeIf { it >= 0 }
                            ?: sObj.optInt("videoSourceTypeId", -1).takeIf { it >= 0 },
                        contentTypeId = sObj.optInt("content_type_id", -1).takeIf { it >= 0 }
                            ?: sObj.optInt("video_content_type_id", -1).takeIf { it >= 0 }
                            ?: sObj.optInt("videoContentTypeId", -1).takeIf { it >= 0 },
                        fileIndex = sObj.optInt("file_index", -1).takeIf { it >= 0 }
                            ?: sObj.optInt("fileIndex", -1).takeIf { it >= 0 },
                        filePath = sObj.optString("file_path").takeIf { it.isNotBlank() }
                            ?: sObj.optString("filePath").takeIf { it.isNotBlank() },
                        seasonNumber = sObj.optInt("season", -1).takeIf { it > 0 },
                        episodeNumber = sObj.optInt("episode", -1).takeIf { it > 0 },
                        mimeType = sObj.optString("mime_type").takeIf { it.isNotBlank() }
                            ?: sObj.optString("mimeType").takeIf { it.isNotBlank() },
                        drmScheme = sObj.optString("drm_scheme").takeIf { it.isNotBlank() }
                            ?: sObj.optString("drmScheme").takeIf { it.isNotBlank() },
                        drmLicenseUrl = sObj.optString("license_url").takeIf { it.isNotBlank() }
                            ?: sObj.optString("drm_license_url").takeIf { it.isNotBlank() }
                            ?: sObj.optString("drmLicenseUrl").takeIf { it.isNotBlank() },
                        language = sObj.optString("language").ifBlank { "ru" },
                        codec = sObj.optString("codec").takeIf { it.isNotBlank() },
                        userAgent = userAgent,
                        headers = headers,
                        subtitles = subtitles,
                        hasInternalSubtitles = sObj.optBoolean("is_use_internal_subtitles", false) ||
                            sObj.optBoolean("isUseInternalSubtitles", false),
                        videoTrackIndex = videoTrackIndex,
                        audioTrackIndex = audioTrackIndex,
                        durationMs = durationMs,
                        sizeBytes = sizeBytes,
                        reloadSupported = sObj.optBoolean("reload_supported", false) ||
                            sObj.optBoolean("reloadSupported", false) ||
                            sObj.has("reload_data") || sObj.has("reloadData"),
                        reloadData = parseStreamReloadData(
                            sObj.opt("reload_data") ?: sObj.opt("reloadData"),
                        ),
                        sourceId = firstStreamString(sObj, "source_id", "sourceId"),
                        providerId = firstStreamString(sObj, "provider_id", "providerId"),
                        providerContentId = firstStreamString(sObj, "provider_content_id", "providerContentId"),
                        transport = firstStreamString(sObj, "transport") ?: when {
                            streamUrl.startsWith("magnet:", ignoreCase = true) -> "torrent_p2p"
                            streamUrl.contains("/stream?", ignoreCase = true) -> "local_gateway"
                            streamUrl.contains(".m3u8", ignoreCase = true) -> "hls"
                            streamUrl.contains(".mpd", ignoreCase = true) -> "dash"
                            else -> "direct"
                        },
                        transportMetadata = parseStreamMap(
                            sObj.optJSONObject("transport_metadata")
                                ?: sObj.optJSONObject("transportMetadata"),
                        ),
                        resolution = resolution,
                        resolutionWidth = sObj.optInt("resolution_width", -1).takeIf { it > 0 }
                            ?: sObj.optInt("resolutionWidth", -1).takeIf { it > 0 }
                            ?: streamResolutionDimension(resolution, width = true),
                        resolutionHeight = sObj.optInt("resolution_height", -1).takeIf { it > 0 }
                            ?: sObj.optInt("resolutionHeight", -1).takeIf { it > 0 }
                            ?: streamResolutionDimension(resolution, width = false),
                        unavailableQuality = sObj.optBoolean("unavailable_quality", false) ||
                            sObj.optBoolean("unavailableQuality", false),
                        isTrailer = sObj.optBoolean("is_trailer", false) ||
                            sObj.optBoolean("isTrailer", false),
                        downloadUrl = firstStreamString(sObj, "download_url", "downloadUrl")
                            ?.takeIf { isStructurallyPlayableUrl(it) },
                        downloadHeaders = parseStreamHeaders(
                            sObj.optJSONObject("download_headers")
                                ?: sObj.optJSONObject("downloadHeaders"),
                        ),
                        skipIntervals = parseStreamSkipIntervals(
                            sObj.optJSONArray("skip_intervals")
                                ?: sObj.optJSONArray("skipIntervals"),
                        ),
                        advertisement = parseStreamAdvertisement(
                            sObj.opt("advertisement") ?: sObj.opt("ad"),
                        ),
                        catalogMediaId = firstStreamString(sObj, "catalog_media_id", "catalogMediaId"),
                        canonicalTitle = firstStreamString(sObj, "canonical_title", "canonicalTitle"),
                        canonicalOriginalTitle = firstStreamString(
                            sObj,
                            "canonical_original_title",
                            "canonicalOriginalTitle",
                        ),
                        canonicalYear = sObj.optInt("canonical_year", -1).takeIf { it > 0 }
                            ?: sObj.optInt("canonicalYear", -1).takeIf { it > 0 },
                        canonicalMediaType = firstStreamString(
                            sObj,
                            "canonical_media_type",
                            "canonicalMediaType",
                        )?.trim()?.lowercase()?.let { raw ->
                            when (raw) {
                                "tv", "series", "serial", "tv_series", "limited_series" -> ContentType.SERIES
                                "tv_channel", "channel" -> ContentType.TV
                                else -> ContentType.MOVIE
                            }
                        },
                        healthScore = sObj.optDouble("health_score", sObj.optDouble("healthScore", 0.5))
                            .coerceIn(0.0, 1.0),
                        startupLatencyMs = when {
                            sObj.has("startup_latency_ms") && !sObj.isNull("startup_latency_ms") ->
                                sObj.optLong("startup_latency_ms", 0L).coerceAtLeast(0L)
                            sObj.has("startupLatencyMs") && !sObj.isNull("startupLatencyMs") ->
                                sObj.optLong("startupLatencyMs", 0L).coerceAtLeast(0L)
                            else -> null
                        },
                        recentFailureCount = sObj.optInt(
                            "recent_failure_count",
                            sObj.optInt("recentFailureCount", 0),
                        ).coerceAtLeast(0),
                        providerReliability = sObj.optDouble("provider_reliability", Double.NaN)
                            .takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0),
                    )
                )
            }
        }
        val playbackUrl = rawPlaybackUrl?.takeIf { raw ->
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("file://", ignoreCase = true) ||
                streamsList.any { it.url == raw }
        }

        val voteCount = obj.optInt("vote_count", obj.optInt("voteCount", 0))
        val seasonsCount = obj.optInt("seasons_count", obj.optInt("seasonsCount", 0))
        val episodesCount = obj.optInt("episodes_count", obj.optInt("episodesCount", 0))
        val seasonEpisodeCounts = buildList {
            val arr = obj.optJSONArray("season_episode_counts")
                ?: obj.optJSONArray("seasonEpisodeCounts")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val count = arr.optInt(i, 0)
                    if (count > 0) add(count) else add(0)
                }
            }
        }

        return MediaContent(
            id = id,
            title = title,
            type = type,
            year = year,
            rating = rating,
            genres = genresSet,
            country = country,
            quality = quality,
            durationMinutes = duration,
            isNew = isNew,
            popularity = popularity,
            ageRating = ageRating,
            synopsis = synopsis,
            originalTitle = originalTitle,
            director = director,
            cast = castList,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            playbackUrl = playbackUrl,
            category = category,
            streams = streamsList,
            sourceUrl = playbackUrl,
            voteCount = voteCount,
            seasonsCount = seasonsCount,
            episodesCount = episodesCount,
            seasonEpisodeCounts = seasonEpisodeCounts,
            writers = writers,
            producers = producers,
            cinematographers = cinematographers,
            composers = composers,
            premiereDate = premiereDate,
            budget = budget,
            boxOffice = boxOffice,
        )
    }
}
