package com.moovie.plugins

import com.google.gson.annotations.SerializedName
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.Date

// ═══════════════════════════════════════════════
// Time helpers
// ═══════════════════════════════════════════════

val unixTimeMS: Long get() = System.currentTimeMillis()
val unixTime: Long get() = System.currentTimeMillis() / 1000

// ═══════════════════════════════════════════════
// APIHolder stub
// ═══════════════════════════════════════════════

object APIHolder {
    val unixTime: Long get() = System.currentTimeMillis() / 1000
}

// ═══════════════════════════════════════════════
// Settings cookie methods (extend Settings object)
// ═══════════════════════════════════════════════

private var _cookie: String? = null
private var _cookieTimestamp: Long = 0L

fun Settings.getCookie(): Pair<String?, Long> = Pair(_cookie, _cookieTimestamp)
fun Settings.saveCookie(cookie: String) { _cookie = cookie; _cookieTimestamp = System.currentTimeMillis() }
fun Settings.clearCookie() { _cookie = null; _cookieTimestamp = 0L }

// ═══════════════════════════════════════════════
fun fixTitle(str: String): String = str.replace("-", " ").trim()
fun httpsify(src: String): String = if (src.startsWith("//")) "https:$src" else src

fun loadExtractor(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    // Basic stub: try to emit a direct link
    callback(newExtractorLink("Direct", url, url))
}

// ═══════════════════════════════════════════════
// JsUnpacker stub
// ═══════════════════════════════════════════════

object JsUnpacker {
    fun unpack(packed: String?): String? {
        if (packed.isNullOrBlank()) return null
        return if (packed.contains("eval(function(p,a,c,k,e,")) packed else null
    }
    // Allow constructor-style invocation: JsUnpacker(str).unpack()
    operator fun invoke(script: String) = _JsUnpackerInstance(script)
}
class _JsUnpackerInstance(private val script: String) {
    fun unpack(): String? = JsUnpacker.unpack(script)
}

// ═══════════════════════════════════════════════
// Cloudflare bypass stub
// ═══════════════════════════════════════════════

class CloudflareKiller {
    // Stub – real Cloudflare bypass would require a headless browser
}

// ═══════════════════════════════════════════════
// TmdbDate data class
// ═══════════════════════════════════════════════

data class TmdbDate(
    val today: String,
    val nextWeek: String,
    val lastWeekStart: String,
    val monthStart: String,
)

// ═══════════════════════════════════════════════
// Actor / ActorData data classes
// ═══════════════════════════════════════════════

data class Actor(
    val name: String,
    val image: String? = null,
)

data class ActorData(
    val actor: Actor,
    val roleString: String? = null,
)

// ═══════════════════════════════════════════════
// ExtractedMediaData
// ═══════════════════════════════════════════════

data class ExtractedMediaData(
    val cast: List<ActorData>? = null,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val logoUrl: String? = null,
)

// ═══════════════════════════════════════════════
// Anizip stub
// ═══════════════════════════════════════════════

data class Anizip(
    val mappings: AnizipMappings? = null,
    val episodes: Map<String, AnizipEpisode>? = null
)
data class AnizipMappings(val imdb_id: String? = null, val thetvdb_id: Int? = null)
data class AnizipEpisode(val anidbEid: Int? = null)

// ═══════════════════════════════════════════════
// AnimeInfo / AniListResponse stubs
// ═══════════════════════════════════════════════

data class AnimeInfo(
    val title: String? = null,
    val romajiTitle: String? = null,
    val banner: String? = null,
    val description: String? = null,
)

data class AniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)
data class AniListMedia(
    val id: Int? = null,
    val title: AniListTitle? = null,
    val bannerImage: String? = null,
    val description: String? = null,
)
data class AniListData(val media: AniListMedia? = null)
data class AniListResponse(val data: AniListData? = null)

data class AniIds(val aniId: Int?, val malId: Int?)
data class AniSearch(val data: AniSearchData? = null)
data class AniSearchData(val Page: AniSearchPage? = null)
data class AniSearchPage(val media: List<AniSearchMedia>? = null)
data class AniSearchMedia(val id: Int? = null, val idMal: Int? = null)
// ═══════════════════════════════════════════════
// XDMovies search types
// ═══════════════════════════════════════════════

data class XDMoviesSearchResponse(
    val results: List<XDMoviesResult>? = null
) : ArrayList<XDMoviesResult>()

