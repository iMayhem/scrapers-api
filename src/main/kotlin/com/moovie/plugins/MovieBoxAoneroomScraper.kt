package com.moovie.plugins

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MovieBox (aoneroom) scraper – ported from moovie-phisher/MovieBoxProvider.
 *
 * Auth flow:
 *  1. generateXClientToken()  → "timestamp,md5(reverse(timestamp))"
 *  2. generateXTrSignature()  → "timestamp|2|hmacMd5(canonicalString, secret)"
 *
 * The two secret keys are double-base64-encoded in the source (first decode yields a
 * base64 string, the second decode yields the raw 30-byte HMAC key).
 */

private const val AONEROOM_BASE = "https://api3.aoneroom.com"

private val SECRET_DEFAULT: ByteArray = run {
    val inner = String(Base64.getDecoder().decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="))
    Base64.getDecoder().decode(inner)
}
private val SECRET_ALT: ByteArray = run {
    val inner = String(Base64.getDecoder().decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="))
    Base64.getDecoder().decode(inner)
}

private val X_CLIENT_INFO = """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"da2b99c821e6ea023e4be55b54d5f7d8","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"sdk_gphone64_x86_64","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}"""
private val AONEROOM_UA = "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)"

private fun md5Hex(input: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(input)
        .joinToString("") { "%02x".format(it) }

private fun generateXClientToken(): String {
    val ts = System.currentTimeMillis().toString()
    val reversed = ts.reversed()
    val hash = md5Hex(reversed.toByteArray())
    return "$ts,$hash"
}

private fun buildCanonicalString(
    method: String,
    accept: String?,
    contentType: String?,
    url: String,
    body: String?,
    timestamp: Long,
): String {
    val uri = URI.create(url)
    val path = uri.rawPath ?: ""
    val rawQuery = uri.rawQuery ?: ""

    // Sort query params alphabetically (same as Kotlin plugin)
    val canonicalUrl = if (rawQuery.isNotEmpty()) {
        val sorted = rawQuery.split("&").sorted().joinToString("&")
        "$path?$sorted"
    } else path

    val bodyBytes = body?.toByteArray(Charsets.UTF_8)
    val bodyHash = if (bodyBytes != null) {
        val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
        md5Hex(trimmed)
    } else ""
    val bodyLength = bodyBytes?.size?.toString() ?: ""

    return "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
}

private fun generateXTrSignature(
    method: String,
    accept: String?,
    contentType: String?,
    url: String,
    body: String? = null,
    useAlt: Boolean = false,
    ts: Long = System.currentTimeMillis(),
): String {
    val canonical = buildCanonicalString(method, accept, contentType, url, body, ts)
    val secret = if (useAlt) SECRET_ALT else SECRET_DEFAULT
    val mac = Mac.getInstance("HmacMD5")
    mac.init(SecretKeySpec(secret, "HmacMD5"))
    val sig = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    return "$ts|2|$sig"
}

private fun getHeaders(method: String, url: String, body: String? = null, accept: String = "application/json", ct: String = "application/json"): Map<String, String> {
    val xct = generateXClientToken()
    val xtr = if (method == "POST")
        generateXTrSignature("POST", accept, "application/json; charset=utf-8", url, body)
    else
        generateXTrSignature("GET", accept, ct, url)
    return mapOf(
        "User-Agent" to AONEROOM_UA,
        "Accept" to accept,
        "Content-Type" to ct,
        "Connection" to "keep-alive",
        "X-Client-Token" to xct,
        "X-Tr-Signature" to xtr,
        "X-Client-Info" to X_CLIENT_INFO,
        "X-Client-Status" to "0",
    )
}

/**
 * Search MovieBox by title, return best matching subjectId (or null).
 */
private fun searchMovieBox(title: String, year: Int?, client: OkHttpClient): String? {
    val url = "$AONEROOM_BASE/wefeed-mobile-bff/subject-api/search/v2"
    val jsonBody = """{"page": 1, "perPage": 10, "keyword": "${title.replace("\"", "\\\"")}"}"""
    val headers = getHeaders("POST", url, jsonBody)

    return try {
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> req.header(k, v) }

        val respStr = client.newCall(req.build()).execute().use { it.body?.string() ?: return null }
        val root = JSONObject(respStr)
        val results = root.optJSONObject("data")?.optJSONArray("results") ?: return null

        val candidates = mutableListOf<Pair<String, String>>() // (subjectId, title)
        for (i in 0 until results.length()) {
            val bucket = results.getJSONObject(i)
            val subjects = bucket.optJSONArray("subjects") ?: continue
            for (j in 0 until subjects.length()) {
                val s = subjects.getJSONObject(j)
                val sid = s.optString("subjectId", "") 
                val t = s.optString("title", "").substringBefore("[").trim()
                if (sid.isNotEmpty()) candidates.add(sid to t)
            }
        }

        if (candidates.isEmpty()) return null

        // Prefer exact title match first, then year-match, finally fallback to first result
        val normTitle = title.trim().lowercase()
        val exactMatch = candidates.firstOrNull { (_, t) -> t.trim().lowercase() == normTitle }
        if (exactMatch != null) return exactMatch.first

        return candidates.first().first
    } catch (_: Exception) {
        null
    }
}

/**
 * Fetch play-info from MovieBox and return stream objects ready to add.
 * Each element: Triple(serverName, url, type["hls"|"mp4"|"dash"])
 */
private fun fetchMovieBoxPlayInfo(
    subjectId: String,
    season: Int,
    episode: Int,
    client: OkHttpClient,
): List<Triple<String, String, String>> {
    val url = "$AONEROOM_BASE/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"
    val headers = getHeaders("GET", url)

    return try {
        val req = Request.Builder().url(url)
        headers.forEach { (k, v) -> req.header(k, v) }

        val respStr = client.newCall(req.build()).execute().use { it.body?.string() ?: return emptyList() }
        val root = JSONObject(respStr)
        val playData = root.optJSONObject("data") ?: return emptyList()
        val streams = playData.optJSONArray("streams") ?: return emptyList()

        val results = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val streamUrl = s.optString("url", "")
            if (streamUrl.isEmpty()) continue
            val format = s.optString("format", "")
            val resolutions = s.optString("resolutions", "Auto")
            val quality = resolutions.ifEmpty { "Auto" }

            val type = when {
                streamUrl.contains(".mpd", ignoreCase = true) -> "dash"
                format.equals("HLS", ignoreCase = true) ||
                        streamUrl.contains(".m3u8", ignoreCase = true) -> "hls"
                streamUrl.contains(".mp4", ignoreCase = true) ||
                        streamUrl.contains(".mkv", ignoreCase = true) -> "mp4"
                else -> "hls"
            }

            val serverName = "MovieBox [$quality]"
            results.add(Triple(serverName, streamUrl, type))
        }
        results
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Main entry point. Searches MovieBox by title+year, then fetches stream URLs.
 * Calls [addStream] for each valid stream found.
 */
suspend fun invokeMovieBoxAoneroom(
    title: String?,
    season: Int?,
    episode: Int?,
    addStream: suspend (server: String, url: String, type: String, quality: String, headers: Map<String, String>?, providerKey: String?) -> Unit,
    client: OkHttpClient,
    year: Int? = null,
) {
    if (title.isNullOrBlank()) return

    val subjectId = searchMovieBox(title, year, client) ?: return
    val se = season ?: 0
    val ep = episode ?: 0

    val streams = fetchMovieBoxPlayInfo(subjectId, se, ep, client)
    for ((server, url, type) in streams) {
        addStream(server, url, type, "Auto", mapOf("Referer" to AONEROOM_BASE), "p_moviebox_aoneroom")
    }
}
