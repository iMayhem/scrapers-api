package com.moovie.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val PING_TIMEOUT_MS = 3000L

/** Measure response latency (ms) for direct stream URLs. Returns null on failure or timeout. */
private fun measurePing(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>?,
): Long? {
  val pingClient =
          client.newBuilder()
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
        val getReq =
                Request.Builder()
                        .url(url)
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
  val client =
          OkHttpClient.Builder()
                  .connectTimeout(20, TimeUnit.SECONDS)
                  .readTimeout(20, TimeUnit.SECONDS)
                  .followRedirects(true)
                  .cookieJar(
                          object : CookieJar {
                            private val storage = mutableMapOf<String, List<Cookie>>()
                            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                              storage[url.host] = cookies
                            }
                            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                              return storage[url.host] ?: listOf()
                            }
                          }
                  )
                  .build()

  routing {
    get("/") { call.respondText("Moovie Mega-Scraper v4 (40+ Sources) is Live!") }

    get("/api/search") {
      val query = call.request.queryParameters["query"]
      val type = call.request.queryParameters["type"] ?: "movie"
      if (query.isNullOrBlank()) {
        call.respondText("""{"error":"Missing query"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        return@get
      }
      val isAnime = type == "anime"
      val results = if (isAnime) CineStreamExtractors.searchKitsu(query) else CineStreamExtractors.searchCinemeta(query, if (type == "show") "series" else "movie")
      val response = JSONArray()
      results.forEach { item ->
        val obj = JSONObject()
        val imdbId = item.optString("imdb_id", "")
        val id = if (imdbId.isNotBlank()) imdbId else item.optString("id", "")
        obj.put("id", id)
        obj.put("title", item.optString("name"))
        obj.put("poster", item.optString("poster"))
        obj.put("year", item.optString("year"))
        obj.put("type", if (type == "show") "show" else if (type == "anime") "anime" else "movie")
        response.put(obj)
      }
      call.respondText(JSONObject().apply { put("status", "success"); put("results", response) }.toString(), ContentType.Application.Json)
    }

    get("/api/home") {
      val type = call.request.queryParameters["type"] ?: "movie"
      val catalogId = call.request.queryParameters["catalog"] ?: "top"
      
      val results = when(type) {
          "anime" -> CineStreamExtractors.getKitsuHome()
          "show" -> CineStreamExtractors.getCinemetaHome("series", catalogId)
          else -> CineStreamExtractors.getCinemetaHome("movie", catalogId)
      }

      val response = JSONArray()
      results.forEach { item ->
        val obj = JSONObject()
        val imdbId = item.optString("imdb_id", "")
        val id = if (imdbId.isNotBlank()) imdbId else item.optString("id", "")
        obj.put("id", id)
        obj.put("title", item.optString("name"))
        obj.put("poster", item.optString("poster"))
        obj.put("year", item.optString("year"))
        obj.put("type", if (type == "show") "show" else if (type == "anime") "anime" else "movie")
        response.put(obj)
      }
      call.respondText(JSONObject().apply { put("status", "success"); put("results", response) }.toString(), ContentType.Application.Json)
    }

    get("/api/details") {
      val id = call.request.queryParameters["id"]
      val type = call.request.queryParameters["type"] ?: "movie"
      if (id.isNullOrBlank()) {
        call.respondText("""{"error":"Missing id"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        return@get
      }
      val details = when {
          id.startsWith("kitsu:") -> CineStreamExtractors.getKitsuDetails(id)
          id.startsWith("tt") -> CineStreamExtractors.getCinemetaDetails(if (type == "show") "series" else "movie", id)
          else -> CineStreamExtractors.getMovieboxDetail(id)
      }
      if (details == null) {
        call.respondText("""{"error":"Details not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        return@get
      }
      call.respondText(JSONObject().apply { put("status", "success"); put("details", details) }.toString(), ContentType.Application.Json)
    }

    get("/api/config") {
      val configUrl =
              System.getenv("CONFIG_URL")
                      ?: "https://gist.githubusercontent.com/iMayhem/abbb593bdcd0bfc3d54a6284e81cc880/raw/scrapers.json"
      try {
        client.newCall(Request.Builder().url("$configUrl?t=${System.currentTimeMillis()}").build())
                .execute()
                .use { resp ->
                  if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: "{}"
                    call.respondText(body, ContentType.Application.Json)
                  } else {
                    call.respondText(
                            """{"providers":[],"preferences":{"prioritizeBy":"latency","maxPreferredSizeGb":3.0}}""",
                            ContentType.Application.Json
                    )
                  }
                }
      } catch (e: Exception) {
        call.respondText(
                """{"providers":[],"preferences":{"prioritizeBy":"latency","maxPreferredSizeGb":3.0}}""",
                ContentType.Application.Json
        )
      }
    }

    get("/api/scrape") {
      val tmdbId = call.request.queryParameters["tmdbId"]
      val season = call.request.queryParameters["season"]
      val episode = call.request.queryParameters["episode"]
      val isStreaming = call.request.queryParameters["stream"] == "true"
      var mediaTitle = call.request.queryParameters["title"]
      val year = call.request.queryParameters["year"]

      if (mediaTitle.isNullOrBlank()) {
        call.respondText(
                """{"error":"Missing title"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
        )
        return@get
      }

      val id = tmdbId ?: mediaTitle // Use tmdbId as id if available, otherwise title

      val streamsList = Collections.synchronizedList(mutableListOf<JSONObject>())
      val eventChannel =
              if (isStreaming)
                      kotlinx.coroutines.channels.Channel<JSONObject>(
                              kotlinx.coroutines.channels.Channel.UNLIMITED
                      )
              else null

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

      // Cache key: always use tmdbId/title for consistency. Add season/episode for TV series.
      val cacheKey =
              if (season == null) {
                "scrape:movie:$id"
              } else {
                "scrape:tv:$id:s${season}:e${episode}"
              }
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

      if (cachedData != null && cachedData.length() > 0) {
        println("Redis: Cache hit for $tmdbId! Serving ${cachedData.length()} streams.")
        emitLog("Cache hit! Serving ${cachedData.length()} streams from Redis.")
        if (isStreaming) {
          call.respondTextWriter(ContentType.Application.Json) {
            for (i in 0 until cachedData.length()) {
              val streamObj = cachedData.getJSONObject(i)
              // Re-emit each stream event
              write(streamObj.apply { put("msgType", "stream") }.toString() + "\n")
              flush()
              delay(10) // Small delay to ensure client can process
            }
          }
        } else {
          call.respondText(
                  JSONObject()
                          .apply {
                            put("provider", "Antigravity Mega-Aggregator v4")
                            put("status", "success")
                            put("total", cachedData.length())
                            put("stream", cachedData)
                            put("cached", true)
                          }
                          .toString(2),
                  ContentType.Application.Json
          )
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
        val latencyMs =
                if (type in listOf("hls", "mp4", "dash")) {
                  withContext(Dispatchers.IO) { measurePing(client, url, headers) }
                } else null
        val fileSizeGb = parseFileSizeGb(server)
        val obj =
                createStreamObj(
                        server,
                        url,
                        type,
                        quality,
                        headers,
                        latencyMs,
                        providerKey,
                        fileSizeGb
                )
        streamsList.add(obj)
        emit("stream", obj)
      }

      val job =
              launch(Dispatchers.IO) {
                coroutineScope {
                  val tasks = mutableListOf<Deferred<Unit>>()

                  // MovieBox (Only active server)
                  tasks.add(
                          async {
                            emitLog("Checking MovieBox...")
                            try {
                              // Using the updated MovieBox from CineStreamExtractors
                              CineStreamExtractors.invokeMoviebox(
                                      title = mediaTitle,
                                      year = year,
                                      season = season?.toIntOrNull(),
                                      episode = episode?.toIntOrNull(),
                                      subtitleCallback = { _ ->
                                      }, // We can handle subtitles if needed later
                                      callback = { link ->
                                        val st =
                                                when (link.type) {
                                                  ExtractorLinkType.M3U8 -> "hls"
                                                  ExtractorLinkType.DASH -> "dash"
                                                  else ->
                                                          if (link.url.contains(".m3u8")) "hls"
                                                          else "mp4"
                                                }
                                        launch {
                                          addStream(
                                                  "MovieBox [${link.name}]",
                                                  link.url,
                                                  st,
                                                  link.quality.toString(),
                                                  link.headers,
                                                  "p_moviebox"
                                          )
                                        }
                                      }
                              )
                            } catch (e: Exception) {
                              emitLog("MovieBox error: ${e.message}")
                            }
                          }
                  )

                  tasks.awaitAll()
                }
                eventChannel?.close()

                // Save to Redis Cache (Background Job - persistent even if user leaves)
                if (Redis.isEnabled() && streamsList.isNotEmpty()) {
                  val streamsToCache = JSONArray()
                  synchronized(streamsList) { streamsList.forEach { streamsToCache.put(it) } }
                  if (streamsToCache.length() > 0) {
                    val success = Redis.set(cacheKey, streamsToCache.toString(), 21600)
                    if (success)
                            println(
                                    "Redis: Successfully cached ${streamsToCache.length()} streams for $id"
                            )
                    else println("Redis: Failed to write cache for $id")
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
        synchronized(streamsList) { streamsList.forEach { streams.put(it) } }

        call.respondText(
                JSONObject()
                        .apply {
                          put("provider", "Antigravity Mega-Aggregator v4")
                          put("status", if (streams.length() > 0) "success" else "failed")
                          put("total", streams.length())
                          put("stream", streams)
                        }
                        .toString(2),
                ContentType.Application.Json
        )
      }
    }
  }
}

fun cinemaOSDecryptResponse(enc: String?): String? {
  if (enc.isNullOrBlank()) return null
  try {
    val j = JSONObject(enc)
    val pw = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456"
    val iv = hexToBytes(j.getString("cin"))
    val tag = hexToBytes(j.getString("mao"))
    val e = hexToBytes(j.getString("encrypted"))
    val salt = hexToBytes(j.getString("salt"))
    val k =
            SecretKeySpec(
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 100000, 256))
                            .encoded,
                    "AES"
            )
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
    return String(c.doFinal(e + tag), StandardCharsets.UTF_8)
  } catch (_: Exception) {
    return null
  }
}

private fun hmac256(data: String, key: String): String {
  val m = Mac.getInstance("HmacSHA256")
  m.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
  return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
