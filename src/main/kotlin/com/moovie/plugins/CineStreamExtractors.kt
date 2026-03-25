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

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) return url
        if (url.isEmpty()) return ""
        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) return "https:$url"
        return if (url.startsWith('/')) "$domain$url" else "$domain/$url"
    }

    private fun normalize(s: String): String {
        return s.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    suspend fun invokeRogmovies(
        imdbId: String? = null,
        title: String? = null,
        year: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (imdbId == null) return
        val rogmoviesAPI = "https://rogmovies.vip"
        
        // 1. Search by IMDb ID
        val searchUrl = getProxiedUrl("$rogmoviesAPI/search.php?q=$imdbId&page=1")
        val json = try { app.get(searchUrl).text } catch (e: Exception) { return }
        
        val searchResponse = tryParseJson<VegaSearchResponse>(json) ?: return
        val movieUrls = searchResponse.hits.map { hit ->
            fixUrl(hit.document.permalink, rogmoviesAPI)
        }

        if (movieUrls.isEmpty()) return

        // 2. Process each result (usually just one for a specific IMDb ID)
        movieUrls.safeAmap { pageUrl ->
            val proxiedPageUrl = getProxiedUrl(pageUrl)
            val doc = try { app.get(proxiedPageUrl).document } catch (e: Exception) { return@safeAmap }
            
            // Verify IMDb ID on the page - STRICT CHECK
            val foundImdbId = doc.select("a[href*=\"imdb.com/title/\"]").attr("href")
                .substringAfter("title/").substringBefore("/")
            
            // Title/Year Verification to avoid sequels/wrong versions
            val pageTitle = doc.title().lowercase()
            val requestedTitle = title?.lowercase() ?: ""
            val normPage = normalize(pageTitle)
            val normReq = normalize(requestedTitle)
            
            if (normReq.isNotEmpty() && !normPage.contains(normReq)) return@safeAmap
            if (year != null && !pageTitle.contains(year)) return@safeAmap

            if (season == null) {
                // MOVIE LOGIC
                // Look for common download/stream button patterns on Vegamovies-like sites
                doc.select("button.dwd-button, a.btn-download, a.dwd-button, .download-btn").safeAmap { btn ->
                    var link = btn.parent()?.attr("href") ?: btn.attr("href")
                    if (link.isNullOrBlank() || link == "#") {
                        // Sometimes the button itself has the data or it's a sibling
                        link = btn.select("a").attr("href").takeIf { it.isNotBlank() } ?: ""
                    }
                    if (link.isBlank()) return@safeAmap
                    val absoluteLink = fixUrl(link, rogmoviesAPI)
                    
                    // Visit the intermediate link to find the actual file/embed links
                    val downloadDoc = try { app.get(getProxiedUrl(absoluteLink)).document } catch (e: Exception) { return@safeAmap }
                    downloadDoc.select("p > a, .download-links a, a.btn, .links a, .dwn-link a").safeAmap { source ->
                        val sourceUrl = source.attr("href")
                        val sourceText = source.text()
                        if (sourceUrl.isNotBlank() && !sourceUrl.contains("telegram", true)) {
                            callback(
                                newExtractorLink(
                                    source = "RogMovies",
                                    name = "RogMovies $sourceText",
                                    url = sourceUrl,
                                    quality = getIndexQuality(sourceText),
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    }
                }
            } else {
                // TV SHOW LOGIC
                // Find Season block
                val seasonText = "Season $season"
                val seasonHeader = doc.select("h4, h3, p, strong").find { it.text().contains(seasonText, ignoreCase = true) }
                
                if (seasonHeader != null) {
                    // Usually links follow the header
                    val container = seasonHeader.parent()
                    container?.select("a")?.toList()?.filter { 
                        val t = it.text().lowercase()
                        t.contains("v-cloud") || t.contains("single") || t.contains("episode") || t.contains("g-direct")
                    }?.safeAmap { episodeListLink ->
                        val epListUrl = fixUrl(episodeListLink.attr("href"), rogmoviesAPI)
                        val epDoc = try { app.get(getProxiedUrl(epListUrl)).document } catch (e: Exception) { return@safeAmap }
                        
                        // Find the specific episode
                        val epPattern = java.util.regex.Pattern.compile("(?i)Episode\\s*0*$episode\\b")
                        val epElement = epDoc.getElementsMatchingText(epPattern).firstOrNull()
                        val epLink = epElement?.parent()?.select("a")?.firstOrNull { it.text().contains("V-Cloud", true) || it.text().contains("Direct", true) }
                                     ?: epElement?.nextElementSibling()?.select("a")?.firstOrNull { it.text().contains("V-Cloud", true) || it.text().contains("Direct", true) }
                        
                        val finalLink = epLink?.attr("href")
                        if (!finalLink.isNullOrBlank()) {
                            callback(
                                newExtractorLink(
                                    source = "RogMovies",
                                    name = "RogMovies S$season E$episode",
                                    url = finalLink,
                                    quality = Qualities.P1080.value,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getProxyBase(): String? = System.getenv("SCRAPER_PROXY")


    private fun getIndexQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it
        }
        val lowerStr = str.lowercase()
        return when {
            lowerStr.contains("8k") -> 4320
            lowerStr.contains("4k") -> 2160
            lowerStr.contains("2k") -> 1440
            else -> Qualities.Unknown.value
        }
    }

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
            .filter { it.totalScore > 0 || (it.titleScore >= 300 && it.releaseYear == null) }
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
        title: String? = null,
        year: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (id == null) return
        val amlHost = "https://allmovieland.you"
        val playUrl = if (season == null) "$amlHost/movie/$id" else "$amlHost/tv/$id"
        val referer = "$amlHost/"

        val res = try {
            val doc = app.get(playUrl, referer = referer).document
            
            // Metadata Verification for AllMoviesLand (avoid fake/upcoming placeholder posts)
            val pageTitle = doc.select("h1, .video-title").text().lowercase()
            val requestedTitle = title?.lowercase() ?: ""
            val normPage = normalize(pageTitle)
            val normReq = normalize(requestedTitle)

            if (normReq.isNotEmpty() && !normPage.contains(normReq)) return
            if (year != null && !pageTitle.contains(year)) return

            val script = doc.selectFirst("script:containsData(\"file\":)")
            if (script == null) {
                val altScript = doc.select("script").find { it.data().contains("\"file\":") }
                altScript?.data()
                    ?.substringAfter("{")
                    ?.substringBeforeLast("}")
            } else {
                script.data()
                    ?.substringAfter("{")
                    ?.substringBeforeLast("}")
            }
        } catch (e: Exception) {
            println("[AML] Extraction failed: ${e.message}")
            null
        }
        
        if (res == null) return
        
        val json = try { JSONObject("{$res}") } catch (e: Exception) {
            println("[AML] JSON parse failed: ${e.message}")
            return
        }
        val key = json.optString("key")
        val fileUri = json.optString("file")
        println("[AML] key=$key fileUri=$fileUri")

     println("[AML] key=$key fileUri=$fileUri")
        if (key.isBlank() || fileUri.isBlank()) {
            println("[AML] Missing key or fileUri.")
            return
        }
        
        val headers = mapOf("X-CSRF-TOKEN" to key, "Referer" to referer)

        val serverRes = try {
            val fileUrl = if (fileUri.startsWith("http")) fileUri else if (fileUri.startsWith("/")) "$amlHost$fileUri" else "$amlHost/$fileUri"
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
                    "${amlHost}/playlist/${server}.txt",
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
