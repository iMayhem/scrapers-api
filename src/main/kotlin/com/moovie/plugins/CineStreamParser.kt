package com.moovie.plugins

import com.google.gson.annotations.SerializedName

// Data classes for CineStream
// Adapted for Moovie Kotlin API

data class AllLoadLinksData(
    val title: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val year: Int? = null,
    val airedYear: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isAnime: Boolean = false,
    val isBollywood: Boolean = false,
    val isAsian: Boolean = false,
    val isCartoon: Boolean = false,
    val originalTitle: String? = null,
    val imdbTitle: String? = null,
    val imdbSeason : Int? = null,
    val imdbEpisode : Int? = null,
    val imdbYear : Int? = null,
)

data class Watch32(
    val type: String,
    val link: String,
)

data class EncDecResponse(
    val result: EncDecResult?
)

data class EncDecResult(
    val servers: String?,
    val stream: String?
)

data class VidfastServer(
    val name: String?,
    val description: String?,
    val data: String?
)

data class VidfastStreamResponse(
    val url: String?,
    val tracks: List<VidfastTrack>?,
    @SerializedName("4kAvailable") val is4kAvailable: Boolean?
)

data class VidfastTrack(
    val file: String?,
    val label: String?
)

data class StremioSubtitleResponse(
    val subtitles: List<StremioSubtitle> = emptyList()
)

data class StremioSubtitle(
    val lang_code: String? = null,
    val lang: String? = null,
    val title: String? = null,
    val url: String? = null,
)

data class TorrentioResponse(val streams: List<TorrentioStream>)

data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val description: String? = null,
    val sources: List<String>? = null,
)

data class StreamifyResponse(
    var streams: List<Streamify>
)

data class StreamifySubs(
    var url  : String,
    var lang : String
)

data class Streamify(
    var name: String? = null,
    var type: String? = null,
    var url: String,
    var title: String? = null,
    var description: String? = null,
    var subtitles: List<StreamifySubs>? = null,
    @SerializedName("behaviorHints" ) var behaviorHints: StreamifyBehaviorHints? = StreamifyBehaviorHints()
)

data class StreamifyBehaviorHints(
    @SerializedName("proxyHeaders" ) var proxyHeaders: StreamifyProxyHeaders? = StreamifyProxyHeaders(),
    @SerializedName("headers") var headers: Map<String, String>? = null
)

data class StreamifyBehaviorHintRequest(
    @SerializedName("Referer") val Referer: String? = null,
    @SerializedName("Origin") val Origin: String? = null,
    @SerializedName("User-Agent") val userAgent: String? = null
)

data class StreamifyProxyHeaders(
    @SerializedName("request" ) var request: StreamifyBehaviorHintRequest? = StreamifyBehaviorHintRequest()
)

data class CinemaOSReponse(
    val data: CinemaOSReponseData,
    val encrypted: Boolean,
)

data class CinemaOSReponseData(
    val encrypted: String,
    val cin: String,
    val mao: String,
    val salt: String,
)
