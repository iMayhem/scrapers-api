package com.moovie.plugins

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.*
import java.net.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import okhttp3.MediaType
import okhttp3.RequestBody

// ═══════════════════════════════════════════════
// 1. CONSTANTS
// ═══════════════════════════════════════════════

const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

// ═══════════════════════════════════════════════
// 2. CORE TYPES (Mimicking Cloudstream API)
// ═══════════════════════════════════════════════

enum class TvType {
    Movie, TvSeries, Anime, OVA, Torrent, Live, Others, AsianDrama, Documentary, AnimeMovie
}

enum class Qualities(val value: Int) {
    P144(144), P240(240), P360(360), P480(480), P720(720), P1080(1080), P1440(1440), P2160(2160), Unknown(400)
}

enum class ExtractorLinkType {
    VIDEO, M3U8, DASH, TORRENT, MAGNET
}

data class ExtractorLink(
    val source: String,
    val name: String,
    var url: String,
    var referer: String,
    var quality: Int,
    val isM3u8: Boolean = false,
    var headers: Map<String, String> = emptyMap(),
    val type: ExtractorLinkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
    var extractorData: String? = null
)

data class SubtitleFile(
    val lang: String,
    val url: String
)

interface SearchResponse {
    val name: String
    val url: String
    val apiName: String
    var type: TvType?
    var posterUrl: String?
}

data class MovieSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.Movie,
    override var posterUrl: String? = null,
    var score: Int? = null
) : SearchResponse

data class TvSeriesSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.TvSeries,
    override var posterUrl: String? = null,
    var score: Int? = null
) : SearchResponse

data class AnimeSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.Anime,
    override var posterUrl: String? = null,
    var score: Int? = null
) : SearchResponse

object Score {
    fun from10(score: Double?): Int? = score?.let { (it * 10).toInt() } // Cloudstream uses 0-1000 usually? Stremio returns 0-10
    fun from10(score: String?): Int? = score?.toDoubleOrNull()?.let { (it * 10).toInt() }
}

enum class DubStatus {
    Dubbed, Subbed, Neural
}

interface LoadResponse {
    val name: String
    val url: String
    val apiName: String
    val type: TvType
    var posterUrl: String?
    var plot: String?
    var year: Int?
    var tags: List<String>?
    var logoUrl: String?
    var backgroundPosterUrl: String?
    var contentRating: String?
    var actors: List<String>?
}

data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Movie,
    override var posterUrl: String? = null,
    override var plot: String? = null,
    override var year: Int? = null,
    override var tags: List<String>? = null,
    override var logoUrl: String? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null,
    override var actors: List<String>? = null,
    val dataUrl: String? = null,
    var recommendations: List<SearchResponse>? = null,
    var score: Int? = null
) : LoadResponse

data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.TvSeries,
    override var posterUrl: String? = null,
    override var plot: String? = null,
    override var year: Int? = null,
    override var tags: List<String>? = null,
    override var logoUrl: String? = null,
    override var backgroundPosterUrl: String? = null,
    override var contentRating: String? = null,
    override var actors: List<String>? = null,
    var episodes: List<Episode> = emptyList(),
    var score: Int? = null
) : LoadResponse

data class Episode(
    val data: String,
    var name: String? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var posterUrl: String? = null,
    var description: String? = null,
    var score: Int? = null
) {
    fun addDate(date: String?) {
        // Mock
    }
}

// ═══════════════════════════════════════════════
// 3. THE app OBJECT (Networking Shim)
// ═══════════════════════════════════════════════

object app {
    private val client = OkHttpClient()
    private val gson = Gson()

    class NiceResponse(val resp: Response) {
        val text: String by lazy { resp.body?.use { it.string() } ?: "" }
        val document: Document by lazy { Jsoup.parse(text) }
        val code: Int = resp.code
        val isSuccessful: Boolean = resp.isSuccessful
        val url: String = resp.request.url.toString()
        val headers: Map<String, String> by lazy { resp.headers.toMap() }
        val cookies: Map<String, String> by lazy {
            resp.headers("Set-Cookie").associate {
                val split = it.split(";")[0].split("=")
                split[0] to (if (split.size > 1) split[1] else "")
            }
        }

        inline fun <reified T> parsedSafe(): T? {
            return try { gson.fromJson(text, T::class.java) } catch (e: Exception) { null }
        }
    }

