package com.moovie.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object CineStreamExtractors {

    private fun getProxiedUrl(url: String): String {
        val proxyBase = System.getenv("SCRAPER_PROXY") ?: return url
        val sep = if (proxyBase.contains("?")) "&" else "?"
        return "$proxyBase${sep}url=${URLEncoder.encode(url, "UTF-8")}"
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
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to BASE_URL,
            "Host" to HOST,
            "Connection" to "keep-alive"
        )

        logCallback("MovieBox: Initializing with Title='$title', Year='$year', S=$season, E=$episode")
        
        // 1. App Init (Optional but mimics client)
        try {
            val initUrl = getProxiedUrl("$BASE_URL/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox")
            app.get(initUrl, headers = baseHeaders)
        } catch (e: Exception) {
            logCallback("MovieBox Warning: Init failed: ${e.message}")
        }

        // 2. Search
        val subjectType = if (season != null) 2 else 1
        val searchKeyword = if (year != null && year.length == 4) "$title $year" else title
        logCallback("MovieBox: Performing search for '$searchKeyword'...")

        val searchResponse = try {
            val searchUrl = getProxiedUrl("$BASE_URL/wefeed-h5-bff/web/subject/search")
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
            logCallback("MovieBox Error: Search request failed: ${e.message}")
            return
        }

        val searchObj = try { JSONObject(searchResponse) } catch (e: Exception) { 
            logCallback("MovieBox Error: Failed to parse search JSON.")
            return 
        }
        val items = unwrapData(searchObj).optJSONArray("items") ?: run {
            logCallback("MovieBox: No items found in search results.")
            return
        }

        logCallback("MovieBox: Found ${items.length()} search candidates.")

        // 3. Match Matching
        val escapedTitle = Regex.escape(title ?: "")
        val titleMatchRegex = """^$escapedTitle(?:\s*[\(\[]\d{4}[\)\]])?(?:\s*\[([^\]]+)\])?$""".toRegex(RegexOption.IGNORE_CASE)
        val matches = mutableListOf<Pair<String, String>>() // ID, Language

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId")
            val rawTitle = item.optString("title", "")
            val cleanTitle = rawTitle.replace(SEASON_SUFFIX_REGEX, "").trim()
            
            val matchResult = titleMatchRegex.find(cleanTitle)
            if (matchResult != null || cleanTitle.equals(title, ignoreCase = true)) {
                val lang = matchResult?.groups?.get(1)?.value ?: "Original"
                logCallback("MovieBox Match: '$rawTitle' -> $lang ($id)")
                matches.add(id to lang)
            } else {
                logCallback("MovieBox Skip: '$rawTitle' (Title mismatch)")
            }
        }

        if (matches.isEmpty()) {
            logCallback("MovieBox: No matching subjects found.")
            return
        }

        // 4. Detail & Link Extraction
        matches.forEach { (subjectId, language) ->
            logCallback("MovieBox: Fetching episode list for $subjectId...")
            val detailResponse = try {
                val detailUrl = getProxiedUrl("$BASE_URL/wefeed-h5-bff/web/subject/detail?subjectId=$subjectId")
                app.get(detailUrl, headers = baseHeaders).text
            } catch (e: Exception) {
                logCallback("MovieBox Error: Detail fetch failed for $subjectId")
                null
            }

            val detailObj = if (detailResponse != null) try { JSONObject(detailResponse) } catch (e: Exception) { null } else null
            val subjectData = unwrapData(detailObj ?: JSONObject()).optJSONObject("subject")
            val episodes = subjectData?.optJSONArray("episodes")

            if (episodes != null && episodes.length() > 0) {
                logCallback("MovieBox: Processing ${episodes.length()} episodes...")
                
                val targetEpisode = if (season != null && episode != null) {
                    var found: JSONObject? = null
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        if (ep.optInt("season") == season && ep.optInt("episode") == episode) {
                            found = ep
                            break
                        }
                    }
                    found
                } else episodes.getJSONObject(0)

                if (targetEpisode != null) {
                    val epId = targetEpisode.optString("id")
                    logCallback("MovieBox: Extracting stream for Episode ID $epId...")
                    
                    val playUrl = getProxiedUrl("$BASE_URL/wefeed-h5-bff/web/subject/play-info?id=$epId")
                    val playResponse = try {
                        app.get(playUrl, headers = baseHeaders).text
                    } catch (e: Exception) {
                        logCallback("MovieBox Error: Play info failed for $epId")
                        null
                    }

                    val playData = unwrapData(if (playResponse != null) try { JSONObject(playResponse) } catch (e: Exception) { JSONObject() } else JSONObject())
                    val contents = playData.optJSONArray("contents")
                    if (contents != null && contents.length() > 0) {
                        logCallback("MovieBox: Found ${contents.length()} quality variants.")
                        for (i in 0 until contents.length()) {
                            val c = contents.getJSONObject(i)
                            val url = c.optString("url")
                            val q = c.optInt("quality", 0)
                            if (url.isNotEmpty()) {
                                callback(
                                    newExtractorLink(
                                        source = "MovieBox ($language)",
                                        name = "MovieBox",
                                        url = url,
                                        quality = q,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                            }
                        }
                    } else {
                        logCallback("MovieBox: No streams found in play-info for this episode.")
                    }
                } else {
                    logCallback("MovieBox: Requested S${season}E${episode} not found in episode list.")
                }
            } else {
                logCallback("MovieBox: No episodes found for this subject.")
            }
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
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to BASE_URL,
            "Host" to HOST,
            "Connection" to "keep-alive"
        )

        val subjectType = if (isTv) 2 else 1
        val searchResponseString = try {
            app.post(
                "$BASE_URL/wefeed-h5-bff/web/subject/search",
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