data class XDMoviesResult(
    val tmdb_id: Int? = null,
    val path: String? = null,
    val title: String? = null,
)

// ═══════════════════════════════════════════════
// AllMovieland data classes
// ═══════════════════════════════════════════════

data class AllMovielandPlaylist(
    val playlist: List<AllMovielandItem>? = null,
    val key: String? = null,
    val file: String? = null,
)

data class AllMovielandItem(
    val file: String? = null,
    val label: String? = null,
    val type: String? = null,
)

data class AllMovielandServer(
    val id: String? = null,
    val title: String? = null,
    val file: String? = null,
    val serverName: String? = null,
    val key: String? = null,
    val folder: List<AllMovielandFolder>? = null
)

// ═══════════════════════════════════════════════
// Kiskkh data classes
// ═══════════════════════════════════════════════

data class KisskhResults(
    val data: List<KisskhItem>? = null,
    @SerializedName("total") val total: Int? = null,
)

data class KisskhItem(
    val id: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
)

data class KisskhDetail(
    val id: Int? = null,
    val title: String? = null,
    val sub: Int? = null,
    val dub: Int? = null,
    val episodes: List<KisskhEpisode>? = null,
)

data class KisskhEpisode(
    val id: Int? = null,
    val number: Int? = null,
    val title: String? = null,
)

data class KisskhSources(
    val video: String? = null,
    val thirdParty: String? = null
)

data class KisskhSubtitle(
    val src: String? = null,
    val label: String? = null,
    val `default`: Boolean? = null,
)

// ═══════════════════════════════════════════════
// Netflix / NF data classes
// ═══════════════════════════════════════════════

data class NfSearchData(
    val searchResult: List<NfMedia>? = null,
)

data class NfMedia(
    val id: String? = null,
    val t: String? = null,
    val y: String? = null,
)

data class NetflixResponse(
    val id: String? = null,
    val title: String? = null,
    val year: String? = null,
    val season: List<NfSeason>? = null,
    val episodes: List<NfEpisode>? = null,
    val nextPageShow: Int? = null,
    val sources: List<NetflixSource>? = null,
)

data class NetflixSource(val file: String? = null)

data class NfSeason(
    val id: String? = null,
    val s: String? = null,
)

data class NfEpisode(
    val id: String? = null,
    val ep: String? = null,
    val t: String? = null,
)

// ═══════════════════════════════════════════════
// StreamWish / VidHide extractor stubs
// ═══════════════════════════════════════════════

open class StreamWishExtractor : ExtractorApi() {
    override val name: String = "StreamWish"
    override val mainUrl: String = "https://streamwish.to"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Stub: would need real extraction logic
    }
}

open class VidHidePro : ExtractorApi() {
    override val name: String = "VidHidePro"
    override val mainUrl: String = "https://vidhidepro.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Stub: would need real extraction logic
    }
}

// ═══════════════════════════════════════════════
// Animetosho data class
// ═══════════════════════════════════════════════

data class Animetosho(
    val id: Int? = null,
    val title: String? = null,
    val link: String? = null,
    val torrentUrl: String? = null,
    val magnetUri: String? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val totalSize: String? = null,
    val infoHash: String? = null,
)

// ═══════════════════════════════════════════════
// ResponseHash data class (for HDMovie2 AJAX)
// ═══════════════════════════════════════════════

data class ResponseHash(
    val embed_url: String? = null,
    val key: String? = null,
)

fun String?.getIframe(): String {
    if (this == null) return ""
    val match = Regex("""src=["']([^"']+)["']""").find(this)
    return match?.groupValues?.get(1) ?: this
}

// ═══════════════════════════════════════════════
// Anichi data classes
// ═══════════════════════════════════════════════

data class Anichi(val data: AnichiData? = null)
data class AnichiData(val shows: AnichiShows? = null, val episode: AnichiEPDetail? = null)
data class AnichiShows(val edges: List<AnichiEdge>? = null)
data class AnichiEdge(val id: String? = null)

data class AnichiEP(val data: AnichiData? = null)
data class AnichiEPDetail(val sourceUrls: List<AnichiEPSource>? = null)
data class AnichiEPSource(val sourceUrl: String, val sourceName: String)

data class AnichiVideoApiResponse(val links: List<AnichiVideoLink>? = null)
data class AnichiVideoLink(
    val link: String,
    val hls: Boolean? = null,
    val resolutionStr: String? = null,
    val headers: AnichiHeaders? = null,
    val subtitles: List<AnichiSubtitle>? = null
)
data class AnichiHeaders(val referer: String? = null)
data class AnichiSubtitle(val src: String? = null, val lang: String? = null)

