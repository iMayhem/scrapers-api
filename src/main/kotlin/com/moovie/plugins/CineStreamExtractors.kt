package com.moovie.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object CineStreamExtractors {

    private fun getProxiedUrl(url: String): String {
        val proxyBase = System.getenv("SCRAPER_PROXY")?.trim() ?: return url
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



        logCallback("MovieBox: Using Proxy Base: ${getProxyBase() ?: "NONE"}")
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
        val searchKeyword = title ?: ""
        logCallback("MovieBox: Performing search for '$searchKeyword' (matching year $year)...")

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
            val preview = searchResponse.take(150).replace("\n", " ")
            logCallback("MovieBox Error: Failed to parse search JSON. Raw start: $preview")
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
            
            logCallback("MovieBox Candidate: '$rawTitle' (ID: $id)")

            val matchResult = titleMatchRegex.find(cleanTitle)
            if (matchResult != null || cleanTitle.equals(title, ignoreCase = true) || cleanTitle.contains(title ?: "", ignoreCase = true)) {
                val lang = matchResult?.groups?.get(1)?.value ?: "Original"
                logCallback("MovieBox MATCH: '$rawTitle' -> $lang")
                matches.add(id to lang)
            } else {
                logCallback("MovieBox SKIP: Title mismatch")
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
            val detailPath = subjectData?.optString("detailPath") ?: ""
            
            val params = StringBuilder("subjectId=$subjectId")
            if (season != null) {
                params.append("&se=$season&ep=$episode")
            }

            val downloadHeaders = baseHeaders + mapOf(
                "Referer" to "https://fmoviesunblocked.net/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
                "Origin" to "https://fmoviesunblocked.net"
            )

            logCallback("MovieBox: Exacting stream from download endpoint...")
            val downloadUrl = getProxiedUrl("$BASE_URL/wefeed-h5-bff/web/subject/download?$params")
            
            val downloadResponseString = try {
                app.get(downloadUrl, headers = downloadHeaders).text
            } catch (e: Exception) {
                logCallback("MovieBox Error: Download fetch failed for $subjectId")
                null
            }

            val sourceObj = try { JSONObject(downloadResponseString ?: "{}") } catch (e: Exception) { JSONObject() }
            val sourceData = unwrapData(sourceObj)

            val downloads = sourceData.optJSONArray("downloads")
            if (downloads != null && downloads.length() > 0) {
                logCallback("MovieBox: Found ${downloads.length()} download links!")
                for (i in 0 until downloads.length()) {
                    val d = downloads.optJSONObject(i) ?: continue
                    val dlink = d.optString("url")
                    if (dlink.isNotEmpty()) {
                        val resolution = d.optInt("resolution")
                        callback(
                            newExtractorLink(
                                source = "MovieBox ($language)",
                                name = "MovieBox",
                                url = dlink,
                                quality = resolution,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            } else {
                logCallback("MovieBox: No streams found in download endpoint.")
            }

            val subtitlesList = sourceData.optJSONArray("captions")
            if (subtitlesList != null && subtitlesList.length() > 0) {
                logCallback("MovieBox: Found ${subtitlesList.length()} subtitle tracks.")
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
