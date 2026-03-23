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
    val client = OkHttpClient()
    val gson = Gson()

    class NiceResponse(val resp: Response) {
        val text: String = resp.body?.use { it.string() } ?: ""
        val document: Document by lazy { Jsoup.parse(text) }
        val code: Int = resp.code
        val isSuccessful: Boolean = resp.isSuccessful
        val url: String = resp.request.url.toString()
        val headers: Map<String, String> = resp.headers.toMap()
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
    val gson = Gson()
    fun toJson(any: Any?): String = gson.toJson(any)
    inline fun <reified T> parseJson(json: String): T = gson.fromJson(json, T::class.java)
    inline fun <reified T> tryParseJson(json: String): T? = try { parseJson<T>(json) } catch (e: Exception) { null }
}

// Top-level convenience wrappers
inline fun <reified T> tryParseJson(json: String?): T? = if (json == null) null else try { AppUtils.parseJson<T>(json) } catch (e: Exception) { null }
inline fun <reified T> parseJson(json: String): T = AppUtils.parseJson(json)
fun toJson(any: Any?): String = AppUtils.toJson(any)

// Type alias so existing NiceResponse usage works without qualification
typealias NiceResponse = app.NiceResponse

suspend inline fun <T> safeApiCall(crossinline block: suspend () -> T): T? = try { block() } catch (e: Exception) { null }

