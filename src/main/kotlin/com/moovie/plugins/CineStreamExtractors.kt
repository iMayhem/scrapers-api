package com.moovie.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object CineStreamExtractors {

    fun getProxiedUrl(url: String): String {
        val proxyBase = System.getenv("SCRAPER_PROXY")?.trim() ?: "https://moovie-proxy.sujeetunbeatable.workers.dev"
        val cleanBase = proxyBase.removeSuffix("/")
        val sep = if (cleanBase.contains("?")) "&" else "?"
        return "$cleanBase${sep}url=${URLEncoder.encode(url, "UTF-8")}"
    }

    private fun getProxyBase(): String? = System.getenv("SCRAPER_PROXY")


    private fun unwrapData(json: JSONObject): JSONObject {
        val data = json.optJSONObject("data") ?: return json
        return data.optJSONObject("data") ?: data
    }

    suspend fun invokeMoviebox(
        title: String? = null,
        year: String? = null,
        season: Int? = null,
        episode: Int? = null,
        logCallback: (String) -> Unit = {},
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val HOST = "h5.aoneroom.com"
        val BASE_URL = "https://$HOST"
        val SEASON_SUFFIX_REGEX = """\sS\d+(?:-S?\d+)*$""".toRegex(RegexOption.IGNORE_CASE)

        val baseHeaders = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to "$BASE_URL/",
            "Origin" to BASE_URL,
            "Connection" to "keep-alive",
            "X-Forwarded-For" to "122.161.49.200"
        )




        
        // 1. App Init (Optional but mimics client)
        try {
            val initUrl = getProxiedUrl("$BASE_URL/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox")
            app.get(initUrl, headers = baseHeaders)
        } catch (e: Exception) {
        }

        // 2. Search
        val subjectType = if (season != null) 2 else 1
        val searchKeyword = title ?: ""

        val searchResponse = try {
            val searchUrl = "$BASE_URL/wefeed-h5-bff/web/subject/search"
            app.post(
                searchUrl,
                headers = baseHeaders,
                json = mapOf(
                    "keyword" to searchKeyword,
                    "page" to 1,
                    "perPage" to 24,
                    "subjectType" to subjectType
                )
            ).text
        } catch (e: Exception) {
            return
        }

        val searchObj = try { JSONObject(searchResponse) } catch (e: Exception) { 
            val preview = searchResponse.take(150).replace("\n", " ")
            return 
        }
        val items = unwrapData(searchObj).optJSONArray("items") ?: run {
            return
        }


        // 3. Match Matching
        val normalizedTitle = (title ?: "").trim()
        val requestedYear = year?.take(4)?.toIntOrNull()
        val escapedTitle = Regex.escape(normalizedTitle)
        val titleMatchRegex = """^$escapedTitle(?:\s*[\(\[]\d{4}[\)\]])?(?:\s*\[([^\]]+)\])?$""".toRegex(RegexOption.IGNORE_CASE)
        data class MatchCandidate(
            val id: String,
            val language: String,
            val titleScore: Int,
            val totalScore: Int,
            val releaseYear: Int?
        )
        val matches = mutableListOf<MatchCandidate>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId")
            val rawTitle = item.optString("title", "")
            val cleanTitle = rawTitle.replace(SEASON_SUFFIX_REGEX, "").trim()
            

            val releaseYear = item.optString("releaseDate")
                .takeIf { it.length >= 4 }
                ?.substring(0, 4)
                ?.toIntOrNull()

            val matchResult = titleMatchRegex.find(cleanTitle)
            val titleScore = when {
                matchResult != null || cleanTitle.equals(normalizedTitle, ignoreCase = true) -> 300
                cleanTitle.startsWith(normalizedTitle, ignoreCase = true) -> 200
                cleanTitle.contains(normalizedTitle, ignoreCase = true) -> 100
                else -> 0
            }
            if (titleScore > 0) {
                val yearScore = when {
                    requestedYear == null -> 0
                    releaseYear == null -> -25
                    releaseYear == requestedYear -> 1000
                    kotlin.math.abs(releaseYear - requestedYear) == 1 -> 100
                    else -> -500
                }
                val lang = matchResult?.groups?.get(1)?.value ?: "Original"
                matches.add(
                    MatchCandidate(
                        id = id,
                        language = lang,
                        titleScore = titleScore,
                        totalScore = titleScore + yearScore,
                        releaseYear = releaseYear
                    )
                )
            }
        }

        if (matches.isEmpty()) {
            return
        }

        val selectedMatches = matches
            .let { candidates ->
                val exactTitleExactYear = candidates.filter {
                    requestedYear != null &&
                        it.releaseYear == requestedYear &&
                        it.titleScore >= 300
                }
                when {
                    exactTitleExactYear.isNotEmpty() -> exactTitleExactYear
                    requestedYear != null -> {
                        val exactYear = candidates.filter { it.releaseYear == requestedYear }
                        if (exactYear.isNotEmpty()) exactYear else candidates
                    }
                    else -> candidates
                }
            }
            .sortedByDescending { it.totalScore }
            .distinctBy { it.id }

        // 4. Detail & Link Extraction
        selectedMatches.forEach { candidate ->
            val subjectId = candidate.id
            val language = candidate.language
            val detailResponse = try {
                val detailUrl = "$BASE_URL/wefeed-h5-bff/web/subject/detail?subjectId=$subjectId"
                app.get(detailUrl, headers = baseHeaders).text
            } catch (e: Exception) {
                null
            }

            val detailObj = if (detailResponse != null) try { JSONObject(detailResponse) } catch (e: Exception) { null } else null
            val subjectData = unwrapData(detailObj ?: JSONObject()).optJSONObject("subject")
            val detailPath = subjectData?.optString("detailPath") ?: ""
            
            val params = StringBuilder("subjectId=$subjectId")
            if (season != null) {
                params.append("&se=$season&ep=$episode")
            }

            val downloadHeaders = baseHeaders + mapOf(
                "Referer" to "https://fmoviesunblocked.net/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
                "Origin" to "https://fmoviesunblocked.net"
            )
            val streamHeaders = mapOf(
                "Referer" to "https://fmoviesunblocked.net/",
                "Origin" to "https://fmoviesunblocked.net",
                "User-Agent" to (baseHeaders["User-Agent"] ?: "")
            )

            val downloadUrl = "$BASE_URL/wefeed-h5-bff/web/subject/download?$params"
            
            val downloadResponseString = try {
                app.get(downloadUrl, headers = downloadHeaders).text
            } catch (e: Exception) {
                null
            }

            val sourceObj = try { JSONObject(downloadResponseString ?: "{}") } catch (e: Exception) { JSONObject() }
            val sourceData = unwrapData(sourceObj)

            val downloads = sourceData.optJSONArray("downloads")
            if (downloads != null && downloads.length() > 0) {
                for (i in 0 until downloads.length()) {
                    val d = downloads.optJSONObject(i) ?: continue
                    val dlink = d.optString("url")
                    if (dlink.isNotEmpty()) {
                        val resolution = d.optInt("resolution")
                        callback(
                            newExtractorLink(
                                source = "MovieBox ($language)",
                                name = if (language == "Original") "MovieBox" else language,
                                url = dlink,
                                quality = resolution,
                                type = ExtractorLinkType.VIDEO,
                                headers = streamHeaders
                            )
                        )
                    }
                }
            } else {
            }

            val subtitlesList = sourceData.optJSONArray("captions")
            if (subtitlesList != null && subtitlesList.length() > 0) {
                for (i in 0 until subtitlesList.length()) {
                    val s = subtitlesList.optJSONObject(i) ?: continue
                    val slink = s.optString("url")
                    if (slink.isNotEmpty()) {
                        val lan = s.optString("lan")
                        subtitleCallback(
                            newSubtitleFile(
                                getLanguage(lan) ?: lan,
                                slink
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun invokeAllmovieland(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val allmovielandAPI = "https://allmovieland.you"
        println("[AML] Fetching player.js...")
        val playerScript = try { app.get(getProxiedUrl("https://allmovieland.link/player.js?v=60%20128")).text } catch (e: Exception) {
            println("[AML] player.js failed: ${e.message}")
            return
        }
        val domainRegex = Regex("const AwsIndStreamDomain.*'(.*)';")
        val host = domainRegex.find(playerScript)?.groupValues?.getOrNull(1) ?: run {
            println("[AML] Could not find host in player.js. Script snippet: ${playerScript.take(200)}")
            return
        }
        println("[AML] Found host: $host")
        val referer = "$allmovielandAPI/"

        val playUrl = "$host/play/$id"
        println("[AML] Fetching play page: $playUrl")
        val res = try {
            val doc = app.get(playUrl, referer = referer).document
            val script = doc.selectFirst("script:containsData(playlist)")
            println("[AML] Script found: ${script != null}")
            script?.data()
                ?.substringAfter("{")
                ?.substringBefore(";")
                ?.substringBefore(")")
        } catch (e: Exception) {
            println("[AML] play page failed: ${e.message}")
            null
        }
        
        if (res == null) {
            println("[AML] No playlist found in page.")
            return
        }
        println("[AML] Playlist JSON snippet: ${res.take(150)}")

        val json = try { JSONObject("{$res}") } catch (e: Exception) {
            println("[AML] JSON parse failed: ${e.message}")
            return
        }
        val key = json.optString("key")
        val fileUri = json.optString("file")
        println("[AML] key=$key fileUri=$fileUri")
        if (key.isBlank() || fileUri.isBlank()) {
            println("[AML] Missing key or fileUri.")
            return
        }
        
        val headers = mapOf("X-CSRF-TOKEN" to key, "Referer" to referer)

        val serverRes = try {
            val fileUrl = if (fileUri.startsWith("http")) fileUri else if (fileUri.startsWith("/")) "$host$fileUri" else "$host/$fileUri"
            app.get(fileUrl, headers = headers, referer = referer)
                .text
                .replace(Regex(""",\s*\[]"""), "")
        } catch (e: Exception) { return }

        val arr = try { JSONArray(serverRes) } catch (e: Exception) { return }
        val servers = mutableListOf<Pair<String, String>>()

        if (season == null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val file = obj.optString("file")
                val title = obj.optString("title")
                if (file.isNotEmpty() && title.isNotEmpty()) servers.add(Pair(file, title))
            }
        } else {
            for (i in 0 until arr.length()) {
                val sObj = arr.optJSONObject(i) ?: continue
                if (sObj.optString("id") == season.toString()) {
                    val eps = sObj.optJSONArray("folder") ?: continue
                    for (j in 0 until eps.length()) {
                        val eObj = eps.optJSONObject(j) ?: continue
                        if (eObj.optString("episode") == episode.toString()) {
                            val streams = eObj.optJSONArray("folder") ?: continue
                            for (k in 0 until streams.length()) {
                                val strObj = streams.optJSONObject(k) ?: continue
                                val file = strObj.optString("file")
                                val title = strObj.optString("title")
                                if (file.isNotEmpty() && title.isNotEmpty()) servers.add(Pair(file, title))
                            }
                            break
                        }
                    }
                    break
                }
            }
        }

        servers.safeAmap { (server, lang) ->
            val path = try {
                app.post(
                    "${host}/playlist/${server}.txt",
                    headers = headers,
                    referer = referer
                ).text
            } catch (e: Exception) { return@safeAmap null }

            if (path.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "AllMoviesLand ($lang)",
                        name = "Allmoviesland ($lang)",
                        url = path,
                        type = ExtractorLinkType.M3U8,
                        quality = Qualities.P1080.value,
                        referer = referer
                    )
                )
            }
            null
        }
    }

    suspend fun searchMoviebox(
        query: String,
        isTv: Boolean = false
    ): List<JSONObject> {
        val HOST = "h5.aoneroom.com"
        val BASE_URL = "https://$HOST"
        val baseHeaders = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to "$BASE_URL/",
            "Origin" to BASE_URL,
            "Connection" to "keep-alive"
        )


        val subjectType = if (isTv) 2 else 1
        val searchResponseString = try {
            val searchUrl = getProxiedUrl("$BASE_URL/wefeed-h5-bff/web/subject/search")
            app.post(
                searchUrl,
                headers = baseHeaders,
                json = mapOf(
                    "keyword" to query,
                    "page" to 1,
                    "perPage" to 24,
                    "subjectType" to subjectType
                )
            ).text
        } catch (e: Exception) {
            return emptyList()
        }

        val searchObj = try { JSONObject(searchResponseString) } catch (e: Exception) { 
            return emptyList()
        }

        fun unwrapData(json: JSONObject): JSONObject {
            val data = json.optJSONObject("data") ?: return json
            return data.optJSONObject("data") ?: data
        }

        val items = unwrapData(searchObj).optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { results.add(it) }
        }
        return results
    }

    suspend fun getCinemetaHome(type: String = "movie", catalogId: String = "top"): List<JSONObject> {
        val baseUrl = "https://v3-cinemeta.strem.io"
        val url = "$baseUrl/catalog/$type/$catalogId.json"
        val responseString = try { app.get(url).text } catch (e: Exception) { return emptyList() }
        val obj = try { JSONObject(responseString) } catch (e: Exception) { return emptyList() }
        val metas = obj.optJSONArray("metas") ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until metas.length()) { metas.optJSONObject(i)?.let { results.add(it) } }
        return results
    }

    suspend fun getKitsuHome(): List<JSONObject> {
        val baseUrl = "https://anime-kitsu.strem.fun"
        val url = "$baseUrl/catalog/anime/kitsu-anime-trending.json"
        val responseString = try { app.get(url).text } catch (e: Exception) { return emptyList() }
        val obj = try { JSONObject(responseString) } catch (e: Exception) { return emptyList() }
        val metas = obj.optJSONArray("metas") ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until metas.length()) { metas.optJSONObject(i)?.let { results.add(it) } }
        return results
    }

    suspend fun getKitsuDetails(id: String): JSONObject? {
        val baseUrl = "https://anime-kitsu.strem.fun"
        val url = "$baseUrl/meta/anime/$id.json"
        val responseString = try { app.get(url).text } catch (e: Exception) { return null }
        val obj = try { JSONObject(responseString) } catch (e: Exception) { return null }
        return obj.optJSONObject("meta")
    }

    suspend fun searchKitsu(query: String): List<JSONObject> {
        val baseUrl = "https://anime-kitsu.strem.fun"
        val url = "$baseUrl/catalog/anime/kitsu-anime-trending/search=${URLEncoder.encode(query, "UTF-8")}.json"
        val responseString = try { app.get(url).text } catch (e: Exception) { return emptyList() }
        val obj = try { JSONObject(responseString) } catch (e: Exception) { return emptyList() }
        val metas = obj.optJSONArray("metas") ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until metas.length()) { metas.optJSONObject(i)?.let { results.add(it) } }
        return results
    }

    suspend fun searchCinemeta(query: String, type: String = "movie"): List<JSONObject> {
        val baseUrl = "https://v3-cinemeta.strem.io"
        val url = "$baseUrl/catalog/$type/search/search=${URLEncoder.encode(query, "UTF-8")}.json"
        val responseString = try { app.get(url).text } catch (e: Exception) { return emptyList() }
        val obj = try { JSONObject(responseString) } catch (e: Exception) { return emptyList() }
        val metas = obj.optJSONArray("metas") ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until metas.length()) { metas.optJSONObject(i)?.let { results.add(it) } }
        return results
    }

    suspend fun getCinemetaDetails(type: String, id: String): JSONObject? {
        val baseUrl = "https://v3-cinemeta.strem.io"
        val url = "$baseUrl/meta/$type/$id.json"
        val responseString = try { app.get(url).text } catch (e: Exception) { return null }
        val obj = try { JSONObject(responseString) } catch (e: Exception) { return null }
        return obj.optJSONObject("meta")
    }

    suspend fun getMovieboxHome(): List<JSONObject> {
        val HOST = "h5.aoneroom.com"
        val BASE_URL = "https://$HOST"
        val baseHeaders = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to BASE_URL,
            "Host" to HOST,
            "Connection" to "keep-alive"
        )

        val homeUrl = "$BASE_URL/wefeed-h5-bff/web/home/get-home-data?page=1&perPage=24"
        val responseString = try {
            app.get(homeUrl, headers = baseHeaders).text
        } catch (e: Exception) {
            return emptyList()
        }

        val homeObj = try { JSONObject(responseString) } catch (e: Exception) { return emptyList() }
        
        fun unwrapData(json: JSONObject): JSONObject {
            val data = json.optJSONObject("data") ?: return json
            return data.optJSONObject("data") ?: data
        }
        
        val items = unwrapData(homeObj).optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { results.add(it) }
        }
        return results
    }

    suspend fun getMovieboxDetail(subjectId: String): JSONObject? {
        val HOST = "h5.aoneroom.com"
        val BASE_URL = "https://$HOST"
        val baseHeaders = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to BASE_URL,
            "Host" to HOST,
            "Connection" to "keep-alive"
        )

        val detailUrl = "$BASE_URL/wefeed-h5-bff/web/subject/detail?subjectId=${subjectId}"
        val detailResponseString = try {
            app.get(detailUrl, headers = baseHeaders).text
        } catch (e: Exception) {
            return null
        }

        val detailObj = try { JSONObject(detailResponseString) } catch (e: Exception) { return null }
        
        fun unwrapData(json: JSONObject): JSONObject {
            val data = json.optJSONObject("data") ?: return json
            return data.optJSONObject("data") ?: data
        }
        
        return unwrapData(detailObj).optJSONObject("subject")
    }

}
