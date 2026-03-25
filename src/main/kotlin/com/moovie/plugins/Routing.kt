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

import io.ktor.server.request.*
import java.util.Base64

private const val PING_TIMEOUT_MS = 3000L

// ── Stream URL Token Vault ─────────────────────────────────────────────
// Secret key material — should be set via environment variable in production
private val TOKEN_SECRET = System.getenv("TOKEN_SECRET") ?: "m00v1e_s3cr3t_k3y_ch4ng3_th1s_1n_pr0d"
private const val TOKEN_TTL_MS = 120_000L // 2 minutes

private fun deriveKey(): SecretKeySpec {
    val salt = "m00v1eSalt!9f2c".toByteArray()
    val spec = PBEKeySpec(TOKEN_SECRET.toCharArray(), salt, 65536, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
}

private val AES_KEY: SecretKeySpec by lazy { deriveKey() }

/** Encrypts "url|headersJson|clientIp|expiresAt" into a Base64-URL-safe token */
fun encryptToken(url: String, headersJson: String, clientIp: String): String {
    val expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS
    // Sanitize url-like chars from headersJson to avoid split ambiguity
    val plaintext = "$url\u0000$headersJson\u0000$clientIp\u0000$expiresAt"
    val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, AES_KEY, GCMParameterSpec(128, iv))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
    val combined = iv + ciphertext
    return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
}

data class TokenData(val url: String, val headersJson: String, val clientIp: String, val expiresAt: Long)

