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

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val TMDB_API_KEY = "f02a0c39f2e7a175fec9f673ff440c4e"

fun Application.configureRouting() {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    routing {
        get("/") {
            call.respondText("Moovie Scraper API is running! Use /api/scrape?tmdbId=XXXXX")
        }

        get("/api/scrape") {
            val tmdbId = call.request.queryParameters["tmdbId"]
            val season = call.request.queryParameters["season"]
            val episode = call.request.queryParameters["episode"]
            val mediaType = if (season == null) "movie" else "tv"

            if (tmdbId == null) {
                call.respondText(
                    """{"error":"Missing tmdbId parameter"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@get
            }

            // Fetch IMDB ID if needed (for Vegamovies/CinemaOS)
            var imdbId: String? = null
            try {
                val url = "https://api.themoviedb.org/3/$mediaType/$tmdbId/external_ids?api_key=$TMDB_API_KEY"
                val req = Request.Builder().url(url).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    imdbId = json.optString("imdb_id", null)
                }
            } catch (e: Exception) {
                println("Failed to fetch IMDB ID: ${e.message}")
            }

            val streams = JSONArray()

            // ═══════════════════════════════════════════════
            // HELPER: Add Stream
            // ═══════════════════════════════════════════════
            fun addStream(server: String, url: String, type: String = "hls", quality: String = "Auto", headers: Map<String, String>? = null) {
                if (url.isBlank()) return
                streams.put(JSONObject().apply {
                    put("server", server)
                    put("url", url)
                    put("quality", quality)
                    put("type", type)
                    if (headers != null) put("headers", JSONObject(headers))
                })
            }

            // ═══════════════════════════════════════════════
            // 1. Madplay & Existing Sources
            // ═══════════════════════════════════════════════
            
            // Madplay Playsrc
            try {
                val url = if (season == null) "https://api.madplay.site/api/playsrc?id=$tmdbId&token=direct"
                          else "https://madplay.site/api/movies/holly?id=$tmdbId&season=$season&episode=$episode&token=direct"
                val resp = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val file = item.optString("file", "")
                        val h = mutableMapOf<String, String>()
                        item.optJSONObject("headers")?.let { ho -> ho.keys().forEach { k -> h[k] = ho.getString(k) } }
                        addStream("Madplay Playsrc", file, if (file.contains(".m3u8")) "hls" else "mp4", "Auto", h)
                    }
                }
            } catch (_: Exception) {}

            // ═══════════════════════════════════════════════
            // 2. CinemaOS (Native Port)
            // ═══════════════════════════════════════════════
            try {
                val api = "https://cinemaos.tech"
                val secret = cinemaOSGenerateHash(tmdbId.toInt(), imdbId, season?.toInt(), episode?.toInt())
                val url = if (season == null) "$api/api/providerv3?type=movie&tmdbId=$tmdbId&imdbId=$imdbId&t=&ry=&secret=$secret"
                          else "$api/api/providerv3?type=tv&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&t=&ry=&secret=$secret"
                
                val req = Request.Builder().url(url)
                    .header("Origin", api)
                    .header("Referer", "$api/")
                    .header("User-Agent", USER_AGENT).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val resJson = JSONObject(resp.body?.string() ?: "{}")
                    val decrypted = cinemaOSDecryptResponse(resJson.optString("data", ""))
                    if (decrypted != null) {
                        val data = JSONArray(decrypted)
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val name = item.optString("name", "Unknown")
                            val urlRel = item.optString("url", "")
                            if (urlRel.isNotEmpty()) {
                                addStream("CinemaOS [$name]", "$api$urlRel", "hls", "Auto", mapOf("Referer" to "$api/"))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // ═══════════════════════════════════════════════
            // 3. Mapple (Native Port)
            // ═══════════════════════════════════════════════
            try {
                val api = "https://mapple.uk"
                val url = "$api/api/server?id=$tmdbId" // Simplified based on CSX logic
                val resp = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    if (json.optBoolean("success")) {
                        val streamUrl = json.optJSONObject("data")?.optString("stream_url", "") ?: ""
                        if (streamUrl.isNotEmpty()) addStream("Mapple", streamUrl, "hls", "1080p")
                    }
                }
            } catch (_: Exception) {}

            // ═══════════════════════════════════════════════
            // 4. Stremio Addons (WebStreamr, Streamvix, NoTorrent)
            // ═══════════════════════════════════════════════
            val stremioSources = mapOf(
                "WebStreamr" to "https://webstreamr.hayd.uk",
                "Streamvix" to "https://streamvix.hayd.uk",
                "NoTorrent" to "https://addon-osvh.onrender.com"
            )

            stremioSources.forEach { (name, base) ->
                try {
                    val id = imdbId ?: tmdbId
                    val url = if (season == null) "$base/stream/movie/$id.json"
                              else "$base/stream/tv/$id:$season:$episode.json"
                    val resp = client.newCall(Request.Builder().url(url).build()).execute()
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val streamsArr = json.optJSONArray("streams") ?: JSONArray()
                        for (i in 0 until streamsArr.length()) {
                            val s = streamsArr.getJSONObject(i)
                            val sUrl = s.optString("url", "")
                            if (sUrl.isNotBlank()) addStream("$name ${s.optString("name", "")}", sUrl, if (sUrl.contains(".m3u8")) "hls" else "mp4")
                        }
                    }
                } catch (_: Exception) {}
            }

            // ═══════════════════════════════════════════════
            // 5. Iframe Fallbacks
            // ═══════════════════════════════════════════════
            addStream("AutoEmbed", if (season == null) "https://autoembed.co/movie/tmdb/$tmdbId" else "https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode", "iframe")
            addStream("VidLink", if (season == null) "https://vidlink.pro/movie/$tmdbId" else "https://vidlink.pro/tv/$tmdbId/$season/$episode", "iframe")
            addStream("Vidsrc.in", if (season == null) "https://vidsrc.in/embed/movie/$tmdbId" else "https://vidsrc.in/embed/tv/$tmdbId/$season/$episode", "iframe")
            addStream("MovieBox", if (season == null) "https://fembox.aether.mom/movie/$tmdbId" else "https://fembox.aether.mom/tv/$tmdbId/$season/$episode", "iframe")

            // Response
            val responseJson = JSONObject().apply {
                put("provider", "Antigravity Power Scraper")
                put("status", if (streams.length() > 0) "success" else "failed")
                put("total", streams.length())
                put("stream", streams)
            }
            call.respondText(responseJson.toString(2), ContentType.Application.Json)
        }
    }
}

// ═══════════════════════════════════════════════
// CRYPTO HELPERS (PORTED FROM CINESTREAM)
// ═══════════════════════════════════════════════

fun cinemaOSGenerateHash(tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?): String {
    val primary = "a7f3b9c2e8d4f1a6b5c9e2d7f4a8b3c6e1d9f7a4b2c8e5d3f9a6b4c1e7d2f8a5"
    val secondary = "d3f8a5b2c9e6d1f7a4b8c5e2d9f3a6b1c7e4d8f2a9b5c3e7d4f1a8b6c2e9d5f3"
    var message = "tmdbId:$tmdbId|imdbId:$imdbId"
    if (season != null && episode != null) message += "|seasonId:$season|episodeId:$episode"
    val firstHash = calculateHmacSha256(message, primary)
    return calculateHmacSha256(firstHash, secondary)
}

fun cinemaOSDecryptResponse(encryptedData: String?): String? {
    if (encryptedData.isNullOrBlank()) return null
    try {
        val json = JSONObject(encryptedData)
        val encrypted = json.optString("encrypted")
        val iv = json.optString("cin")
        val authTag = json.optString("mao")
        val salt = json.optString("salt")
        if (encrypted.isEmpty() || iv.isEmpty() || authTag.isEmpty() || salt.isEmpty()) return null

        val passwordStr = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
        val ivBytes = hexStringToByteArray(iv)
        val authTagBytes = hexStringToByteArray(authTag)
        val encryptedBytes = hexStringToByteArray(encrypted)
        val saltBytes = hexStringToByteArray(salt)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passwordStr.toCharArray(), saltBytes, 100000, 256)
        val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ivBytes))
        val decryptedBytes = cipher.doFinal(encryptedBytes + authTagBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        return null
    }
}

private fun calculateHmacSha256(data: String, key: String): String {
    val algorithm = "HmacSHA256"
    val secretKeySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), algorithm)
    val mac = Mac.getInstance(algorithm)
    mac.init(secretKeySpec)
    return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun hexStringToByteArray(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