    fun get(url: String, headers: Map<String, String>? = null, referer: String? = null, cookies: Map<String, String>? = null, timeout: Long? = null, allowRedirects: Boolean = true): NiceResponse {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k, v) }
        if (referer != null) builder.header("Referer", referer)
        cookies?.let { c ->
            builder.header("Cookie", c.map { "${it.key}=${it.value}" }.joinToString("; "))
        }
        val currentClient = if (allowRedirects) client else client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return NiceResponse(currentClient.newCall(builder.build()).execute())
    }

    fun post(url: String, headers: Map<String, String>? = null, data: Map<String, String>? = null, json: Any? = null, referer: String? = null, timeout: Long? = null, cookies: Map<String, String>? = null, requestBody: RequestBody? = null): NiceResponse {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k, v) }
        if (referer != null) builder.header("Referer", referer)
        cookies?.let { c ->
            builder.header("Cookie", c.map { "${it.key}=${it.value}" }.joinToString("; "))
        }

        if (requestBody != null) {
            builder.post(requestBody)
        } else if (json != null) {
            val jsonStr = if (json is String) json else gson.toJson(json)
            val body = jsonStr.toRequestBody("application/json".toMediaTypeOrNull())
            builder.post(body)
        } else {
            val form = FormBody.Builder()
            data?.forEach { (k, v) -> form.add(k, v) }
            builder.post(form.build())
        }
        
        return NiceResponse(client.newCall(builder.build()).execute())
    }

    fun head(url: String, headers: Map<String, String>? = null, referer: String? = null, cookies: Map<String, String>? = null, allowRedirects: Boolean = true, timeout: Long? = null): NiceResponse {
        val builder = Request.Builder().url(url).head()
        headers?.forEach { (k, v) -> builder.header(k, v) }
        if (referer != null) builder.header("Referer", referer)
        return NiceResponse(client.newBuilder().followRedirects(allowRedirects).build().newCall(builder.build()).execute())
    }
}

// ═══════════════════════════════════════════════
// 4. UTILS (AppUtils, Coroutines, Strings)
// ═══════════════════════════════════════════════

object AppUtils {
    private val gson = Gson()
    fun toJson(any: Any?): String = gson.toJson(any)
    inline fun <reified T> parseJson(json: String): T = gson.fromJson(json, T::class.java)
    inline fun <reified T> tryParseJson(json: String): T? = try { parseJson<T>(json) } catch (e: Exception) { null }
}

suspend fun <A, B> Iterable<A>.safeAmap(
    concurrency: Int = 10,
    f: suspend (A) -> B?
): List<B> = supervisorScope {
    val semaphore = Semaphore(concurrency)
    map { item ->
        async(Dispatchers.IO) {
            semaphore.acquire()
            try {
                f(item)
            } catch (e: Exception) {
                null
            } finally {
                semaphore.release()
            }
        }
    }.awaitAll().filterNotNull()
}

suspend fun runLimitedAsync(
    concurrency: Int = 10,
    vararg tasks: suspend () -> Unit
) = supervisorScope {
    val semaphore = Semaphore(concurrency)
    tasks.map { task ->
        async(Dispatchers.IO) {
            semaphore.acquire()
            try {
                task()
            } catch (e: Exception) {
                // Log error
            } finally {
                semaphore.release()
            }
        }
    }.awaitAll()
}

suspend fun runAllAsync(vararg tasks: suspend () -> Unit) {
    coroutineScope {
        tasks.map { async(Dispatchers.IO) { it() } }.awaitAll()
    }
}

fun String.toSansSerifBold(): String {
    val builder = StringBuilder()
    for (char in this) {
        val codePoint = when (char) {
            in 'A'..'Z' -> 0x1D5D4 + (char - 'A')
            in 'a'..'z' -> 0x1D5EE + (char - 'a')
            in '0'..'9' -> 0x1D7EC + (char - '0')
            else -> char.code
        }
        builder.append(Character.toChars(codePoint))
    }
    return builder.toString()
}

object M3u8Helper {
    suspend fun generateM3u8(source: String, url: String, referer: String, headers: Map<String, String>? = null): List<ExtractorLink> {
        return listOf(
            newExtractorLink(source, source, url, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.headers = headers ?: emptyMap()
            }
        )
    }
}

// ═══════════════════════════════════════════════
// 5. BASE API CLASS
// ═══════════════════════════════════════════════

