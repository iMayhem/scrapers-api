package com.moovie.plugins

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object CineStreamExtractors {

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

    suspend fun invokeMoviebox(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        fun unwrapData(json: JSONObject): JSONObject {
            val data = json.optJSONObject("data") ?: return json
            return data.optJSONObject("data") ?: data
        }

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

        app.get("$BASE_URL/wefeed-h5-bff/app/get-latest-app-pkgs?app_name=moviebox", headers = baseHeaders)

        val subjectType = if (season != null) 2 else 1
        val searchResponseString = app.post(
            "$BASE_URL/wefeed-h5-bff/web/subject/search",
            headers = baseHeaders,
            json = mapOf(
                "keyword" to title,
                "page" to 1,
                "perPage" to 24,
                "subjectType" to subjectType
            )
        ).text

        val searchObj = try { JSONObject(searchResponseString) } catch (e: Exception) { 
            return 
        }

        val items = unwrapData(searchObj).optJSONArray("items") ?: return


        val titleMatchRegex = """^${Regex.escape(title ?: "")}(?: \[([^\]]+)\])?$""".toRegex(RegexOption.IGNORE_CASE)
        val uniqueIdsWithLang = mutableMapOf<String, String>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId")
            if (id.isEmpty()) continue

            val rawTitle = item.optString("title", "")
            val cleanTitle = rawTitle.replace(SEASON_SUFFIX_REGEX, "")
            val matchResult = titleMatchRegex.find(cleanTitle)

            if (matchResult != null) {
                val language = matchResult.groups[1]?.value ?: "Original"
                uniqueIdsWithLang.putIfAbsent(id, language)
            }
        }

        if (uniqueIdsWithLang.isEmpty()) return


        uniqueIdsWithLang.forEach { (subjectId, language) ->
            val detailUrl = "$BASE_URL/wefeed-h5-bff/web/subject/detail?subjectId=${subjectId}"
            val detailResponseString = app.get(detailUrl, headers = baseHeaders).text

            val detailObj = try { JSONObject(detailResponseString) } catch (e: Exception) { return@forEach }
            val detailPath = unwrapData(detailObj).optJSONObject("subject")?.optString("detailPath") ?: ""

            val params = StringBuilder("subjectId=$subjectId")
            if (season != null) {
                params.append("&se=$season&ep=$episode")
            }

            val downloadHeaders = baseHeaders + mapOf(
                "Referer" to "https://fmoviesunblocked.net/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
                "Origin" to "https://fmoviesunblocked.net"
            )

            val downloadResponseString = app.get(
                "$BASE_URL/wefeed-h5-bff/web/subject/download?$params",
                headers = downloadHeaders
            ).text

            val sourceObj = try { JSONObject(downloadResponseString) } catch (e: Exception) { return@forEach }
            val sourceData = unwrapData(sourceObj)

            val downloads = sourceData.optJSONArray("downloads")
            if (downloads != null) {
                for (i in 0 until downloads.length()) {
                    val d = downloads.optJSONObject(i) ?: continue
                    val dlink = d.optString("url")
                    if (dlink.isNotEmpty()) {
                        val resolution = d.optInt("resolution")
                        callback.invoke(
                            newExtractorLink(
                                "MovieBox [$language]",
                                "MovieBox [$language]",
                                dlink,
                            ) {
                                this.headers = mapOf(
                                    "Referer" to "https://fmoviesunblocked.net/",
                                    "Origin" to "https://fmoviesunblocked.net"
                                )
                                this.quality = resolution
                            }
                        )
                    }
                }
            }

            val subtitles = sourceData.optJSONArray("captions")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val s = subtitles.optJSONObject(i) ?: continue
                    val slink = s.optString("url")
                    if (slink.isNotEmpty()) {
                        val lan = s.optString("lan")
                        subtitleCallback.invoke(
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
}