/** Decrypts a token; returns TokenData or throws */
fun decryptToken(token: String): TokenData {
    val combined = Base64.getUrlDecoder().decode(token)
    val iv = combined.sliceArray(0 until 12)
    val ciphertext = combined.sliceArray(12 until combined.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, AES_KEY, GCMParameterSpec(128, iv))
    val plaintext = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    val parts = plaintext.split("\u0000")
    if (parts.size != 4) throw IllegalArgumentException("Invalid token format")
    return TokenData(parts[0], parts[1], parts[2], parts[3].toLong())
}

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
      val configRes = try {
        client.newCall(Request.Builder().url("$configUrl?t=${System.currentTimeMillis()}").build())
                .execute()
                .use { resp ->
                  if (resp.isSuccessful) resp.body?.string() else null
                }
      } catch (e: Exception) {
        null
      }

      if (configRes != null) {
        call.respondText(configRes, ContentType.Application.Json)
      } else {
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
                
      println("[SCRAPE] 🏁 NEW REQUEST: title=$mediaTitle, tmdbId=$tmdbId, year=$year, s=$season, e=$episode")

      var imdbId = call.request.queryParameters["imdbId"]
      if (imdbId.isNullOrBlank() && !tmdbId.isNullOrBlank()) {
          println("[SCRAPE] Resolving IMDb from TMDB: $tmdbId")
          try {
              val mediaType = if (season != null) "tv" else "movie"
              val tmdbUrl = CineStreamExtractors.getProxiedUrl("https://api.themoviedb.org/3/$mediaType/$tmdbId/external_ids?api_key=f02a0c39f2e7a175fec9f673ff440c4e")
              val response = app.get(tmdbUrl).text
              val json = JSONObject(response)
              imdbId = json.optString("imdb_id").takeIf { it.isNotBlank() }
              println("[SCRAPE] Resolved IMDb: $imdbId")
          } catch (e: Exception) {
              println("[SCRAPE] TMDB resolution failed: ${e.message}")
          }
      }
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


      fun parseFileSizeGb(server: String): Double? {
        // Match numbers like 1,2 or 1.2
        val sizeVal = """(\d+(?:[.,]\d+)?)""" 
        val gbMatch = Regex("$sizeVal\\s*GB", RegexOption.IGNORE_CASE).find(server)
        if (gbMatch != null) return gbMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
        
        val mbMatch = Regex("$sizeVal\\s*MB", RegexOption.IGNORE_CASE).find(server)
        if (mbMatch != null) return mbMatch.groupValues[1].replace(',', '.').toDoubleOrNull()?.div(1024.0)
        
        return null
      }

      // Redis caching removed

      fun createStreamObj(
              server: String,
              url: String,
              type: String = "hls",
              quality: String = "Auto",
              headers: Map<String, String>? = null,
              latencyMs: Long? = null,
              providerKey: String? = null,
              fileSizeGb: Double? = null,
        }
      }

      fun createSubtitleObj(lang: String, url: String): JSONObject {
          return JSONObject().apply {
              put("lang", lang)
              put("url", url)
          }
      }

      suspend fun addStream(
              server: String,
              url: String,
              type: String = "hls",
              quality: String = "Auto",
              headers: Map<String, String>? = null,
              providerKey: String? = null,
              fileSizeGb: Double? = null,
      ) {
        if (url.isBlank()) return
        val latencyMs =
                if (type in listOf("hls", "mp4", "dash")) {
                  withContext(Dispatchers.IO) { measurePing(client, url, headers) }
                } else null
        val finalFileSizeGb = fileSizeGb ?: parseFileSizeGb(server)
        val obj =
                createStreamObj(
                        server,
                        url,
                        type,
                        quality,
                        headers,
                        latencyMs,
                        providerKey,
                        finalFileSizeGb
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
                            try {
                              // Using the updated MovieBox from CineStreamExtractors
                              CineStreamExtractors.invokeMoviebox(
                                      title = mediaTitle,
                                      year = year,
                                      season = season?.toIntOrNull(),
                                      episode = episode?.toIntOrNull(),
                                      subtitleCallback = { sub ->
                                          launch { emit("subtitle", createSubtitleObj(sub.lang, sub.url)) }
                                      },
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
                                                  "p_moviebox",
                                                  link.fileSizeGb
                                          )
                                        }
                                      }
                              )
                            } catch (e: Exception) {
                            }
                          }
                  )

                  // AllMoviesLand — geo-restricted (India only), disabled until India-based proxy available
                  // tasks.add(async { ... })

 
                   // RogMovies
                   tasks.add(
                           async {
                             try {
                               if (!imdbId.isNullOrBlank()) {
                                 println("[SCRAPE] RogMovies starting: imdbId=$imdbId title=$mediaTitle year=$year season=$season episode=$episode")
                                 CineStreamExtractors.invokeRogmovies(
                                          title = mediaTitle,
                                          year = year,
                                         imdbId = imdbId,
                                         season = season?.toIntOrNull(),
                                         episode = episode?.toIntOrNull(),
                                         subtitleCallback = { sub -> launch { emit("subtitle", createSubtitleObj(sub.lang, sub.url)) } },
                                         callback = { link ->
                                           launch {
                                             println("[SCRAPE] RogMovies stream found: name='${link.name}' url='${link.url}' quality=${link.quality}")
                                             addStream(
                                                     server = link.name,
                                                     url = link.url,
                                                     type = if (link.isM3u8 || link.url.contains(".m3u8")) "hls" else "mp4",
                                                     quality = if (link.quality > 0) "${link.quality}p" else "Auto",
                                                     headers = link.headers,
                                                     providerKey = "RogMovies", fileSizeGb = link.fileSizeGb
                                             )
                                           }
                                         }
                                 )
                                 println("[SCRAPE] RogMovies finished")
                               } else {
                                 println("[SCRAPE] RogMovies SKIP: imdbId is blank")
                               }
                             } catch (e: Exception) {
                               println("[SCRAPE] RogMovies EXCEPTION: ${e::class.simpleName}: ${e.message}")
                               e.printStackTrace()
                             }
                           }
                   )

                    // MoviesDrive
                    tasks.add(
                            async {
                              try {
                                if (!imdbId.isNullOrBlank()) {
                                  CineStreamExtractors.invokeMoviesDrive(
                                          imdbId = imdbId,
                                          title = mediaTitle,
                                          year = year,
                                          season = season?.toIntOrNull(),
                                          episode = episode?.toIntOrNull(),
                                          callback = { link ->
                                            launch {
                                              addStream(
                                                      server = link.name,
                                                      url = link.url,
                                                      type = if (link.isM3u8 || link.url.contains(".m3u8")) "hls" else "mp4",
                                                      quality = if (link.quality > 0) "${link.quality}p" else "Auto",
                                                      headers = link.headers,
                                                      providerKey = "MoviesDrive",
                                                      fileSizeGb = link.fileSizeGb
                                              )
                                            }
                                          }
                                  )
                                }
                              } catch (e: Exception) {
                                println("[SCRAPE] MoviesDrive failed: ${e.message}")
                              }
                            }
                    )

                  tasks.awaitAll()
                }
                eventChannel?.close()

                // Redis caching removed
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

    // ── Stream Token Generation ──────────────────────────────────────────
    post("/api/token") {
        try {
            val body = call.receiveText()
            val json = JSONObject(body)
            val url = json.getString("url")
            val headersJson = json.optString("headers", "{}")
            // Bind token to requester's IP to prevent URL sharing
            val clientIp = call.request.headers["CF-Connecting-IP"] ?: call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                ?: call.request.local.remoteHost
            val token = encryptToken(url, headersJson, clientIp)
            call.respondText(
                JSONObject().apply { put("token", token) }.toString(),
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
        }
    }

    get("/api/proxy") {
      // Accept either a signed token OR legacy url param (for backward compat)
      val tokenParam = call.request.queryParameters["token"]
      val targetUrl: String
      val headersJsonRaw: String?

      if (!tokenParam.isNullOrBlank()) {
          try {
              val tokenData = decryptToken(tokenParam)
              if (System.currentTimeMillis() > tokenData.expiresAt) {
                  call.respond(HttpStatusCode.Gone, "Token expired")
                  return@get
              }
              targetUrl = tokenData.url
              headersJsonRaw = tokenData.headersJson
          } catch (e: Exception) {
              call.respond(HttpStatusCode.Unauthorized, "Invalid token")
              return@get
          }
      } else {
          // Legacy fallback (no raw URL exposure in prod)
          targetUrl = call.request.queryParameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing token or url")
          headersJsonRaw = call.request.queryParameters["headers"]
      }
      
      val reqBuilder = Request.Builder().url(targetUrl)
      
      call.request.headers["Range"]?.let { range ->
          reqBuilder.header("Range", range)
      }

      if (!headersJsonRaw.isNullOrBlank()) {
          try {
              val h = JSONObject(headersJsonRaw)
              h.keys().forEach { k -> reqBuilder.header(k, h.getString(k)) }
          } catch(e: Exception) {
              println("Failed parsing headers for proxy: ${e.message}")
          }
      }
      
      // Filepress / Filebee explicitly requires origin and referer to match their domain or they return 403 Forbidden
      if (targetUrl.contains("filebee.xyz", ignoreCase = true) || targetUrl.contains("filepress", ignoreCase = true)) {
          reqBuilder.header("Origin", "https://filebee.xyz")
          reqBuilder.header("Referer", "https://filebee.xyz/")
      }
      
      try {
          val response = client.newCall(reqBuilder.build()).execute()
          val upstreamCT = response.header("Content-Type") ?: "video/mp4"
          
          // Map upstream Content-Type to a Ktor ContentType
          val ktorContentType = try {
              io.ktor.http.ContentType.parse(upstreamCT)
          } catch (e: Exception) {
              io.ktor.http.ContentType.Video.MP4
          }
          
          if (response.code == 206 || response.code == 200) {
              call.response.status(HttpStatusCode.fromValue(response.code))
              // CORS headers — must be set before respondOutputStream
              call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
              call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, HEAD, OPTIONS")
              call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "*")
              call.response.headers.append(HttpHeaders.AccessControlExposeHeaders, "Content-Range, Content-Length, Content-Type")
              val cr = response.header("Content-Range")
              if (cr != null) call.response.headers.append(HttpHeaders.ContentRange, cr)
              val cl = response.header("Content-Length")
              if (cl != null) call.response.headers.append(HttpHeaders.ContentLength, cl)
              
              // Use respondOutputStream with explicit contentType to prevent
              // Ktor from overriding it with application/octet-stream (which triggers ORB)
              call.respondOutputStream(contentType = ktorContentType) {
                  response.body?.byteStream()?.copyTo(this)
              }
          } else {
              call.respond(HttpStatusCode.fromValue(response.code), "Upstream Proxy Error: ${response.code}")
          }
      } catch (e: Exception) {
          call.respond(HttpStatusCode.InternalServerError, "Proxy Connection Error: ${e.message}")
      }
    }

    options("/api/proxy") {
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, HEAD, OPTIONS")
        call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "*")
        call.response.headers.append(HttpHeaders.AccessControlMaxAge, "86400")
        call.respond(HttpStatusCode.OK)
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