abstract class MainAPI {
    abstract var name: String
    abstract var mainUrl: String
    open var lang: String = "en"
    open val hasMainPage: Boolean = false
    open val hasDownloadSupport: Boolean = false
    open val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)

    // MainPage Definition
    class MainPageRequest(val name: String, val data: String, val horizontal: Boolean = true)
    data class HomePageList(val name: String, val list: List<SearchResponse>, val horizontal: Boolean = true)
    data class HomePageResponse(val list: List<HomePageList>, val hasNext: Boolean = false)

    open val mainPage: List<MainPageRequest> = emptyList()
    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null

    fun mainPageOf(vararg pairs: Pair<String, String>): List<MainPageRequest> {
        return pairs.map { MainPageRequest(it.second, it.first) }
    }

    fun newHomePageResponse(list: HomePageList, hasNext: Boolean = false): HomePageResponse {
        return HomePageResponse(listOf(list), hasNext)
    }

    fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("/")) "$mainUrl$url" else "$mainUrl/$url"
    }

    abstract suspend fun search(query: String): List<SearchResponse>?
    abstract suspend fun load(url: String): LoadResponse?
    abstract suspend fun loadLinks(data: String, isCasting: Boolean = false, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean

    // Factory Helpers
    fun newMovieSearchResponse(name: String, url: String, type: TvType = TvType.Movie, initializer: MovieSearchResponse.() -> Unit = {}): MovieSearchResponse {
        return MovieSearchResponse(name, url, this.name, type).apply(initializer)
    }

    fun newTvSeriesSearchResponse(name: String, url: String, type: TvType = TvType.TvSeries, initializer: TvSeriesSearchResponse.() -> Unit = {}): TvSeriesSearchResponse {
        return TvSeriesSearchResponse(name, url, this.name, type).apply(initializer)
    }

    fun newAnimeSearchResponse(name: String, url: String, type: TvType = TvType.Anime, initializer: AnimeSearchResponse.() -> Unit = {}): AnimeSearchResponse {
        return AnimeSearchResponse(name, url, this.name, type).apply(initializer)
    }

    fun newMovieLoadResponse(name: String, url: String, type: TvType, dataUrl: String, initializer: MovieLoadResponse.() -> Unit = {}): MovieLoadResponse {
        return MovieLoadResponse(name, url, this.name, type, dataUrl = dataUrl).apply(initializer)
    }

    fun newTvSeriesLoadResponse(name: String, url: String, type: TvType, episodes: List<Episode>, initializer: TvSeriesLoadResponse.() -> Unit = {}): TvSeriesLoadResponse {
        return TvSeriesLoadResponse(name, url, this.name, type, episodes = episodes).apply(initializer)
    }

    fun newAnimeLoadResponse(name: String, url: String, type: TvType, initializer: TvSeriesLoadResponse.() -> Unit = {}): TvSeriesLoadResponse {
        return TvSeriesLoadResponse(name, url, this.name, type).apply(initializer)
    }

    fun newEpisode(data: String, initializer: Episode.() -> Unit = {}): Episode {
        return Episode(data).apply(initializer)
    }

    fun addEpisodes(status: DubStatus, episodes: List<Episode>) {
        // Mock
    }
}

fun newSubtitleFile(lang: String, url: String) = SubtitleFile(lang, url)

fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    quality: Int = Qualities.Unknown.value,
    referer: String = "",
    headers: Map<String, String> = emptyMap(),
    extractorData: String? = null,
    initializer: ExtractorLink.() -> Unit = {}
): ExtractorLink {
    return ExtractorLink(source, name, url, referer, quality, headers = headers, type = type, extractorData = extractorData).apply(initializer)
}

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    open val requiresReferer: Boolean = false
    abstract suspend fun getUrl(url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit)
}

// Mock methods for ID adding (used in ported code)
fun LoadResponse.addAniListId(id: Int?) {}
fun LoadResponse.addImdbId(id: String?) {}
fun LoadResponse.addMalId(id: Int?) {}

fun base64Decode(text: String): String = String(Base64.getDecoder().decode(text))

fun getLanguage(lang: String?): String? = lang // Stub

const val INFER_TYPE = ExtractorLinkType.VIDEO

object Log {
    fun d(tag: String, msg: String) = println("[$tag] $msg")
    fun e(tag: String, msg: String) = println("ERROR [$tag] $msg")
    fun w(tag: String, msg: String, e: Throwable? = null) = println("WARN [$tag] $msg $e")
}

object Settings {
    fun getConcurrency(): Int = 10
    val allowDownloadLinks: Boolean = true
    val activeProviderOrder: List<String> get() = ProviderRegistry.keys
    
    enum class AddonType { HTTPS, TORRENT, DEBRID, SUBTITLE }
    data class StremioAddon(val name: String, val url: String, val type: AddonType)
    
    fun getStremioAddons(): List<StremioAddon> = listOf(
        StremioAddon("Torrentio", "https://torrentio.strem.fun", AddonType.TORRENT),
        StremioAddon("Cinemeta", "https://v3-cinemeta.strem.io", AddonType.HTTPS)
    )
    
    fun stremioAddonKey(name: String): String = "stremio_${name.lowercase()}"
    fun getShowboxToken(): String? = "direct"
}

object Base64 {
    const val NO_WRAP = 0
    const val DEFAULT = 0
    fun encodeToString(bytes: ByteArray, flags: Int): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String, flags: Int): ByteArray = java.util.Base64.getDecoder().decode(text)
}

data class TripleOneMoviesServer(
    val name: String,
    val description: String,
    val image: String,
    val data: String,
)

fun <T> JSONObject.parsedSafe(): T? = null // Stub for complex parsing

fun JSONObject.optString(key: String, fallback: String): String = if (has(key)) getString(key) else fallback

fun String.toHttpUrl() = URL(this)
val URL.host: String get() = getHost()
val URL.pathSegments: List<String> get() = path.split("/").filter { it.isNotEmpty() }
