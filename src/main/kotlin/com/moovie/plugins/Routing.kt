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

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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

            if (tmdbId == null) {
                call.respondText(
                    """{"error":"Missing tmdbId parameter"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@get
            }

            val streams = JSONArray()

            // ═══════════════════════════════════════════════
            // SOURCE 1: Madplay Playsrc (Direct M3U8)
            // ═══════════════════════════════════════════════
            try {
                val madplayUrl = if (season == null)
                    "https://api.madplay.site/api/playsrc?id=$tmdbId&token=direct"
                else
                    "https://madplay.site/api/movies/holly?id=$tmdbId&season=$season&episode=$episode&token=direct"

                val req = Request.Builder().url(madplayUrl)
                    .header("User-Agent", USER_AGENT).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val file = item.optString("file", "")
                        if (file.isNotBlank()) {
                            streams.put(JSONObject().apply {
                                put("server", "Madplay Playsrc")
                                put("url", file)
                                put("quality", "Auto")
                                put("type", if (file.contains(".m3u8")) "hls" else "mp4")
                                val h = item.optJSONObject("headers")
                                if (h != null) put("headers", h)
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                println("Madplay failed: ${e.message}")
            }

            // ═══════════════════════════════════════════════
            // SOURCE 2: Madplay CDN (Direct M3U8)
            // ═══════════════════════════════════════════════
            try {
                val cdnUrl = if (season == null)
                    "https://cdn.madplay.site/api/hls/unknown/$tmdbId/master.m3u8"
                else
                    "https://cdn.madplay.site/api/hls/unknown/$tmdbId/season_$season/episode_$episode/master.m3u8"

                // Verify it's reachable
                val req = Request.Builder().url(cdnUrl)
                    .header("User-Agent", USER_AGENT).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    streams.put(JSONObject().apply {
                        put("server", "Madplay CDN")
                        put("url", cdnUrl)
                        put("quality", "Auto")
                        put("type", "hls")
                    })
                }
            } catch (e: Exception) {
                println("MadplayCDN failed: ${e.message}")
            }

            // ═══════════════════════════════════════════════
            // SOURCE 3: Vidzee (Direct M3U8 — tries 13 servers)
            // ═══════════════════════════════════════════════
            try {
                val vidzeeApi = "https://core.vidzee.wtf"
                for (sr in 1..5) { // Try first 5 servers for speed
                    try {
                        val apiUrl = if (season == null)
                            "$vidzeeApi/api/server?id=$tmdbId&sr=$sr"
                        else
                            "$vidzeeApi/api/server?id=$tmdbId&sr=$sr&ss=$season&ep=$episode"

                        val req = Request.Builder().url(apiUrl)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "$vidzeeApi/")
                            .build()
                        val resp = client.newCall(req).execute()
                        if (!resp.isSuccessful) continue
                        val json = JSONObject(resp.body?.string() ?: "")
                        val sources = json.optJSONArray("sources") ?: continue

                        for (i in 0 until sources.length()) {
                            val src = sources.getJSONObject(i)
                            val finalUrl = src.optString("url", "")
                            if (finalUrl.isBlank()) continue
                            val sType = src.optString("type", "")
                            val lang = src.optString("lang", "")
                            val name = src.optString("name", "Server $sr")

                            streams.put(JSONObject().apply {
                                put("server", "Vidzee $name")
                                put("url", finalUrl)
                                put("quality", "1080p")
                                put("type", if (sType.equals("hls", true)) "hls" else "mp4")
                                if (lang.isNotBlank()) put("language", lang)
                                put("headers", JSONObject().apply {
                                    put("Referer", "$vidzeeApi/")
                                })
                            })
                        }
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                println("Vidzee failed: ${e.message}")
            }

            // ═══════════════════════════════════════════════
            // SOURCE 4: VidsrcCC (Direct M3U8 via hash)
            // ═══════════════════════════════════════════════
            try {
                val vidsrcCCAPI = "https://vidsrc.cc"
                val infoUrl = if (season == null)
                    "$vidsrcCCAPI/v3/movie/$tmdbId"
                else
                    "$vidsrcCCAPI/v3/tv/$tmdbId/$season/$episode"

                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$vidsrcCCAPI/"
                )

                val infoReq = Request.Builder().url(infoUrl).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                val infoResp = client.newCall(infoReq).execute()

                if (infoResp.isSuccessful) {
                    val infoJson = JSONObject(infoResp.body?.string() ?: "")
                    val serversArray = infoJson.optJSONArray("data") ?: JSONArray()

                    for (i in 0 until serversArray.length()) {
                        val serverObj = serversArray.getJSONObject(i)
                        val serverName = serverObj.optString("name", "Unknown")
                        val hash = serverObj.optString("hash", "")
                        if (hash.isBlank()) continue

                        try {
                            val sourceReq = Request.Builder()
                                .url("$vidsrcCCAPI/api/source/$hash")
                                .apply { headers.forEach { (k, v) -> header(k, v) } }
                                .build()
                            val sourceResp = client.newCall(sourceReq).execute()
                            if (!sourceResp.isSuccessful) continue
                            val sourceJson = JSONObject(sourceResp.body?.string() ?: "")
                            val dataObj = sourceJson.optJSONObject("data") ?: continue
                            val streamUrl = dataObj.optString("source", "")
                            if (streamUrl.isBlank()) continue

                            streams.put(JSONObject().apply {
                                put("server", "VidsrcCC [$serverName]")
                                put("url", streamUrl)
                                put("quality", "1080p")
                                put("type", "hls")
                                put("headers", JSONObject(headers))
                            })
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                println("VidsrcCC failed: ${e.message}")
            }

            // ═══════════════════════════════════════════════
            // SOURCE 5: AutoEmbed (Iframe fallback)
            // ═══════════════════════════════════════════════
            streams.put(JSONObject().apply {
                put("server", "AutoEmbed")
                put("url", if (season == null) "https://autoembed.co/movie/tmdb/$tmdbId" else "https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode")
                put("quality", "Auto")
                put("type", "iframe")
            })

            // ═══════════════════════════════════════════════
            // SOURCE 6: VidLink (Iframe fallback)
            // ═══════════════════════════════════════════════
            streams.put(JSONObject().apply {
                put("server", "VidLink")
                put("url", if (season == null) "https://vidlink.pro/movie/$tmdbId" else "https://vidlink.pro/tv/$tmdbId/$season/$episode")
                put("quality", "1080p")
                put("type", "iframe")
            })

            // ═══════════════════════════════════════════════
            // SOURCE 7: Vidsrc.in (Iframe fallback)
            // ═══════════════════════════════════════════════
            streams.put(JSONObject().apply {
                put("server", "Vidsrc")
                put("url", if (season == null) "https://vidsrc.in/embed/movie/$tmdbId" else "https://vidsrc.in/embed/tv/$tmdbId/$season/$episode")
                put("quality", "1080p")
                put("type", "iframe")
            })

            // Build final response
            val responseJson = JSONObject().apply {
                put("provider", "Kotlin CSX Aggregator")
                put("tmdbId", tmdbId)
                if (season != null) put("season", season)
                if (episode != null) put("episode", episode)
                put("status", if (streams.length() > 0) "success" else "failed")
                put("totalSources", streams.length())
                put("stream", streams)
            }

            call.respondText(responseJson.toString(2), ContentType.Application.Json)
        }
    }
}
