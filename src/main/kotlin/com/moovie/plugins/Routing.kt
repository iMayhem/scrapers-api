package com.moovie.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

fun Application.configureRouting() {
    val client = OkHttpClient.Builder().build()

    routing {
        get("/") {
            call.respondText("Moovie Scraper API Wrapper is running natively!")
        }

        get("/api/scrape") {
            val tmdbId = call.request.queryParameters["tmdbId"]
            val season = call.request.queryParameters["season"]
            val episode = call.request.queryParameters["episode"]
            
            if (tmdbId == null) {
                call.respondText("Missing tmdbId parameter", status = io.ktor.http.HttpStatusCode.BadRequest)
                return@get
            }

            // Manually mirroring CineStream's `invokePlaysrc` logic:
            var playsrcStreamUrl: String? = null
            var playsrcHeaders: Map<String, String> = emptyMap()

            // Build Madplay Playsrc URL
            val madplayUrl = if (season == null) {
                "https://api.madplay.site/api/playsrc?id=${tmdbId}&token=direct"
            } else {
                "https://madplay.site/api/movies/holly?id=${tmdbId}&season=${season}&episode=${episode}&token=direct"
            }

            try {
                val request = Request.Builder()
                    .url(madplayUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful && response.body != null) {
                    val rawJson = response.body!!.string()
                    // JSON is an array of video sources: [ { file: "...", headers: {...} } ]
                    val array = JSONArray(rawJson)
                    if (array.length() > 0) {
                        val firstItem = array.getJSONObject(0)
                        playsrcStreamUrl = firstItem.optString("file", null)
                        
                        // Parse optional headers
                        val headersObj = firstItem.optJSONObject("headers")
                        if (headersObj != null) {
                            val map = mutableMapOf<String, String>()
                            for (key in headersObj.keys()) {
                                map[key] = headersObj.optString(key)
                            }
                            playsrcHeaders = map
                        }
                    }
                }
            } catch (e: Exception) {
                println("Failed to fetch Playsrc endpoint: ${e.message}")
            }

            // Fallback to CineStream's `invokeMadplayCDN`
            if (playsrcStreamUrl == null || playsrcStreamUrl.isEmpty()) {
                playsrcStreamUrl = if (season == null) {
                    "https://cdn.madplay.site/api/hls/unknown/${tmdbId}/master.m3u8"
                } else {
                    "https://cdn.madplay.site/api/hls/unknown/${tmdbId}/season_${season}/episode_${episode}/master.m3u8"
                }
            }

            if (playsrcStreamUrl != null) {
                // Return exactly what your TypeScript app expects from `runAll`
                val responseJson = JSONObject().apply {
                    put("provider", "CineStream (Native Port)")
                    put("status", "success")
                    put("stream", JSONArray().apply {
                        put(JSONObject().apply {
                            put("url", playsrcStreamUrl)
                            put("quality", "HLS")
                            put("type", "hls")
                            if (playsrcHeaders.isNotEmpty()) {
                                put("headers", JSONObject(playsrcHeaders))
                            }
                        })
                    })
                }
                
                call.respondText(responseJson.toString(), io.ktor.http.ContentType.Application.Json)
            } else {
                call.respondText("""
                    {
                        "provider": "CineStream (Native Port)",
                        "status": "failed",
                        "error": "No streams found for TMDB $tmdbId on any CDN."
                    }
                """.trimIndent(), io.ktor.http.ContentType.Application.Json)
            }
        }
    }
}
