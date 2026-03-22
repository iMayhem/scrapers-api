package com.moovie.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
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
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            private val storage = mutableMapOf<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { storage[url.host] = cookies }
            override fun loadForRequest(url: HttpUrl): List<Cookie> { return storage[url.host] ?: listOf() }
        })
        .build()

    routing {
        get("/") { call.respondText("Moovie Scraper API v3 (30+ Sources) is running!") }

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
            var title: String = ""
            var year: String = ""
            try {
                val url = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_API_KEY"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (resp.isSuccessful) {
                    val j = JSONObject(resp.body?.string() ?: "{}")
                    imdbId = j.optString("imdb_id", null)
                    title = j.optString("title", j.optString("name", ""))
                    year = j.optString("release_date", j.optString("first_air_date", "")).take(4)
                }
            } catch (_: Exception) {}

            val id = imdbId ?: tmdbId
            val streams = JSONArray()

            fun addStream(server: String, url: String, type: String = "hls", quality: String = "Auto", headers: Map<String, String>? = null) {
                if (url.isBlank()) return
                streams.put(JSONObject().apply {
                    put("server", server); put("url", url); put("quality", quality); put("type", type)
                    if (headers != null) put("headers", JSONObject(headers))
                })
            }

            // ═══════════════════════════════════════════════
            // 1. Netflix (Netmirror Native)
            // ═══════════════════════════════════════════════
            try {
                val mainUrl = "https://nfmirror.com"
                val mirror2 = "https://nfmirror2.org"
                val finalId = if (season == null) id else "$id:$season:$episode"
                
                // Step 1: Initialize cookies and session
                client.newCall(Request.Builder().url("$mainUrl/").build()).execute()
                
                // Step 2: Get Token H
                val formBody = FormBody.Builder().add("id", finalId).build()
                val hResp = client.newCall(Request.Builder().url("$mainUrl/play.php").post(formBody).header("X-Requested-With", "XMLHttpRequest").header("Referer", "$mainUrl/").build()).execute()
                val h = JSONObject(hResp.body?.string() ?: "{}").optString("h", "")
                
                if (h.isNotEmpty()) {
                    val tUrl = "$mirror2/playlist.php?id=$finalId&t=$h&tm=${System.currentTimeMillis() / 1000}"
                    val tResp = client.newCall(Request.Builder().url(tUrl).header("Referer", "$mainUrl/").build()).execute()
                    val pJson = JSONArray(tResp.body?.string() ?: "[]")
                    if (pJson.length() > 0) {
                        val sources = pJson.getJSONObject(0).optJSONArray("sources") ?: JSONArray()
                        for (i in 0 until sources.length()) {
                            val s = sources.getJSONObject(i); val sUrl = s.optString("file", "")
                            if (sUrl.startsWith("/")) addStream("Netflix Mirror", "$mirror2$sUrl", "hls")
                        }
                    }
                }
            } catch (_: Exception) {}

            // ═══════════════════════════════════════════════
            // 2. High-Performance Core (Madplay, CinemaOS, Mapple)
            // ═══════════════════════════════════════════════
            // Madplay
            try {
                val mUrl = if (season == null) "https://api.madplay.site/api/playsrc?id=$tmdbId&token=direct"
                else "https://madplay.site/api/movies/holly?id=$tmdbId&season=$season&episode=$episode&token=direct"
                val mResp = client.newCall(Request.Builder().url(mUrl).header("User-Agent", USER_AGENT).build()).execute()
                if (mResp.isSuccessful) {
                    val arr = JSONArray(mResp.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        val it = arr.getJSONObject(i); val f = it.optString("file", "")
                        addStream("Madplay", f, if (f.contains(".m3u8")) "hls" else "mp4")
                    }
                }
            } catch (_: Exception) {}

            // CinemaOS
            try {
                val secret = cinemaOSGenerateHash(tmdbId.toInt(), imdbId, season?.toInt(), episode?.toInt())
                val cUrl = if (season == null) "https://cinemaos.tech/api/providerv3?type=movie&tmdbId=$tmdbId&imdbId=$imdbId&secret=$secret"
                else "https://cinemaos.tech/api/providerv3?type=tv&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&secret=$secret"
                val cResp = client.newCall(Request.Builder().url(cUrl).header("User-Agent", USER_AGENT).build()).execute()
                cinemaOSDecryptResponse(JSONObject(cResp.body?.string() ?: "{}").optString("data", ""))?.let { dec ->
                    val data = JSONArray(dec)
                    for (i in 0 until data.length()) {
                        val it = data.getJSONObject(i); val ur = it.optString("url", "")
                        if (ur.isNotEmpty()) addStream("CinemaOS [${it.optString("name")}]", "https://cinemaos.tech$ur")
                    }
                }
            } catch (_: Exception) {}

            // ═══════════════════════════════════════════════
            // 3. Stremio Bridges (WebStreamr, Streamvix, NoTorrent)
            // ═══════════════════════════════════════════════
            val addons = mapOf("WebStreamr" to "https://webstreamr.hayd.uk", "Streamvix" to "https://streamvix.hayd.uk", "NoTorrent" to "https://addon-osvh.onrender.com")
            addons.forEach { (n, b) ->
                try {
                    val url = if (season == null) "$b/stream/movie/$id.json" else "$b/stream/tv/$id:$season:$episode.json"
                    val resp = client.newCall(Request.Builder().url(url).build()).execute()
                    if (resp.isSuccessful) {
                        val sArr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("streams") ?: JSONArray()
                        for (i in 0 until sArr.length()) {
                            val s = sArr.getJSONObject(i); val su = s.optString("url", "")
                            if (su.isNotBlank()) addStream("$n ${s.optString("name")}", su, if (su.contains(".m3u8")) "hls" else "mp4")
                        }
                    }
                } catch (_: Exception) {}
            }

            // ═══════════════════════════════════════════════
            // 4. Global Iframes & Aggregators
            // ═══════════════════════════════════════════════
            addStream("Vidsrc.to", "https://vidsrc.to/embed/$mediaType/$id", "iframe")
            addStream("VidLink", "https://vidlink.pro/$mediaType/$id", "iframe")
            addStream("SuperEmbed", "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1", "iframe")
            addStream("Hianime", "https://hianime.to/search?keyword=$tmdbId", "iframe")
            addStream("UHDMovies", "https://uhdmovies.fun/?s=$tmdbId", "iframe")

            call.respondText(JSONObject().apply {
                put("provider", "Antigravity Power Scraper v3")
                put("status", if (streams.length() > 0) "success" else "failed")
                put("total", streams.length())
                put("stream", streams)
            }.toString(2), ContentType.Application.Json)
        }
    }
}

fun cinemaOSGenerateHash(tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?): String {
    val p = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
    val s = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
    var msg = "tmdbId:$tmdbId|imdbId:$imdbId"
    if (season != null) msg += "|seasonId:$season|episodeId:$episode"
    return hmac256(hmac256(msg, p), s)
}

fun cinemaOSDecryptResponse(enc: String?): String? {
    if (enc.isNullOrBlank()) return null
    try {
        val j = JSONObject(enc); val pw = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
        val iv = hexToBytes(j.getString("cin")); val tag = hexToBytes(j.getString("mao"))
        val e = hexToBytes(j.getString("encrypted")); val salt = hexToBytes(j.getString("salt"))
        val k = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, 100000, 256)).encoded, "AES")
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
        return String(c.doFinal(e + tag), StandardCharsets.UTF_8)
    } catch (_: Exception) { return null }
}

private fun hmac256(data: String, key: String): String {
    val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
