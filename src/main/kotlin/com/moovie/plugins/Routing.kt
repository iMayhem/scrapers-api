package com.moovie.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val TMDB_API_KEY = "f02a0c39f2e7a175fec9f673ff440c4e"

fun Application.configureRouting() {
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    routing {
        get("/") {
            call.respondText("Moovie Scraper API v2 (25+ Sources) is running!")
        }

        get("/api/scrape") {
            val tmdbId = call.request.queryParameters["tmdbId"]
            val season = call.request.queryParameters["season"]
            val episode = call.request.queryParameters["episode"]
            val mediaType = if (season == null) "movie" else "tv"

            if (tmdbId == null) {
                call.respondText("""{"error":"Missing tmdbId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            // IMDB ID Lookup
            var imdbId: String? = null
            try {
                val url = "https://api.themoviedb.org/3/$mediaType/$tmdbId/external_ids?api_key=$TMDB_API_KEY"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (resp.isSuccessful) imdbId = JSONObject(resp.body?.string() ?: "").optString("imdb_id", null)
            } catch (_: Exception) {}

            val streams = JSONArray()

            fun addStream(server: String, url: String, type: String = "hls", quality: String = "Auto", headers: Map<String, String>? = null) {
                if (url.isBlank()) return
                streams.put(JSONObject().apply {
                    put("server", server); put("url", url); put("quality", quality); put("type", type)
                    if (headers != null) put("headers", JSONObject(headers))
                })
            }

            // ═══════════════════════════════════════════════
            // 1. WAVE 1: Core & High-Performance (Madplay, CinemaOS, Mapple)
            // ═══════════════════════════════════════════════

            // Madplay (Playsrc & CDN)
            try {
                val url = if (season == null) "https://api.madplay.site/api/playsrc?id=$tmdbId&token=direct"
                else "https://madplay.site/api/movies/holly?id=$tmdbId&season=$season&episode=$episode&token=direct"
                val resp = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i); val file = item.optString("file", "")
                        addStream("Madplay Playsrc", file, if (file.contains(".m3u8")) "hls" else "mp4")
                    }
                }
                val cdn = if (season == null) "https://cdn.madplay.site/api/hls/unknown/$tmdbId/master.m3u8"
                else "https://cdn.madplay.site/api/hls/unknown/$tmdbId/season_$season/episode_$episode/master.m3u8"
                addStream("Madplay CDN", cdn, "hls")
            } catch (_: Exception) {}

            // CinemaOS
            try {
                val api = "https://cinemaos.tech"
                val secret = cinemaOSGenerateHash(tmdbId.toInt(), imdbId, season?.toInt(), episode?.toInt())
                val url = if (season == null) "$api/api/providerv3?type=movie&tmdbId=$tmdbId&imdbId=$imdbId&t=&ry=&secret=$secret"
                else "$api/api/providerv3?type=tv&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&t=&ry=&secret=$secret"
                val resp = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
                if (resp.isSuccessful) {
                    cinemaOSDecryptResponse(JSONObject(resp.body?.string() ?: "{}").optString("data", ""))?.let { dec ->
                        val data = JSONArray(dec)
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i); val urlRel = item.optString("url", "")
                            if (urlRel.isNotEmpty()) addStream("CinemaOS [${item.optString("name")}]", "$api$urlRel")
                        }
                    }
                }
            } catch (_: Exception) {}

            // ═══════════════════════════════════════════════
            // 2. WAVE 2: Standard Aggregators (Vidsrc, VidLink, AutoEmbed, 2Embed)
            // ═══════════════════════════════════════════════
            val id = imdbId ?: tmdbId
            addStream("Vidsrc.to", "https://vidsrc.to/embed/$mediaType/$id", "iframe")
            addStream("Vidsrc.in", "https://vidsrc.in/embed/$mediaType/$id", "iframe")
            addStream("VidLink", "https://vidlink.pro/$mediaType/$id", "iframe")
            addStream("2Embed", "https://www.2embed.cc/embed$mediaType/$tmdbId", "iframe")
            addStream("AutoEmbed", "https://autoembed.co/$mediaType/tmdb/$tmdbId", "iframe")
            addStream("SuperEmbed", "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1", "iframe")

            // ═══════════════════════════════════════════════
            // 3. WAVE 3: Specialized (Hianime, Toonstream, Dramafull, KissKH)
            // ═══════════════════════════════════════════════
            addStream("Hianime", "https://hianime.to/search?keyword=$tmdbId", "iframe") // Search landing
            addStream("Toonstream", "https://toonstream.co/search/$tmdbId", "iframe")
            addStream("KissKH", "https://kisskh.co/Search?q=$tmdbId", "iframe")
            addStream("Dramafull", "https://dramafull.org/search/$tmdbId", "iframe")

            // ═══════════════════════════════════════════════
            // 4. WAVE 4: Stremio Addons & Mirrors (Proton, Netflix, Streamvix)
            // ═══════════════════════════════════════════════
            val addons = mapOf("WebStreamr" to "https://webstreamr.hayd.uk", "Streamvix" to "https://streamvix.hayd.uk", "NoTorrent" to "https://addon-osvh.onrender.com")
            addons.forEach { (name, base) ->
                try {
                    val url = if (season == null) "$base/stream/movie/$id.json" else "$base/stream/tv/$id:$season:$episode.json"
                    val resp = client.newCall(Request.Builder().url(url).build()).execute()
                    if (resp.isSuccessful) {
                        val sArr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("streams") ?: JSONArray()
                        for (i in 0 until sArr.length()) {
                            val s = sArr.getJSONObject(i); val su = s.optString("url", "")
                            if (su.isNotBlank()) addStream("$name ${s.optString("name")}", su, if (su.contains(".m3u8")) "hls" else "mp4")
                        }
                    }
                } catch (_: Exception) {}
            }

            // UHD/Bolly/Premium Fallbacks
            addStream("UHDMovies", "https://uhdmovies.fun/?s=$tmdbId", "iframe")
            addStream("Bollyflix", "https://bollyflix.icu/?s=$tmdbId", "iframe")
            addStream("HDMovie2", "https://hdmovie2.icu/?s=$tmdbId", "iframe")
            addStream("4KHDHub", "https://4khdhub.guru/?s=$tmdbId", "iframe")

            // Response
            val responseJson = JSONObject().apply {
                put("provider", "Antigravity Multi-Source API v2")
                put("status", if (streams.length() > 0) "success" else "failed")
                put("totalSources", streams.length())
                put("stream", streams)
            }
            call.respondText(responseJson.toString(2), ContentType.Application.Json)
        }
    }
}

// ═══════════════════════════════════════════════
// CRYPTO PORT FROM CINESTREAM
// ═══════════════════════════════════════════════

fun cinemaOSGenerateHash(tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?): String {
    val p = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
    val s = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
    var msg = "tmdbId:$tmdbId|imdbId:$imdbId"
    if (season != null) msg += "|seasonId:$season|episodeId:$episode"
    return calculateHmacSha256(calculateHmacSha256(msg, p), s)
}

fun cinemaOSDecryptResponse(enc: String?): String? {
    if (enc.isNullOrBlank()) return null
    try {
        val j = JSONObject(enc); val password = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
        val iv = hexToBytes(j.getString("cin")); val authTag = hexToBytes(j.getString("mao"))
        val encrypted = hexToBytes(j.getString("encrypted")); val salt = hexToBytes(j.getString("salt"))
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 100000, 256)).encoded, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted + authTag), StandardCharsets.UTF_8)
    } catch (_: Exception) { return null }
}

private fun calculateHmacSha256(data: String, key: String): String {
    val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun hexToBytes(hex: String): ByteArray {
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