suspend fun getM3u8Qualities(url: String, referer: String? = null, name: String? = null): List<ExtractorLink> {
    val headers = if (referer != null) mapOf("Referer" to referer) else emptyMap()
    val response = app.get(url, headers = headers).text
    val links = mutableListOf<ExtractorLink>()
    
    val lines = response.split("\n")
    var currentQuality = 0
    for (line in lines) {
        if (line.contains("RESOLUTION=")) {
            val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
            if (resMatch != null) {
                currentQuality = resMatch.groupValues[2].toInt()
            }
        } else if (line.trim().startsWith("http") || (line.trim().isNotEmpty() && !line.startsWith("#"))) {
            val m3u8Link = if (line.trim().startsWith("http")) line.trim() else {
                val base = url.substringBeforeLast("/")
                "$base/${line.trim()}"
            }
            links.add(newExtractorLink(
                name ?: "M3U8",
                name ?: "M3U8",
                m3u8Link,
                ExtractorLinkType.M3U8
            ).apply { quality = if (currentQuality > 0) currentQuality else Qualities.Unknown.value })
            currentQuality = 0
        }
    }
    
    if (links.isEmpty()) {
        links.add(newExtractorLink(
            name ?: "M3U8",
            name ?: "M3U8",
            url,
            ExtractorLinkType.M3U8
        ))
    }
    return links
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
                if (e is CancellationException) throw e
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

val languageMap = mapOf(
    "Afrikaans" to listOf("af", "afr"),
    "Albanian" to listOf("sq", "sqi"),
    "Amharic" to listOf("am", "amh"),
    "Arabic" to listOf("ar", "ara"),
    "Armenian" to listOf("hy", "hye"),
    "Azerbaijani" to listOf("az", "aze"),
    "Basque" to listOf("eu", "eus"),
    "Belarusian" to listOf("be", "bel"),
    "Bengali" to listOf("bn", "ben"),
    "Bosnian" to listOf("bs", "bos"),
    "Bulgarian" to listOf("bg", "bul"),
    "Catalan" to listOf("ca", "cat"),
    "Chinese" to listOf("zh", "zho"),
    "Croatian" to listOf("hr", "hrv"),
    "Czech" to listOf("cs", "ces"),
    "Danish" to listOf("da", "dan"),
    "Dutch" to listOf("nl", "nld"),
    "English" to listOf("en", "eng"),
    "Estonian" to listOf("et", "est"),
    "Filipino" to listOf("tl", "tgl", "fil"),
    "Finnish" to listOf("fi", "fin"),
    "French" to listOf("fr", "fra"),
    "Galician" to listOf("gl", "glg"),
    "Georgian" to listOf("ka", "kat"),
    "German" to listOf("de", "deu", "ger"),
    "Greek" to listOf("el", "ell"),
    "Gujarati" to listOf("gu", "guj"),
    "Hebrew" to listOf("he", "heb"),
    "Hindi" to listOf("hi", "hin"),
    "Hungarian" to listOf("hu", "hun"),
    "Icelandic" to listOf("is", "isl"),
    "Indonesian" to listOf("id", "ind"),
    "Italian" to listOf("it", "ita"),
    "Japanese" to listOf("ja", "jpn"),
    "Kannada" to listOf("kn", "kan"),
    "Kazakh" to listOf("kk", "kaz"),
    "Korean" to listOf("ko", "kor"),
    "Latvian" to listOf("lv", "lav"),
    "Lithuanian" to listOf("lt", "lit"),
    "Macedonian" to listOf("mk", "mkd"),
    "Malay" to listOf("ms", "msa"),
    "Malayalam" to listOf("ml", "mal"),
    "Maltese" to listOf("mt", "mlt"),
    "Marathi" to listOf("mr", "mar"),
    "Mongolian" to listOf("mn", "mon"),
    "Nepali" to listOf("ne", "nep"),
    "Norwegian" to listOf("no", "nor"),
    "Persian" to listOf("fa", "fas"),
    "Polish" to listOf("pl", "pol"),
    "Portuguese" to listOf("pt", "por"),
    "Punjabi" to listOf("pa", "pan"),
    "Romanian" to listOf("ro", "ron"),
    "Russian" to listOf("ru", "rus"),
    "Serbian" to listOf("sr", "srp"),
    "Sinhala" to listOf("si", "sin"),
    "Slovak" to listOf("sk", "slk"),
    "Slovenian" to listOf("sl", "slv"),
    "Spanish" to listOf("es", "spa"),
    "Swahili" to listOf("sw", "swa"),
    "Swedish" to listOf("sv", "swe"),
    "Tamil" to listOf("ta", "tam"),
    "Telugu" to listOf("te", "tel"),
    "Thai" to listOf("th", "tha"),
    "Turkish" to listOf("tr", "tur"),
    "Ukrainian" to listOf("uk", "ukr"),
    "Urdu" to listOf("ur", "urd"),
    "Uzbek" to listOf("uz", "uzb"),
    "Vietnamese" to listOf("vi", "vie"),
    "Welsh" to listOf("cy", "cym"),
    "Yiddish" to listOf("yi", "yid")
)

fun getLanguage(language: String?): String? {
    language ?: return null
    var normalizedLang = if(language.contains("-")) {
        language.substringBefore("-")
    } else if(language.contains(" ")) {
        language.substringBefore(" ")
    } else if(language.contains("CR_")) {
        language.substringAfter("CR_")
    } else {
        language
    }

    if(normalizedLang.isBlank()) {
        normalizedLang =  language
    }

    val tag = languageMap.entries.find { entry ->
        entry.value.contains(normalizedLang)
    }?.key

    if(tag == null) {
        return normalizedLang
    }
    return tag
}

val INFER_TYPE = ExtractorLinkType.VIDEO

object Log {
    fun d(tag: String, msg: String) = println("[$tag] $msg")
    fun e(tag: String, msg: String) = println("ERROR [$tag] $msg")
    fun w(tag: String, msg: String, e: Throwable? = null) = println("WARN [$tag] $msg $e")
}

object Settings {
    private val gson = Gson()
    private val client = OkHttpClient()
    private var remoteProviderKeys: List<String> = emptyList()
    private var disabledProviderKeys: Set<String> = emptySet()

    init {
        // Initial fetch synchronously to ensure settings are loaded before first request
        refreshConfig()
        
        // Poll for updates every 5 minutes in a background thread
        Thread {
            while (true) {
                Thread.sleep(300_000)
                refreshConfig()
            }
        }.start()
    }

    private fun refreshConfig() {
        val baseUrl = System.getenv("CONFIG_URL") ?: "https://gist.githubusercontent.com/iMayhem/abbb593bdcd0bfc3d54a6284e81cc880/raw/scrapers.json"
        val url = "$baseUrl?t=${System.currentTimeMillis()}"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: return
                
                // Handle potential double-encoding (if body is a JSON string instead of an object)
                val rawJson = if (body.trim().startsWith("\"")) {
                    println("[Settings] Double-encoded JSON detected, unescaping...")
                    gson.fromJson(body, String::class.java)
                } else {
                    body
                }

                val config = gson.fromJson(rawJson, RemoteConfig::class.java)
                
                if (config?.providers != null) {
                    remoteProviderKeys = config.providers.map { it.key }
                    disabledProviderKeys = config.providers.filter { !it.enabled }.map { it.key }.toSet()
                    println("[Settings] Remote config updated. ${remoteProviderKeys.size} providers, ${disabledProviderKeys.size} disabled.")
                }
            }
        } catch (e: Exception) {
            println("[Settings] Failed to fetch remote config: ${e.message}")
        }
    }

    fun getConcurrency(): Int = 20
    val allowDownloadLinks: Boolean = true
    
    val activeProviderOrder: List<String> get() {
        val base = if (remoteProviderKeys.isNotEmpty()) remoteProviderKeys else ProviderRegistry.keys
        return base.filter { it !in disabledProviderKeys }
    }
    
    enum class AddonType { HTTPS, TORRENT, DEBRID, SUBTITLE }
    data class StremioAddon(val name: String, val url: String, val type: AddonType)
    
    fun getStremioAddons(): List<StremioAddon> = listOf(
        StremioAddon("Torrentio", "https://torrentio.strem.fun", AddonType.TORRENT),
        StremioAddon("Cinemeta", "https://v3-cinemeta.strem.io", AddonType.HTTPS)
    )
    
    fun stremioAddonKey(name: String): String = "stremio_${name.lowercase()}"
    fun isEnabled(key: String): Boolean {
        // If we have remote keys, and the key is one of them, check if it's disabled.
        // If it's NOT in remote keys, it might be a new/hardcoded one we haven't registered yet?
        // Let's assume if it's in disabledProviderKeys, it's disabled.
        return key !in disabledProviderKeys
    }

    fun getShowboxToken(): String? = "direct"
}

data class RemoteProvider(val key: String, val name: String, val enabled: Boolean)
data class ScraperPreferences(
    val prioritizeBy: String = "latency",
    val maxPreferredSizeGb: Double = 3.0,
)
data class RemoteConfig(
    val providers: List<RemoteProvider>,
    val preferences: ScraperPreferences? = null,
)

// Base64 removed to avoid conflict

data class TripleOneMoviesServer(
    val name: String,
    val description: String,
    val image: String,
    val data: String,
)

fun <T> JSONObject.parsedSafe(): T? = null // Stub for complex parsing

fun JSONObject.optString(key: String, fallback: String): String = if (has(key)) getString(key) else fallback

fun String.toHttpUrl() = URI(this).toURL()
val URL.host: String get() = getHost()
val URL.pathSegments: List<String> get() = path.split("/").filter { it.isNotEmpty() }
