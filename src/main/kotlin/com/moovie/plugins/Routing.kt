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
import java.net.URLEncoder
import kotlinx.coroutines.*
import java.util.Collections

private const val TMDB_API_KEY = "f02a0c39f2e7a175fec9f673ff440c4e"
private const val PING_TIMEOUT_MS = 3000L

/** Measure response latency (ms) for direct stream URLs. Returns null on failure or timeout. */
private fun measurePing(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>?,
): Long? {
    val pingClient = client.newBuilder()
        .connectTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    val start = System.currentTimeMillis()
    return try {
        val reqBuilder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> reqBuilder.header(k, v) }
        val headReq = reqBuilder.head().build()
        pingClient.newCall(headReq).execute().use { resp ->
            if (resp.isSuccessful) System.currentTimeMillis() - start
            else {
                val getReq = Request.Builder().url(url)
                    .addHeader("Range", "bytes=0-0")
                    .apply { headers?.forEach { (k, v) -> header(k, v) } }
                    .build()
                val start2 = System.currentTimeMillis()
                pingClient.newCall(getReq).execute().use { getResp ->
                    if (getResp.isSuccessful) System.currentTimeMillis() - start2 else null
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

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
        get("/") { call.respondText("Moovie Mega-Scraper v4 (40+ Sources) is Live!") }

        get("/api/config") {
            val configUrl = System.getenv("CONFIG_URL") ?: "https://gist.githubusercontent.com/iMayhem/abbb593bdcd0bfc3d54a6284e81cc880/raw/scrapers.json"
            try {
                client.newCall(Request.Builder().url("$configUrl?t=${System.currentTimeMillis()}").build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        call.respondText(body, ContentType.Application.Json)
                    } else {
                        call.respondText("""{"providers":[],"preferences":{"prioritizeBy":"latency","maxPreferredSizeGb":3.0}}""", ContentType.Application.Json)
                    }
                }
            } catch (e: Exception) {
                call.respondText("""{"providers":[],"preferences":{"prioritizeBy":"latency","maxPreferredSizeGb":3.0}}""", ContentType.Application.Json)
            }
        }

        get("/api/scrape") {
            val tmdbId = call.request.queryParameters["tmdbId"]
            val season = call.request.queryParameters["season"]
            val episode = call.request.queryParameters["episode"]
            val isStreaming = call.request.queryParameters["stream"] == "true"
            val mediaType = if (season == null) "movie" else "tv"

            if (tmdbId == null) {
                call.respondText("""{"error":"Missing tmdbId"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            // IMDB ID Lookup
            var imdbId: String? = null
            try {
                val url = "https://api.themoviedb.org/3/$mediaType/$tmdbId/external_ids?api_key=$TMDB_API_KEY"
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) imdbId = JSONObject(resp.body?.string() ?: "").optString("imdb_id", null)
                }
            } catch (_: Exception) {}

            val streamsList = Collections.synchronizedList(mutableListOf<JSONObject>())
            val eventChannel = if (isStreaming) kotlinx.coroutines.channels.Channel<JSONObject>(kotlinx.coroutines.channels.Channel.UNLIMITED) else null
            
            val id = if (imdbId.isNullOrEmpty()) tmdbId else imdbId
            
            suspend fun emit(type: String, data: JSONObject = JSONObject()) {
                if (eventChannel != null) {
                    eventChannel.send(data.apply { put("msgType", type) })
                }
            }
            
            suspend fun emitLog(msg: String) = emit("log", JSONObject().put("message", msg))

            fun parseFileSizeGb(server: String): Double? {
                val gbMatch = Regex("""(\d+\.?\d*)\s*GB""", RegexOption.IGNORE_CASE).find(server)
                if (gbMatch != null) return gbMatch.groupValues[1].toDoubleOrNull()
                val mbMatch = Regex("""(\d+\.?\d*)\s*MB""", RegexOption.IGNORE_CASE).find(server)
                if (mbMatch != null) return mbMatch.groupValues[1].toDoubleOrNull()?.div(1024.0)
                return null
            }
            
            val cacheKey = "scrape:\${id}:\${season ?: \"null\"}:\${episode ?: \"null\"}"
            var cachedData: JSONArray? = null
            
            if (Redis.isEnabled()) {
                val cachedString = Redis.get(cacheKey)
                if (!cachedString.isNullOrBlank()) {
                    try {
                        cachedData = JSONArray(cachedString)
                    } catch (e: Exception) {
                        emitLog("Warning: Failed to parse cached data")
                    }
                }
            }

            if (cachedData != null && cachedData!!.length() > 0) {
                println("Redis: Cache hit for \${id}! Serving \${cachedData!!.length()} streams.")
                emitLog("Cache hit! Serving \${cachedData!!.length()} streams from Redis.")
                if (isStreaming) {
                    call.respondTextWriter(ContentType.Application.Json) {
                        for (i in 0 until cachedData!!.length()) {
                            val streamObj = cachedData!!.getJSONObject(i)
                            // Re-emit each stream event
                            write(streamObj.apply { put("msgType", "stream") }.toString() + "\n")
                            flush()
                            delay(10) // Small delay to ensure client can process
                        }
                    }
                } else {
                    call.respondText(JSONObject().apply {
                        put("provider", "Antigravity Mega-Aggregator v4")
                        put("status", "success")
                        put("total", cachedData!!.length())
                        put("stream", cachedData)
                        put("cached", true)
                    }.toString(2), ContentType.Application.Json)
                }
                return@get
            }


            fun createStreamObj(
                server: String,
                url: String,
                type: String = "hls",
                quality: String = "Auto",
                headers: Map<String, String>? = null,
                latencyMs: Long? = null,
                providerKey: String? = null,
                fileSizeGb: Double? = null,
            ): JSONObject {
                return JSONObject().apply {
                    put("server", server)
                    put("url", url)
                    put("quality", quality)
                    put("type", type)
                    if (headers != null) put("headers", JSONObject(headers))
                    if (latencyMs != null) put("latencyMs", latencyMs)
                    if (providerKey != null) put("providerKey", providerKey)
                    if (fileSizeGb != null) put("fileSizeGb", fileSizeGb)
                }
            }

            suspend fun addStream(
                server: String,
                url: String,
                type: String = "hls",
                quality: String = "Auto",
                headers: Map<String, String>? = null,
                providerKey: String? = null,
            ) {
                if (url.isBlank()) return
                val latencyMs = if (type in listOf("hls", "mp4", "dash")) {
                    withContext(Dispatchers.IO) { measurePing(client, url, headers) }
                } else null
                val fileSizeGb = parseFileSizeGb(server)
                val obj = createStreamObj(server, url, type, quality, headers, latencyMs, providerKey, fileSizeGb)
                streamsList.add(obj)
                emit("stream", obj)
            }

            val job = launch(Dispatchers.IO) {
                coroutineScope {
                    val tasks = mutableListOf<Deferred<Unit>>()

                    // 1. Core & Premium
                    tasks.add(async {
                        if (Settings.isEnabled("p_netflix")) {
                            emitLog("Checking Netflix Mirror...")
                            try {
                                val main = "https://nfmirror.com"; val mir2 = "https://nfmirror2.org"
                                val finalId = if (season == null) id!! else "$id:$season:$episode"
                                client.newCall(Request.Builder().url("$main/").build()).execute().use { _ -> }
                                val hRespStr = client.newCall(Request.Builder().url("$main/play.php").post(FormBody.Builder().add("id", finalId).build()).header("X-Requested-With", "XMLHttpRequest").header("Referer", "$main/").build()).execute().use { it.body?.string() ?: "{}" }
                                val h = JSONObject(hRespStr).optString("h", "")
                                if (h.isNotEmpty()) {
                                    val tRespStr = client.newCall(Request.Builder().url("$mir2/playlist.php?id=$finalId&t=$h&tm=${System.currentTimeMillis()/1000}").header("Referer", "$main/").build()).execute().use { it.body?.string() ?: "[]" }
                                    val pJson = JSONArray(tRespStr)
                                    if (pJson.length() > 0) {
                                        val srcs = pJson.getJSONObject(0).optJSONArray("sources") ?: JSONArray()
                                        for (i in 0 until srcs.length()) {
                                            val s = srcs.getJSONObject(i); val su = s.optString("file", "")
                                            if (su.startsWith("/")) addStream("Netflix Mirror", "$mir2$su", "hls", "Auto", null, "p_netflix")
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    })

                    tasks.add(async {
                        if (Settings.isEnabled("p_madplaycdn")) {
                            emitLog("Checking Madplay CDN...")
                            addStream("Madplay CDN", if (season == null) "https://cdn.madplay.site/api/hls/unknown/$tmdbId/master.m3u8" else "https://cdn.madplay.site/api/hls/unknown/$tmdbId/season_$season/episode_$episode/master.m3u8", "hls", "Auto", null, "p_madplaycdn")
                        }
                    })

                    tasks.add(async {
                        if (Settings.isEnabled("p_cinemaos")) {
                            emitLog("Checking CinemaOS...")
                            try {
                                val secret = cinemaOSGenerateHash(tmdbId.toInt(), imdbId, season?.toInt(), episode?.toInt())
                                val cUrl = if (season == null) "https://cinemaos.tech/api/providerv3?type=movie&tmdbId=$tmdbId&imdbId=$imdbId&secret=$secret" else "https://cinemaos.tech/api/providerv3?type=tv&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&secret=$secret"
                                val cRespStr = client.newCall(Request.Builder().url(cUrl).header("User-Agent", USER_AGENT).build()).execute().use { it.body?.string() ?: "{}" }
                                cinemaOSDecryptResponse(JSONObject(cRespStr).optString("data", ""))?.let { dec ->
                                    val data = JSONArray(dec)
                                    for (i in 0 until data.length()) {
                                        val it = data.getJSONObject(i); val ur = it.optString("url", "")
                                        if (ur.isNotEmpty()) addStream("CinemaOS [${it.optString("name")}]", "https://cinemaos.tech$ur", "hls", "Auto", null, "p_cinemaos")
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    })

                    // 2. Wave 4: Anime & Regional (Parallelized individual ones)
                    if (Settings.isEnabled("p_sudatchi")) tasks.add(async { emitLog("Checking Sudatchi..."); addStream("Sudatchi", "https://sudatchi.com/api/streams?id=$tmdbId", "hls", "Auto", null, "p_sudatchi") })
                    if (Settings.isEnabled("p_allanime")) tasks.add(async { emitLog("Checking AllAnime..."); addStream("AllAnime", "https://allmanga.to/search?q=$tmdbId", "iframe", "Auto", null, "p_allanime") })
                    if (Settings.isEnabled("p_animepahe")) tasks.add(async { emitLog("Checking AnimePahe..."); addStream("AnimePahe", "https://animepahe.ru/search?q=$tmdbId", "iframe", "Auto", null, "p_animepahe") })
                    if (Settings.isEnabled("p_gojo")) tasks.add(async { emitLog("Checking Animetsu..."); addStream("Animetsu", "https://animetsu.cc/search/$tmdbId", "iframe", "Auto", null, "p_gojo") })
                    if (Settings.isEnabled("p_flixindia")) tasks.add(async { emitLog("Checking FlixIndia..."); addStream("FlixIndia", "https://flixindia.xyz/?s=$tmdbId", "iframe", "Auto", null, "p_flixindia") })
                    if (Settings.isEnabled("p_rogmovies")) tasks.add(async { emitLog("Checking RogMovies..."); addStream("RogMovies", "https://rogmovies.fun/?s=$tmdbId", "iframe", "Auto", null, "p_rogmovies") })
                    if (Settings.isEnabled("p_bollywood")) tasks.add(async { emitLog("Checking Gramcinema..."); addStream("Gramcinema", "https://gramcinema.com/?s=$tmdbId", "iframe", "Auto", null, "p_bollywood") })

                    // 3. Wave 5: MovieBox
                    tasks.add(async {
                        if (Settings.isEnabled("p_moviebox")) {
                            emitLog("Checking MovieBox...")
                            try {
                                val boxBase = "https://vidjoy.pro/embed/api/fastfetch"
                                val targetUrl = if (season == null) "$boxBase/$tmdbId?sr=0" else "$boxBase/$tmdbId/$season/$episode?sr=0"
                                
                                var body = ""
                                try {
                                    client.newCall(Request.Builder().url(targetUrl)
                                        .header("User-Agent", USER_AGENT)
                                        .header("Accept", "application/json, text/plain, */*")
                                        .header("Referer", "https://vidjoy.pro/").build()).execute().use { dResp ->
                                        body = dResp.body?.string() ?: ""
                                    }
                                } catch (_: Exception) {}

                                if (!body.startsWith("{")) {
                                    try {
                                        val mUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Mobile/15E148 Safari/604.1"
                                        client.newCall(Request.Builder().url(targetUrl).header("User-Agent", mUA).build()).execute().use { mResp ->
                                            body = mResp.body?.string() ?: ""
                                        }
                                    } catch (_: Exception) {}
                                }

                                if (body.startsWith("{")) {
                                    val bData = JSONObject(body)
                                    val bSrcs = bData.optJSONArray("url") ?: JSONArray()
                                    val bRef = bData.optJSONObject("headers")?.optString("Referer", "") ?: ""
                                    val bHeaders = if (bRef.isNotEmpty()) mapOf("Referer" to bRef) else null
                                    
                                    for (i in 0 until bSrcs.length()) {
                                        val s = bSrcs.getJSONObject(i)
                                        val su = s.optString("link", "")
                                        val res = s.optString("resulation", "Auto")
                                        val st = if (su.contains(".m3u8")) "hls" else "mp4"
                                        if (su.isNotEmpty()) addStream("MovieBox [$res]", su, st, res, bHeaders, "p_moviebox")
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    })

                    // 4. Wave 6: CineStream
                    tasks.add(async {
                        if (Settings.isEnabled("p_cinestream")) {
                            emitLog("Checking CineStream...")
                            try {
                                val cineStream = CineStreamProvider()
                                val passData = AppUtils.toJson(CineStreamProvider.PassData(id!!, mediaType))
                                val loadResponse = cineStream.load(passData)
                                if (loadResponse is MovieLoadResponse) {
                                    cineStream.loadLinks(loadResponse.dataUrl!!, false, { _ -> }) { link ->
                                        val st = when (link.type) {
                                            ExtractorLinkType.M3U8 -> "hls"
                                            ExtractorLinkType.DASH -> "dash"
                                            else -> if (link.url.contains(".m3u8")) "hls" else "mp4"
                                        }
                                        launch {
                                            addStream("CineStream [${link.name}]", link.url, st, link.quality.toString(), link.headers, "p_cinestream")
                                        }
                                    }
                                } else if (loadResponse is TvSeriesLoadResponse) {
                                    val ep = loadResponse.episodes.find { it.season == season?.toInt() && it.episode == episode?.toInt() }
                                    if (ep != null) {
                                        cineStream.loadLinks(ep.data, false, { _ -> }) { link ->
                                            val st = when (link.type) {
                                                ExtractorLinkType.M3U8 -> "hls"
                                                ExtractorLinkType.DASH -> "dash"
                                                else -> if (link.url.contains(".m3u8")) "hls" else "mp4"
                                            }
                                            launch {
                                                addStream("CineStream [${link.name}]", link.url, st, link.quality.toString(), link.headers, "p_cinestream")
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        Unit
                    })

                    // 5. Fallbacks
                    tasks.add(async {
                        emitLog("Checking Fallbacks...")
                        val fallbacks = mapOf(
                            "Vidsrc.to" to "https://vidsrc.to/embed/$mediaType/$id",
                            "Vidsrc.xyz" to "https://vidsrc.xyz/embed/$mediaType/$id",
                            "Vidsrc.net" to "https://vidsrc.net/embed/$mediaType/$id",
                            "RiveStream" to "https://rivestream.com/embed/$mediaType/$id",
                            "Embed.su" to "https://embed.su/embed/$mediaType/$id",
                            "VidLink" to "https://vidlink.pro/$mediaType/$id",
                            "SuperEmbed" to "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1",
                            "2Embed" to "https://www.2embed.cc/embed$mediaType/$tmdbId",
                            "AutoEmbed" to "https://autoembed.co/$mediaType/tmdb/$tmdbId"
                        )
                        val fallbackKeys = mapOf(
                            "Vidsrc.to" to "p_vidsrccc", "Vidsrc.xyz" to "p_vidsrccc", "Vidsrc.net" to "p_vidsrccc",
                            "RiveStream" to "p_vidsrccc", "Embed.su" to "p_vidsrccc", "VidLink" to "p_vidlink",
                            "SuperEmbed" to "p_vidsrccc", "2Embed" to "p_2embed", "AutoEmbed" to "p_autoembed"
                        )
                        fallbacks.forEach { (n, u) -> 
                            if (Settings.isEnabled(fallbackKeys[n] ?: "p_vidsrccc")) addStream(n, u, "iframe", "Auto", null, fallbackKeys[n] ?: "p_vidsrccc")
                        }
                    })

                    // 6. Stremio Addon Bridges
                    tasks.add(async {
                        emitLog("Checking Stremio Bridges...")
                        val addons = mapOf("WebStreamr" to "https://webstreamr.hayd.uk", "Streamvix" to "https://streamvix.hayd.uk", "NoTorrent" to "https://addon-osvh.onrender.com")
                        val addonKeys = mapOf("WebStreamr" to "p_webstreamr", "Streamvix" to "p_streamvix", "NoTorrent" to "p_notorrent")
                        addons.forEach { (n, b) ->
                            if (Settings.isEnabled(addonKeys[n] ?: "")) {
                                try {
                                    val url = if (season == null) "$b/stream/movie/$id.json" else "$b/stream/tv/$id:$season:$episode.json"
                                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            val sArr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("streams") ?: JSONArray()
                                            for (i in 0 until sArr.length()) {
                                                val s = sArr.getJSONObject(i); val su = s.optString("url", "")
                                                if (su.isNotBlank()) addStream("$n ${s.optString("name")}", su, if (su.contains(".m3u8")) "hls" else "mp4", "Auto", null, addonKeys[n] ?: "")
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    })

                    tasks.awaitAll()
                }
                eventChannel?.close()

                // Save to Redis Cache (Background Job - persistent even if user leaves)
                if (Redis.isEnabled() && streamsList.isNotEmpty()) {
                    val streamsToCache = JSONArray()
                    synchronized(streamsList) {
                        streamsList.forEach { streamsToCache.put(it) }
                    }
                    if (streamsToCache.length() > 0) {
                        val success = Redis.set(cacheKey, streamsToCache.toString(), 21600)
                        if (success) println("Redis: Successfully cached \${streamsToCache.length()} streams for \${id}")
                        else println("Redis: Failed to write cache for \${id}")
                    }
                }
            }

            if (isStreaming) {
                call.respondTextWriter(ContentType.Application.Json) {
                    for (event in eventChannel!!) {
                        write(event.toString() + "\n")
                        flush()
                    }
                }
            } else {
                job.join()
                val streams = JSONArray()
                synchronized(streamsList) {
                    streamsList.forEach { streams.put(it) }
                }

                call.respondText(JSONObject().apply {
                    put("provider", "Antigravity Mega-Aggregator v4")
                    put("status", if (streams.length() > 0) "success" else "failed")
                    put("total", streams.length())
                    put("stream", streams)
                }.toString(2), ContentType.Application.Json)
            }
        }

    }
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
