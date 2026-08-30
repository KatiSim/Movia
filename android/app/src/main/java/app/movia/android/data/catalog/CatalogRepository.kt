package app.movia.android.data.catalog

import android.content.Context
import android.util.Log
import android.util.LruCache
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import app.movia.android.domain.model.StreamOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    fun findFullById(id: String): MediaContent?
    fun findFullByTitle(title: String): MediaContent?
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
        prewarm()
    }

    fun prewarm() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fetchHome()
                fetchAllGenres()
            } catch (e: Exception) {
                Log.w(TAG, "Prewarm failed: ${e.message}")
            }
        }
    }

    @Synchronized
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
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
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
        fetchHome()
        cachedPopular.take(limit)
    }

    override fun getNew(limit: Int): List<MediaContent> = runSafe {
        fetchHome()
        cachedNew.take(limit)
    }

    fun getSeries(limit: Int = 12): List<MediaContent> = runSafe {
        fetchHome()
        cachedSeries.take(limit)
    }

    fun getForYou(limit: Int = 12): List<MediaContent> = runSafe {
        fetchHome()
        cachedForYou.take(limit)
    }

    fun getHero(limit: Int = 3): List<MediaContent> = runSafe {
        fetchHome()
        cachedHero.take(limit)
    }

    private fun cachedCatalogItems(): List<MediaContent> =
        (cachedPopular + cachedNew + cachedAnimations + cachedSeries + cachedForYou + cachedHero)
            .distinctBy { it.id }

    fun all(): List<MediaContent> {
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
        return runSafe {
            val resp = httpGet("/api/genres")
            if (resp != null) {
                val arr = JSONArray(resp)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                cachedGenres = list
                list
            } else {
                listOf("аниме", "боевик", "детектив", "драма", "комедия", "мультфильм", "триллер", "ужасы", "фантастика")
            }
        }
    }

    private fun httpGetDetailed(path: String): CatalogHttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(if (path.startsWith("http")) path else "$BASE_URL$path")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
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

    override fun findByTitle(title: String): MediaContent? {
        val key = CanonicalTextNormalizer.normalize(title)
        val cached = titleCache.get(key)
        if (cached != null && !cached.synopsis.isNullOrBlank() && cached.cast.isNotEmpty()) {
            return cached
        }
        return findFullByTitle(title) ?: cached
    }

    override fun findById(id: String): MediaContent? {
        val cached = movieCache.get(id)
        if (cached != null && !cached.synopsis.isNullOrBlank() && cached.cast.isNotEmpty()) {
            return cached
        }
        return findFullById(id) ?: cached
    }

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

        val director = obj.optString("director").takeIf { it.isNotBlank() }

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
                streamsList.add(
                    StreamOption(
                        voice = sObj.optString("voice", "Не указано"),
                        quality = sObj.optString("quality", "Не указано"),
                        seeders = sObj.optInt("seeders", 0),
                        url = streamUrl,
                        source = source,
                        streamId = sObj.optString("stream_id").takeIf { it.isNotBlank() }
                            ?: sObj.optString("streamId").takeIf { it.isNotBlank() }.orEmpty(),
                        providerItemId = sObj.optString("provider_item_id").takeIf { it.isNotBlank() }
                            ?: sObj.optString("providerItemId").takeIf { it.isNotBlank() },
                        infoHash = sObj.optString("info_hash").takeIf { it.isNotBlank() }
                            ?: sObj.optString("infoHash").takeIf { it.isNotBlank() },
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
        )
    }
}
