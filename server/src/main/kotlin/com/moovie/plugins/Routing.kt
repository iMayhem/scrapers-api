package com.moovie.plugins

import com.moovie.Config
import com.moovie.PluginManager
import com.moovie.ScrapeService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val PING_TIMEOUT_MS = 3000L

// ── Stream URL Token Vault ─────────────────────────────────────────────
private val TOKEN_SECRET = System.getenv("TOKEN_SECRET") ?: "m00v1e_s3cr3t_k3y_ch4ng3_th1s_1n_pr0d"
private const val TOKEN_TTL_MS = 120_000L // 2 minutes

private fun deriveKey(): SecretKeySpec {
    val salt = "m00v1eSalt!9f2c".toByteArray()
    val spec = PBEKeySpec(TOKEN_SECRET.toCharArray(), salt, 65536, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
}

private val AES_KEY: SecretKeySpec by lazy { deriveKey() }

private fun encryptToken(url: String, headersJson: String, clientIp: String): String {
    val expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS
    val plaintext = "$url\u0000$headersJson\u0000$clientIp\u0000$expiresAt"
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, AES_KEY, GCMParameterSpec(128, iv))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
    val combined = iv + ciphertext
    return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
}

data class TokenData(val url: String, val headersJson: String, val clientIp: String, val expiresAt: Long)

private fun decryptToken(token: String): TokenData {
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

fun Application.configureRouting() {
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    routing {
        // Frontend (static site)
        val frontendDir = File(System.getenv("FRONTEND_DIR") ?: "frontend")
        if (frontendDir.exists()) {
            staticFiles("/", frontendDir, index = "index.html")
        }

        get("/api/status") {
            val providers = PluginManager.providers()
            call.respondText("Moovie Scraper API is Live! ${providers.size} providers loaded")
        }

        get("/api/search") {
            val query = call.request.queryParameters["query"]
            val type = call.request.queryParameters["type"] ?: "movie"
            if (query.isNullOrBlank()) {
                call.respondText("""{"error":"Missing query"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }
            val results = ScrapeService.search(query, type)
            call.respondText(
                JSONObject().apply { put("status", "success"); put("results", results) }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/home") {
            val type = call.request.queryParameters["type"] ?: "movie"
            val catalog = call.request.queryParameters["catalog"] ?: "top"
            val results = ScrapeService.home(type, catalog)
            call.respondText(
                JSONObject().apply { put("status", "success"); put("results", results) }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/details") {
            val url = call.request.queryParameters["url"]
            val provider = call.request.queryParameters["provider"]
            val type = call.request.queryParameters["type"] ?: "movie"
            if (url.isNullOrBlank()) {
                call.respondText("""{"error":"Missing url"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }
            val details = ScrapeService.details(provider, url, type)
            if (details == null) {
                call.respondText("""{"error":"Details not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(
                JSONObject().apply { put("status", "success"); put("details", details) }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/providers") {
            val arr = org.json.JSONArray()
            PluginManager.providers().forEach { p ->
                arr.put(JSONObject().apply {
                    put("name", p.name)
                    put("mainUrl", p.mainUrl)
                    put("lang", p.lang)
                    put("hasMainPage", p.hasMainPage)
                    put("hasDownloadSupport", p.hasDownloadSupport)
                    put("supportedTypes", org.json.JSONArray(p.supportedTypes.map { it.name }))
                })
            }
            call.respondText(
                JSONObject().apply { put("status", "success"); put("total", arr.length()); put("providers", arr) }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/config") {
            call.respondText(
                JSONObject().apply {
                    put("config", Config.toJson(Config.load()))
                    put("providers", org.json.JSONArray(PluginManager.providers().map { it.name }))
                    put("preferences", JSONObject().apply {
                        put("prioritizeBy", "latency")
                        put("maxPreferredSizeGb", 3.0)
                    })
                }.toString(),
                ContentType.Application.Json
            )
        }

        post("/api/reload") {
            runBlocking {
                withContext(Dispatchers.IO) { PluginManager.reload() }
            }
            call.respondText(
                JSONObject().apply { put("status", "success"); put("providers", PluginManager.providers().size) }.toString(),
                ContentType.Application.Json
            )
        }

        get("/api/scrape") {
            val title = call.request.queryParameters["title"]
            val year = call.request.queryParameters["year"]
            val season = call.request.queryParameters["season"]?.toIntOrNull()
            val episode = call.request.queryParameters["episode"]?.toIntOrNull()
            val type = call.request.queryParameters["type"] ?: "movie"
            val isStreaming = call.request.queryParameters["stream"] == "true"

            if (title.isNullOrBlank()) {
                call.respondText(
                    """{"error":"Missing title"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@get
            }

            println("[SCRAPE] title=$title, year=$year, type=$type, s=$season, e=$episode, stream=$isStreaming")

            if (isStreaming) {
                call.respondTextWriter(ContentType.Application.Json) {
                    ScrapeService.scrape(title, year, season, episode, type, isStreaming = true) { stream ->
                        write(stream.toString() + "\n")
                        flush()
                    }
                }
            } else {
                val streams = ScrapeService.scrape(title, year, season, episode, type, isStreaming = false) {}
                call.respondText(
                    JSONObject()
                        .apply {
                            put("provider", "Moovie Mega-Scraper (Cloudstream plugins)")
                            put("status", if (streams.isNotEmpty()) "success" else "failed")
                            put("total", streams.size)
                            put("stream", org.json.JSONArray(streams))
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
                val clientIp = call.request.headers["CF-Connecting-IP"]
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
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
                } catch (e: Exception) {
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

                val ktorContentType = try {
                    io.ktor.http.ContentType.parse(upstreamCT)
                } catch (e: Exception) {
                    io.ktor.http.ContentType.Video.MP4
                }

                if (response.code == 206 || response.code == 200) {
                    call.response.status(HttpStatusCode.fromValue(response.code))
                    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
                    call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, HEAD, OPTIONS")
                    call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "*")
                    call.response.headers.append(HttpHeaders.AccessControlExposeHeaders, "Content-Range, Content-Length, Content-Type")
                    val cr = response.header("Content-Range")
                    if (cr != null) call.response.headers.append(HttpHeaders.ContentRange, cr)
                    val cl = response.header("Content-Length")
                    if (cl != null) call.response.headers.append(HttpHeaders.ContentLength, cl)

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