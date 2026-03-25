package com.moovie.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object CineStreamExtractors {

    fun getProxiedUrl(url: String): String {
        val proxyBase = System.getenv("SCRAPER_PROXY")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://moovie-proxy.sujeetunbeatable.workers.dev"
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
        val tag = "[RogMovies]"
        println("$tag START imdbId=$imdbId title=$title year=$year season=$season episode=$episode")

        if (imdbId == null) {
            println("$tag ABORT: imdbId is null")
            return
        }
        val rogmoviesAPI = "https://rogmovies.vip"
        
        // 1. Search by IMDb ID
        val rawSearchUrl = "$rogmoviesAPI/search.php?q=$imdbId&page=1"
        val searchUrl = getProxiedUrl(rawSearchUrl)
        println("$tag Searching: $rawSearchUrl (proxied)")

        val json = try {
            val resp = app.get(searchUrl)
            println("$tag Search HTTP ${resp.code} body_len=${resp.text.length}")
            resp.text
        } catch (e: Exception) {
            println("$tag Search FAILED: ${e.message}")
            return
        }

        println("$tag Search raw JSON (first 500): ${json.take(500)}")
        
        val searchResponse = tryParseJson<VegaSearchResponse>(json)
        if (searchResponse == null) {
            println("$tag ABORT: Failed to parse VegaSearchResponse. Raw: ${json.take(300)}")
            return
        }
        println("$tag Search hits count: ${searchResponse.hits.size}")

        val exactHits = searchResponse.hits.filter { hit ->
            val hitImdb = hit.document.imdb_id
            val match = hitImdb == imdbId
            println("$tag  hit: id=${hit.document.id} imdb=$hitImdb title=${hit.document.post_title} permalink=${hit.document.permalink} → imdbMatch=$match")
            match
        }

        // If no exact IMDb match, fall back to title search
        val movieUrls: List<String>
        if (exactHits.isNotEmpty()) {
            println("$tag Using ${exactHits.size} exact IMDb match(es)")
            movieUrls = exactHits.map { fixUrl(it.document.permalink, rogmoviesAPI) }
        } else {
            println("$tag No exact IMDb match — falling back to title search for '$title'")
            val titleSearchUrl = getProxiedUrl("$rogmoviesAPI/search.php?q=${URLEncoder.encode(title ?: "", "UTF-8")}&page=1")
            val titleJson = try {
                val resp = app.get(titleSearchUrl)
                println("$tag Title search HTTP ${resp.code} body_len=${resp.text.length}")
                resp.text
            } catch (e: Exception) {
                println("$tag Title search FAILED: ${e.message}")
                return
            }
            val titleResponse = tryParseJson<VegaSearchResponse>(titleJson)
            if (titleResponse == null) {
                println("$tag ABORT: Failed to parse title search response")
                return
            }
            println("$tag Title search hits: ${titleResponse.hits.size}")
            titleResponse.hits.forEach { hit ->
                println("$tag  title-hit: imdb=${hit.document.imdb_id} title=${hit.document.post_title}")
            }
            movieUrls = titleResponse.hits.map { fixUrl(it.document.permalink, rogmoviesAPI) }
        }
        println("$tag Processing ${movieUrls.size} page(s): $movieUrls")

        // 2. Process each result
        movieUrls.safeAmap { pageUrl ->
            println("$tag Fetching page: $pageUrl")
            val proxiedPageUrl = getProxiedUrl(pageUrl)
            val doc = try {
                val resp = app.get(proxiedPageUrl)
                println("$tag Page HTTP ${resp.code} for $pageUrl")
                resp.document
            } catch (e: Exception) {
                println("$tag Page fetch FAILED for $pageUrl: ${e.message}")
                return@safeAmap
            }
            
            println("$tag Page title: '${doc.title()}'")

            // Verify IMDb ID on the page
            val foundImdbId = doc.select("a[href*=\"imdb.com/title/\"]").attr("href")
                .substringAfter("title/").substringBefore("/")
            println("$tag IMDb ID on page: '$foundImdbId' (expected: '$imdbId')")
            
            val idMatch = (foundImdbId == imdbId) || (foundImdbId.isBlank() && doc.body().text().contains(imdbId ?: "---"))
            println("$tag IMDb ID match: $idMatch (foundBlank=${foundImdbId.isBlank()})")

            if (!idMatch) {
                val pageTitle = doc.title().lowercase()
                val fullText = doc.body().text().lowercase()
                val normReq = normalize(title ?: "")
                println("$tag ID mismatch — falling back to title/year check. normReq='$normReq'")
                
                if (normReq.isNotEmpty() && !normalize(pageTitle).contains(normReq) && !normalize(fullText).contains(normReq)) {
                    println("$tag SKIP: title '$normReq' not found in page title or body")
                    return@safeAmap
                }
                if (year != null && !pageTitle.contains(year) && !fullText.contains(year)) {
                    println("$tag SKIP: year '$year' not found in page")
                    return@safeAmap
                }
                println("$tag Fallback match passed for $pageUrl")
            }

            if (season == null) {
                // MOVIE LOGIC
                val downloadBtns = doc.select("button.dwd-button, a.btn-download, a.dwd-button, .download-btn")
                println("$tag [MOVIE] Found ${downloadBtns.size} download button(s) via selector")

                // Dump all links on page for debugging if no buttons found
                if (downloadBtns.isEmpty()) {
                    val allLinks = doc.select("a[href]").map { "${it.text().trim()} -> ${it.attr("href")}" }
                    println("$tag [MOVIE] No download buttons found. All page links (${allLinks.size}):")
                    allLinks.take(40).forEach { println("$tag   $it") }
                }

                downloadBtns.safeAmap { btn ->
                    var link = btn.parent()?.attr("href") ?: btn.attr("href")
                    println("$tag [MOVIE] Button text='${btn.text()}' raw_link='$link'")
                    if (link.isNullOrBlank() || link == "#") {
                        link = btn.select("a").attr("href").takeIf { it.isNotBlank() } ?: ""
                        println("$tag [MOVIE] Resolved inner link: '$link'")
                    }
                    if (link.isBlank()) {
                        println("$tag [MOVIE] SKIP: blank link for button '${btn.text()}'")
                        return@safeAmap
                    }
                    val absoluteLink = fixUrl(link, rogmoviesAPI)
                    println("$tag [MOVIE] Visiting intermediate page: $absoluteLink")
                    
                    val downloadDoc = try {
                        val resp = app.get(getProxiedUrl(absoluteLink))
                        println("$tag [MOVIE] Intermediate page HTTP ${resp.code}")
                        resp.document
                    } catch (e: Exception) {
                        println("$tag [MOVIE] Intermediate page FAILED: ${e.message}")
                        return@safeAmap
                    }

                    val sources = downloadDoc.select("p > a, .download-links a, a.btn, .links a, .dwn-link a")
                    println("$tag [MOVIE] Found ${sources.size} source link(s) on intermediate page")
                    if (sources.isEmpty()) {
                        val allLinks = downloadDoc.select("a[href]").map { "${it.text().trim()} -> ${it.attr("href")}" }
                        println("$tag [MOVIE] No sources matched selector. All links on intermediate page:")
                        allLinks.take(30).forEach { println("$tag   $it") }
                    }

                    sources.safeAmap { source ->
                        val sourceUrl = source.attr("href")
                        val sourceText = source.text()
                        println("$tag [MOVIE] Source: text='$sourceText' url='$sourceUrl'")
                        if (sourceUrl.isBlank() || sourceUrl.contains("telegram", true)) return@safeAmap

                        if (sourceUrl.contains("vcloud", ignoreCase=true) || sourceUrl.contains("hubcloud", ignoreCase=true) || sourceUrl.contains("fastdl", ignoreCase=true)) {
                            println("$tag [MOVIE] Resolving VCloud/HubCloud: $sourceUrl")
                            resolveVCloudOrGDFlix(sourceUrl, "RogMovies") { resolvedLink ->
                                println("$tag [MOVIE] Resolved link: name='${resolvedLink.name}' url='${resolvedLink.url}'")
                                callback(resolvedLink.copy(name = "RogMovies ${resolvedLink.name.replace("RogMovies ", "")}"))
                            }
                        } else {
                            println("$tag [MOVIE] SKIP (not vcloud/fastdl): $sourceUrl")
                        }
                    }
                }
            } else {
                // TV SHOW LOGIC
                val seasonText = "Season $season"
                println("$tag [TV] Looking for season header: '$seasonText'")
                val seasonHeader = doc.select("h4, h3, p, strong").find { it.text().contains(seasonText, ignoreCase = true) }
                
                if (seasonHeader == null) {
                    println("$tag [TV] WARN: No season header found for '$seasonText'")
                    val allHeaders = doc.select("h4, h3, p, strong").map { it.text().trim() }.filter { it.isNotBlank() }
                    println("$tag [TV] Available headers/text elements (first 30):")
                    allHeaders.take(30).forEach { println("$tag   '$it'") }
                } else {
                    println("$tag [TV] Found season header: '${seasonHeader.text()}'")
                    val container = seasonHeader.parent()
                    val containerLinks = container?.select("a")?.toList() ?: emptyList()
                    println("$tag [TV] Container has ${containerLinks.size} link(s)")
                    containerLinks.forEach { println("$tag   link: '${it.text()}' -> '${it.attr("href")}'") }

                    val filteredLinks = containerLinks.filter { 
                        val t = it.text().lowercase()
                        t.contains("v-cloud") || t.contains("single") || t.contains("episode") || t.contains("g-direct")
                    }
                    println("$tag [TV] Filtered episode list links: ${filteredLinks.size}")

                    filteredLinks.safeAmap { episodeListLink ->
                        val epListUrl = fixUrl(episodeListLink.attr("href"), rogmoviesAPI)
                        println("$tag [TV] Fetching episode list: $epListUrl")
                        val epDoc = try {
                            val resp = app.get(getProxiedUrl(epListUrl))
                            println("$tag [TV] Episode list HTTP ${resp.code}")
                            resp.document
                        } catch (e: Exception) {
                            println("$tag [TV] Episode list FAILED: ${e.message}")
                            return@safeAmap
                        }
                        
                        val epPattern = java.util.regex.Pattern.compile("(?i)Episode\\s*0*$episode\\b")
                        println("$tag [TV] Searching for episode pattern: $epPattern")
                        val epElement = epDoc.getElementsMatchingText(epPattern).firstOrNull()
                        println("$tag [TV] Episode element found: ${epElement != null} text='${epElement?.text()?.take(100)}'")

                        val epLink = epElement?.parent()?.select("a")?.firstOrNull { it.text().contains("V-Cloud", true) || it.text().contains("Direct", true) }
                                     ?: epElement?.nextElementSibling()?.select("a")?.firstOrNull { it.text().contains("V-Cloud", true) || it.text().contains("Direct", true) }
                        println("$tag [TV] Episode link: text='${epLink?.text()}' href='${epLink?.attr("href")}'")
                        
                        val finalLink = epLink?.attr("href")
                        if (finalLink.isNullOrBlank()) {
                            println("$tag [TV] WARN: No final link found for S${season}E${episode}")
                            // Dump nearby elements for debugging
                            epElement?.parent()?.select("a")?.forEach {
                                println("$tag [TV]   nearby link: '${it.text()}' -> '${it.attr("href")}'")
                            }
                        } else {
                            println("$tag [TV] Final link for S${season}E${episode}: $finalLink")
                            if (finalLink.contains("vcloud", ignoreCase=true) || finalLink.contains("hubcloud", ignoreCase=true) || finalLink.contains("fastdl", ignoreCase=true)) {
                                println("$tag [TV] Resolving VCloud/HubCloud: $finalLink")
                                resolveVCloudOrGDFlix(finalLink, "RogMovies") { resolvedLink ->
                                    println("$tag [TV] Resolved: name='${resolvedLink.name}' url='${resolvedLink.url}'")
                                    callback(resolvedLink.copy(name = "RogMovies S$season E$episode ${resolvedLink.name.replace("RogMovies ", "")}"))
                                }
                            } else {
                                println("$tag [TV] SKIP (not vcloud/fastdl): $finalLink")
                            }
                        }
                    }
                }
            }
        }
        println("$tag DONE")
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

        // Dynamically resolve the actual stream host from player.js
        val playerScript = try {
            app.get("https://allmovieland.link/player.js?v=60%20128").text
        } catch (e: Exception) {
            println("[AML] Failed to fetch player.js: ${e.message}")
            return
        }
        val host = Regex("const AwsIndStreamDomain.*'(.*)'").find(playerScript)
            ?.groupValues?.getOrNull(1)
        if (host.isNullOrBlank()) {
            println("[AML] Could not extract AwsIndStreamDomain. player.js preview: ${playerScript.take(300)}")
            return
        }
        println("[AML] host=$host")

        val referer = "https://allmovieland.you/"

        // Establish a session by hitting the host root first (gets cookies)
        try {
            app.get(host, headers = mapOf("User-Agent" to USER_AGENT))
        } catch (e: Exception) {
            println("[AML] Session init failed (non-fatal): ${e.message}")
        }

        val res = try {
            val resp = app.get(
                "$host/play/$id",
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
            println("[AML] /play/$id HTTP ${resp.code} len=${resp.text.length}")
            println("[AML] Body preview: ${resp.text.take(500)}")
            resp.document
                .selectFirst("script:containsData(playlist)")
                ?.data()
                ?.substringAfter("{")
                ?.substringBefore(";")
                ?.substringBefore(")")
        } catch (e: Exception) {
            println("[AML] /play/$id fetch failed: ${e.message}")
            null
        }

        if (res == null) {
            println("[AML] No playlist script found")
            return
        }

        val json = tryParseJson<AllMovielandPlaylist>("{$res}")
        val key = json?.key
        val fileUri = json?.file
        println("[AML] key=$key fileUri=$fileUri")

        if (key.isNullOrBlank() || fileUri.isNullOrBlank()) {
            println("[AML] Missing key or file")
            return
        }

        val headers = mapOf("X-CSRF-TOKEN" to key, "Referer" to referer)
        val fileUrl = if (fileUri.startsWith("http")) fileUri else "$host$fileUri"
        println("[AML] Fetching server list: $fileUrl")

        val serverRes = try {
            val resp = app.get(fileUrl, headers = headers, referer = referer)
            println("[AML] Server list HTTP ${resp.code} preview: ${resp.text.take(200)}")
            resp.text.replace(Regex(""",\s*\[]"""), "")
        } catch (e: Exception) {
            println("[AML] Server list fetch failed: ${e.message}")
            return
        }

        val servers: List<Pair<String?, String?>> = tryParseJson<ArrayList<AllMovielandServer>>(serverRes)?.let { server ->
            if (season == null) {
                server.map { it.file to it.title }
            } else {
                server.find { it.id == "$season" }
                    ?.folder?.find { it.episode == "$episode" }
                    ?.folder?.map { it.file to it.title }
            }
        } ?: run {
            println("[AML] Failed to parse server list")
            return
        }

        println("[AML] Found ${servers.size} server(s)")

        servers.safeAmap { (server, lang) ->
            if (server.isNullOrBlank()) return@safeAmap null
            println("[AML] Fetching playlist for server=$server lang=$lang")
            val path = try {
                val resp = app.post(
                    "$host/playlist/$server.txt",
                    headers = headers,
                    referer = referer
                )
                println("[AML] Playlist HTTP ${resp.code} body='${resp.text.take(100)}'")
                resp.text
            } catch (e: Exception) {
                println("[AML] Playlist fetch failed: ${e.message}")
                return@safeAmap null
            }

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

    suspend fun invokeMoviesDrive(
        imdbId: String? = null,
        title: String? = null,
        year: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (imdbId == null) return

        // Fetch the current domain dynamically (same as CSX app)
        val moviesdriveAPI = try {
            val json = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
            tryParseJson<org.json.JSONObject>(json)?.optString("moviesdrive")?.takeIf { it.isNotBlank() }
                ?: "https://new1.moviesdrives.my"
        } catch (e: Exception) {
            println("[MD] Failed to fetch dynamic URL, using fallback: ${e.message}")
            "https://new1.moviesdrives.my"
        }
        println("[MD] Using domain: $moviesdriveAPI")
        
        // 1. Search by IMDb ID
        val searchUrl = getProxiedUrl("$moviesdriveAPI/searchapi.php?q=$imdbId")
        val jsonString = try { app.get(searchUrl).text } catch (e: Exception) { return }
        val root = tryParseJson<JSONObject>(jsonString) ?: return
        if (!root.has("hits")) return
        val hits = root.optJSONArray("hits") ?: return

        var permalink = ""
        for (i in 0 until hits.length()) {
            val hit = hits.optJSONObject(i) ?: continue
            val doc = hit.optJSONObject("document") ?: continue
            val currentImdbId = doc.optString("imdb_id")
            if (imdbId == currentImdbId) {
                permalink = doc.optString("permalink")
                break
            }
        }
        
        if (permalink.isBlank()) return

        // 2. Process Page
        val pageUrl = getProxiedUrl(moviesdriveAPI + permalink)
        val document = try { app.get(pageUrl).document } catch (e: Exception) { return }
        
        // 3. Strict Match Fallback (Title/Year)
        val pageTitleExtracted = document.title().lowercase()
        val fullText = document.body().text().lowercase()
        val normReq = normalize(title ?: "")
        if (normReq.isNotEmpty() && !normalize(pageTitleExtracted).contains(normReq) && !normalize(fullText).contains(normReq)) return
        if (year != null && !pageTitleExtracted.contains(year) && !fullText.contains(year)) return

        val sourceLinks = mutableListOf<String>()

        if (season == null) {
            document.select("h5 > a").forEach {
                sourceLinks.add(it.attr("href"))
            }
        } else {
            // TV SHOW logic
            val sSlug = season.toString().padStart(2, '0')
            val eSlug = episode.toString().padStart(2, '0')
            val stag = "(?i)(Season $season|S$sSlug)"
            val sep = "(?i)(Ep(?:isode)? $episode|Ep(?:isode)?$eSlug|Ep$eSlug|Ep$episode)"
            
            document.select("h5:matches($stag)").forEach { entry ->
                val href = entry.nextElementSibling()?.selectFirst("a")?.attr("href") ?: ""
                if (href.isNotBlank()) {
                    val epDocUrl = getProxiedUrl(href)
                    val epDoc = try { app.get(epDocUrl).document } catch(e:Exception){return@forEach}
                    val fEp = epDoc.selectFirst("h5:matches($sep)") ?: epDoc.selectFirst("span:matches($sep)")?.parent()?.nextElementSibling()
                    
                    var current = fEp?.nextElementSibling()
                    while(current != null && (current.tagName() == "h5" || current.tagName() == "p")) {
                        if (current.text().contains("HubCloud", ignoreCase=true) || current.text().contains("gdflix", ignoreCase=true) || current.text().contains("gdlink", ignoreCase=true)) {
                            val aHref = current.selectFirst("a")?.attr("href")
                            if(aHref != null) sourceLinks.add(aHref)
                        } else if(current.tagName() == "h5") {
                            // If it's a new h5 but not a link, might be next episode marker
                            // But usually links are directly under it
                            val aHref = current.selectFirst("a")?.attr("href")
                            if(aHref != null) {
                                sourceLinks.add(aHref)
                            } else {
                                break
                            }
                        }
                        current = current.nextElementSibling()
                    }
                    
                    if (sourceLinks.isEmpty() && fEp != null) {
                         fEp.nextElementSibling()?.select("a")?.forEach { a ->
                             sourceLinks.add(a.attr("href"))
                         }
                    }
                }
            }
        }

        // 4. Resolve HubCloud / GDFlix
        sourceLinks.safeAmap { link ->
            resolveVCloudOrGDFlix(link, "MoviesDrive", callback)
        }
    }
    private suspend fun resolveVCloudOrGDFlix(
        link: String,
        sourceTag: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "[VCloud/$sourceTag]"
        println("$tag Resolving: $link")

        val resolvedUrl = getProxiedUrl(link)
        val doc = try {
            val resp = app.get(resolvedUrl)
            println("$tag Entry page HTTP ${resp.code} for $link")
            resp.document
        } catch(e: Exception) {
            println("$tag Entry page FAILED: ${e.message}")
            return
        }
        
        if (link.contains("hubcloud", ignoreCase=true) || link.contains("vcloud", ignoreCase=true) || link.contains("fastdl", ignoreCase=true)) {
            println("$tag Branch: HubCloud/VCloud/FastDL")
            val isVideoPath = link.contains("/video/")
            println("$tag isVideoPath=$isVideoPath")

            var scriptLink = if (isVideoPath) {
                val sel = doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
                println("$tag [/video/] div.vd>center>a = '$sel'")
                sel
            } else {
                val scriptTag = doc.select("script:containsData(url)").firstOrNull()?.data() ?: ""
                println("$tag Script tag length=${scriptTag.length} preview='${scriptTag.take(200)}'")
                // vcloud.zip uses: var url = '...'
                // fastdl.zip uses: var reurl = "..."
                val extracted = Regex("var url = '([^']+)'").find(scriptTag)?.groupValues?.get(1)
                    ?: Regex("""var reurl = "([^"]+)"""").find(scriptTag)?.groupValues?.get(1)
                    ?: ""
                println("$tag Extracted scriptLink: '$extracted'")
                extracted
            }

            // fastdl.zip wraps the real URL in a dl.php?link= param — extract it directly
            if (scriptLink.contains("fastdl.zip/dl.php", ignoreCase = true)) {
                val directUrl = scriptLink.substringAfter("link=").takeIf { it.startsWith("http") }
                if (directUrl != null) {
                    println("$tag FastDL direct URL extracted: $directUrl")
                    callback(newExtractorLink(sourceTag, "$sourceTag FastDL", directUrl, ExtractorLinkType.VIDEO, Qualities.Unknown.value))
                } else {
                    println("$tag FastDL WARN: could not extract direct URL from $scriptLink")
                }
                return
            }

            if (scriptLink.isBlank()) {
                println("$tag ABORT: scriptLink is blank after extraction")
                // Dump all scripts for debugging
                doc.select("script").forEachIndexed { i, s ->
                    val d = s.data().take(300)
                    if (d.isNotBlank()) println("$tag   script[$i]: $d")
                }
                // Also dump all links
                doc.select("a[href]").forEach { println("$tag   link: '${it.text()}' -> '${it.attr("href")}'") }
                return
            }

            if (!scriptLink.startsWith("http")) {
                val host = try { java.net.URI(link).host } catch (e: Exception) { "" }
                scriptLink = "https://$host$scriptLink"
                println("$tag Prepended host, scriptLink now: $scriptLink")
            }
            
            println("$tag Fetching inner page: $scriptLink")
            val innerDoc = try {
                val resp = app.get(getProxiedUrl(scriptLink))
                println("$tag Inner page HTTP ${resp.code}")
                resp.document
            } catch(e: Exception) {
                println("$tag Inner page FAILED: ${e.message}")
                return
            }

            val header = innerDoc.select("div.card-header").text()
            val size = innerDoc.select("i#size").text()
            val quality = getIndexQuality(header)
            println("$tag Inner page header='$header' size='$size' quality=$quality")

            val btns = innerDoc.select("h2 a.btn, .btn-success, .btn-primary")
            println("$tag Found ${btns.size} button(s) on inner page")
            btns.forEach { println("$tag   btn: '${it.text()}' href='${it.attr("href")}'") }

            btns.forEach { btn ->
                val dlink = btn.attr("href")
                val text = btn.text()
                println("$tag Checking btn '$text' -> '$dlink'")
                if (dlink.isBlank()) return@forEach

                when {
                    text.contains("FSLv2", ignoreCase = true) -> {
                        // FSLv2: follow the vcloud.zip/re.php redirect to get the signed R2 URL
                        println("$tag FSLv2: following redirect for $dlink")
                        try {
                            val res = app.get(getProxiedUrl(dlink), allowRedirects = false)
                            val redirect = res.headers["location"] ?: res.headers["Location"]
                            println("$tag FSLv2 redirect HTTP ${res.code} location='$redirect'")
                            if (redirect != null) {
                                val finalUrl = if (redirect.contains("link=")) redirect.substringAfter("link=") else redirect
                                println("$tag FSLv2 FINAL URL: $finalUrl")
                                callback(newExtractorLink(sourceTag, "$sourceTag [Hub-Cloud[FSLv2 Server]] $header [$size]", finalUrl, ExtractorLinkType.VIDEO, quality))
                            }
                        } catch (e: Exception) {
                            println("$tag FSLv2 redirect FAILED: ${e.message}")
                        }
                    }
                    text.contains("FSL Server", ignoreCase = true) -> {
                        // FSL: pass directly, let the proxy/player handle it
                        println("$tag FSL: passing direct link $dlink")
                        callback(newExtractorLink(sourceTag, "$sourceTag [Hub-Cloud[FSL Server]] $header [$size]", dlink, ExtractorLinkType.VIDEO, quality))
                    }
                    text.contains("10Gbps", ignoreCase = true) -> {
                        // 10Gbps: follow redirect to get final URL
                        println("$tag 10Gbps: following redirect for $dlink")
                        try {
                            val res = app.get(getProxiedUrl(dlink), allowRedirects = false)
                            val redirect = res.headers["location"] ?: res.headers["Location"]
                            println("$tag 10Gbps redirect HTTP ${res.code} location='$redirect'")
                            if (redirect != null) {
                                val finalUrl = if (redirect.contains("link=")) redirect.substringAfter("link=") else redirect
                                println("$tag 10Gbps FINAL URL: $finalUrl")
                                callback(newExtractorLink(sourceTag, "$sourceTag [Hub-Cloud[10Gbps]] $header [$size]", finalUrl, ExtractorLinkType.VIDEO, quality))
                            }
                        } catch (e: Exception) {
                            println("$tag 10Gbps redirect FAILED: ${e.message}")
                        }
                    }
                    dlink.contains("pixeldra", ignoreCase = true) -> {
                        val baseUrlLink = try { java.net.URI(dlink).let { "https://${it.host}" } } catch (e: Exception) { "" }
                        val finalUrl = if (dlink.contains("download", true)) dlink
                            else "$baseUrlLink/api/file/${dlink.substringAfterLast("/")}?download"
                        println("$tag Pixeldrain: $finalUrl")
                        callback(newExtractorLink(sourceTag, "$sourceTag [Hub-Cloud[Pixeldrain]] $header [$size]", finalUrl, ExtractorLinkType.VIDEO, quality))
                    }
                    else -> println("$tag Skipping btn '$text'")
                }
            }

            if (btns.isEmpty()) {
                println("$tag WARN: No buttons found. Dumping inner page body (first 1000):")
                println(innerDoc.body().text().take(1000))
            }

        } else if (link.contains("gdflix", ignoreCase=true) || link.contains("gdlink", ignoreCase=true)) {
            println("$tag Branch: GDFlix/GDLink")
            val fileName = doc.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
            val fileSize = doc.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ")
            val quality = getIndexQuality(fileName)
            println("$tag fileName='$fileName' fileSize='$fileSize' quality=$quality")
            
            val anchors = doc.select("div.text-center a")
            println("$tag Found ${anchors.size} anchor(s) in div.text-center")
            anchors.forEach { println("$tag   anchor: '${it.text()}' -> '${it.attr("href")}'") }

            anchors.forEach { anchor ->
                val text = anchor.select("a").text().ifBlank { anchor.text() }
                val aLink = anchor.attr("href")
                println("$tag GDFlix anchor text='$text' href='$aLink'")
                if (text.contains("DIRECT DL", ignoreCase=true) || text.contains("DIRECT SERVER", ignoreCase=true)) {
                    println("$tag GDFlix DIRECT callback: $aLink")
                    callback(newExtractorLink(sourceTag, "$sourceTag GDFlix $fileName [$fileSize]", aLink, ExtractorLinkType.VIDEO, quality))
                } else if (text.contains("FAST CLOUD", ignoreCase=true)) {
                    try {
                        println("$tag GDFlix FastCloud fetching: $aLink")
                        val fastDoc = app.get(getProxiedUrl(aLink)).document
                        val fastLink = fastDoc.select("div.card-body a").attr("href")
                        println("$tag GDFlix FastCloud link: '$fastLink'")
                        if (fastLink.isNotBlank()) {
                            callback(newExtractorLink(sourceTag, "$sourceTag FastCloud $fileName [$fileSize]", fastLink, ExtractorLinkType.VIDEO, quality))
                        } else {
                            println("$tag GDFlix FastCloud WARN: blank fastLink")
                        }
                    } catch(e: Exception) {
                        println("$tag GDFlix FastCloud FAILED: ${e.message}")
                    }
                }
            }
        } else {
            println("$tag WARN: Link doesn't match any known branch: $link")
        }
        println("$tag resolveVCloudOrGDFlix DONE for $link")
    }

}