// ═══════════════════════════════════════════════
// Hianime data classes
// ═══════════════════════════════════════════════

data class HianimeResponses(val html: String? = null)
data class HianimeStreamResponse(
    val sources: List<HianimeSource> = emptyList(),
    val tracks: List<HianimeTrack> = emptyList()
)
data class HianimeSource(val url: String? = null)
data class HianimeTrack(val kind: String? = null, val label: String? = null, val file: String = "")

// ═══════════════════════════════════════════════
// PrimeSrc data classes
// ═══════════════════════════════════════════════

data class PrimeSrcServerList(val servers: List<PrimeSrcServer>? = null)
data class PrimeSrcServer(val key: String? = null)

// ═══════════════════════════════════════════════
// Subtitle and Quality Helpers
// ═══════════════════════════════════════════════

object SubtitleHelper {
    fun fromTagToEnglishLanguageName(tag: String): String? = tag
}

fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null) return Qualities.Unknown.value
    return when {
        qualityName.contains("2160") || qualityName.contains("4K", true) -> Qualities.P2160.value
        qualityName.contains("1080") || qualityName.contains("FHD", true) -> Qualities.P1080.value
        qualityName.contains("720") || qualityName.contains("HD", true) -> Qualities.P720.value
        qualityName.contains("480") -> Qualities.P480.value
        qualityName.contains("360") -> Qualities.P360.value
        qualityName.contains("144") -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }
}

fun base64Decode(input: String): String {
    return String(java.util.Base64.getDecoder().decode(input))
}

fun getAndUnpack(script: String): String {
    return JsUnpacker.unpack(script) ?: ""
}

data class AkIframe(val idUrl: String? = null)

data class WYZIESubtitle(val url: String, val display: String? = null, val language: String? = null)

data class VegaSearchResponse(val hits: List<VegaHit>? = null)
data class VegaHit(val document: VegaDocument)
data class VegaDocument(val permalink: String)

inline fun <reified T> okhttp3.Response.parsed(): T {
    return tryParseJson<T>(this.body?.string() ?: "") ?: throw Exception("Failed to parse json")
}

inline fun <reified T> okhttp3.Response.parsedSafe(): T? {
    return tryParseJson<T>(this.body?.string() ?: "")
}

// Android Base64 stub
object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2

    fun encodeToString(input: ByteArray, flags: Int): String {
        return java.util.Base64.getEncoder().encodeToString(input)
    }

    fun decode(input: String, flags: Int): ByteArray {
        return java.util.Base64.getDecoder().decode(input)
    }
}

// ═══════════════════════════════════════════════
// MALSync response types
// ═══════════════════════════════════════════════

data class MALSyncResponses(
    val title: String? = null,
    val sites: MALSyncSites? = null
)
data class MALSyncSites(
    val zoro: Map<String, Map<String, String>>? = null,
    val animepahe: Map<String, Map<String, String>>? = null
)

// ═══════════════════════════════════════════════
// Showbox response types
// ═══════════════════════════════════════════════

data class ShowboxResponse(
    val sources: List<ShowboxSource> = emptyList(),
    val subtitles: List<ShowboxSubtitle> = emptyList()
)
data class ShowboxSource(val url: String, val quality: String = "", val size: String? = null)
data class ShowboxSubtitle(val url: String, val language: String)

// ═══════════════════════════════════════════════
// Flixindia response types
// ═══════════════════════════════════════════════

data class Flixindia(val results: List<FlixindiaItem>? = null)
data class FlixindiaItem(val url: String, val title: String? = null)

// ═══════════════════════════════════════════════
// Vidlink response types
// ═══════════════════════════════════════════════

data class VidlinkResponse(val stream: VidlinkStream)
data class VidlinkStream(val playlist: String)

// ═══════════════════════════════════════════════
// AllMovieland with folder/episode support
// ═══════════════════════════════════════════════

data class AllMovielandFolder(
    val episode: String? = null,
    val file: String? = null,
    val title: String? = null,
    val folder: List<AllMovielandFolder>? = null
)

// ═══════════════════════════════════════════════
// animepahe response types
// ═══════════════════════════════════════════════

data class animepahe(val data: List<AnimepaheEntry>? = null)
data class AnimepaheEntry(val session: String? = null, val episode: Int? = null)
