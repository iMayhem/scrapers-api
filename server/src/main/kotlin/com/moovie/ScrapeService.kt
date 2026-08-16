package com.moovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ScrapeService {

    private val config: ScraperConfig by lazy { Config.load() }
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val semaphore = Semaphore(10)

    // ── Search ────────────────────────────────────────────────────────────
    suspend fun search(query: String, type: String): JSONArray {
        val providers = PluginManager.providersForType(type)
        val results = ConcurrentHashMap.newKeySet<SearchResult>()
        coroutineScope {
            providers.map { provider ->
                launch(Dispatchers.IO) {
                    runCatching { withTimeout(config.scrapeTimeoutSeconds * 1000L) { provider.search(query) } }
                        .getOrNull()
                        ?.forEach { r ->
                            results.add(
                                SearchResult(
                                    name = r.name,
                                    url = r.url,
                                    provider = provider.name,
                                    type = r.type?.name?.lowercase() ?: type,
                                    poster = r.posterUrl,
                                )
                            )
                        }
                }
            }.forEach { it.join() }
        }
        val arr = JSONArray()
        results.toList().sortedBy { it.name.lowercase() }.forEach { arr.put(it.toJson()) }
        return arr
    }

    suspend fun home(type: String, catalog: String): JSONArray {
        val providers = PluginManager.providersForType(type)
        val results = ConcurrentHashMap.newKeySet<SearchResult>()
        coroutineScope {
            providers.filter { it.hasMainPage }.map { provider ->
                launch(Dispatchers.IO) {
                    runCatching {
                        withTimeout(config.scrapeTimeoutSeconds * 1000L) {
                            provider.getMainPage(0, MainPageRequest(catalog, catalog, false))
                        }
                    }.getOrNull()?.items?.forEach { list ->
                        list.list.forEach { r ->
                            results.add(
                                SearchResult(
                                    name = r.name,
                                    url = r.url,
                                    provider = provider.name,
                                    type = r.type?.name?.lowercase() ?: type,
                                    poster = r.posterUrl,
                                )
                            )
                        }
                    }
                }
            }.forEach { it.join() }
        }
        val arr = JSONArray()
        results.toList().sortedBy { it.name.lowercase() }.forEach { arr.put(it.toJson()) }
        return arr
    }

    // ── Details ───────────────────────────────────────────────────────────
    suspend fun details(providerName: String?, url: String, type: String): JSONObject? {
        val providers = if (providerName != null) {
            listOfNotNull(PluginManager.providerByName(providerName))
        } else PluginManager.providersForType(type)
        val load = providers.firstNotNullOfOrNull { p ->
            runCatching { withTimeout(config.scrapeTimeoutSeconds * 1000L) { p.load(url) } }.getOrNull()
        } ?: return null

        return JSONObject().apply {
            put("name", load.name)
            put("url", load.url)
            put("apiName", load.apiName)
            put("type", load.type.name)
            put("posterUrl", load.posterUrl)
            put("year", load.year)
            put("plot", load.plot)
            put("duration", load.duration)
            when (load) {
                is TvSeriesLoadResponse -> {
                    val episodes = JSONArray()
                    load.episodes.forEach { ep ->
                        episodes.put(JSONObject().apply {
                            put("data", ep.data)
                            put("name", ep.name)
                            put("season", ep.season)
                            put("episode", ep.episode)
                        })
                    }
                    put("episodes", episodes)
                }
                is AnimeLoadResponse -> {
                    val episodes = JSONArray()
                    load.episodes.values.flatten().forEach { ep ->
                        episodes.put(JSONObject().apply {
                            put("data", ep.data)
                            put("name", ep.name)
                            put("season", ep.season)
                            put("episode", ep.episode)
                        })
                    }
                    put("episodes", episodes)
                }
                is MovieLoadResponse -> put("dataUrl", load.dataUrl)
            }
        }
    }

    // ── Scrape (loadLinks → direct links) ────────────────────────────────
    suspend fun scrape(
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        type: String,
        isStreaming: Boolean,
        onStream: (JSONObject) -> Unit,
    ): List<JSONObject> {
        val streams = ConcurrentHashMap.newKeySet<String>()
        val streamMap = ConcurrentHashMap<String, JSONObject>()

        suspend fun addStream(provider: String, link: ExtractorLink) {
            val st = streamObject(provider, link)
            val key = link.url
            if (streams.add(key)) {
                streamMap[key] = st
                if (isStreaming) onStream(st)
            }
        }

        val providers = PluginManager.providersForType(type)
        coroutineScope {
            providers.map { provider ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        runCatching {
                            withTimeout(config.scrapeTimeoutSeconds * 1000L) {
                                scrapeProvider(provider, title, year, season, episode, ::addStream)
                            }
                        }.onFailure {
                            println("[SCRAPE] ${provider.name} failed: ${it.message}")
                        }
                    }
                }
            }.forEach { it.join() }
        }

        return streamMap.values
            .map { st ->
                val latency = measureLatency(st.optString("url"), st.optJSONObject("headers"))
                if (latency != null) st.put("latencyMs", latency)
                st
            }
            .sortedBy { it.optString("quality") }
            .sortedBy { it.optLong("latencyMs", Long.MAX_VALUE) }
    }

    private suspend fun scrapeProvider(
        provider: MainAPI,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        onLink: suspend (String, ExtractorLink) -> Unit,
    ) {
        val results = provider.search(title, 1) ?: return
        val match = results.items.minByOrNull { similarity(title, it.name) } ?: return
        if (similarity(title, match.name) < 0.45) return

        val load = provider.load(match.url) ?: return

        val dataList = when (load) {
            is MovieLoadResponse -> listOf(load.dataUrl)
            is TvSeriesLoadResponse -> pickEpisode(load.episodes, season, episode)?.let { listOf(it.data) } ?: emptyList()
            is AnimeLoadResponse -> pickAnimeEpisode(load.episodes, season, episode)?.let { listOf(it.data) } ?: emptyList()
            else -> emptyList()
        }

        dataList.forEach { data ->
            provider.loadLinks(
                data,
                isCasting = false,
                subtitleCallback = { },
                callback = { link ->
                    CoroutineScope(Dispatchers.IO).launch { onLink(provider.name, link) }
                },
            )
        }
    }

    private fun pickEpisode(episodes: List<Episode>, season: Int?, episode: Int?): Episode? {
        if (season == null && episode == null) return episodes.firstOrNull()
        return episodes.firstOrNull {
            (season == null || it.season == season) && (episode == null || it.episode == episode)
        } ?: episodes.firstOrNull { it.episode == episode }
    }

    private fun pickAnimeEpisode(episodes: Map<DubStatus, List<Episode>>, season: Int?, episode: Int?): Episode? {
        val all = episodes.values.flatten()
        return pickEpisode(all, season, episode)
    }

    private fun streamObject(provider: String, link: ExtractorLink): JSONObject {
        val st = when (link.type) {
            ExtractorLinkType.M3U8 -> "hls"
            ExtractorLinkType.DASH -> "dash"
            else -> if (link.url.contains(".m3u8")) "hls" else "mp4"
        }
        return JSONObject().apply {
            put("server", "$provider [${link.name}]")
            put("url", link.url)
            put("quality", if (link.quality > 0) "${link.quality}p" else "Auto")
            put("type", st)
            if (link.headers.isNotEmpty()) put("headers", JSONObject(link.headers))
            if (link.referer.isNotBlank()) put("referer", link.referer)
            put("providerKey", provider)
        }
    }

    private fun measureLatency(url: String, headers: JSONObject?): Long? {
        val pingClient = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        return try {
            val reqBuilder = Request.Builder().url(url)
            headers?.keys()?.forEach { k -> reqBuilder.header(k, headers.getString(k)) }
            val start = System.currentTimeMillis()
            pingClient.newCall(reqBuilder.head().build()).execute().use { resp ->
                if (resp.isSuccessful) return System.currentTimeMillis() - start
                pingClient.newCall(
                    Request.Builder().url(url)
                        .addHeader("Range", "bytes=0-0")
                        .apply { headers?.keys()?.forEach { k -> header(k, headers.getString(k)) } }
                        .build()
                ).execute().use { getResp ->
                    if (getResp.isSuccessful) System.currentTimeMillis() - start else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun similarity(a: String, b: String): Double {
        val s1 = a.lowercase()
        val s2 = b.lowercase()
        if (s1 == s2) return 1.0
        if (s1 in s2 || s2 in s1) return 0.9
        val max = maxOf(s1.length, s2.length)
        if (max == 0) return 1.0
        val matrix = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) matrix[i][0] = i
        for (j in 0..s2.length) matrix[0][j] = j
        for (i in 1..s1.length) for (j in 1..s2.length) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            matrix[i][j] = minOf(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1, matrix[i - 1][j - 1] + cost)
        }
        val dist = matrix[s1.length][s2.length]
        return 1.0 - dist.toDouble() / max
    }

    data class SearchResult(
        val name: String,
        val url: String,
        val provider: String,
        val type: String,
        val poster: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("url", url)
            put("provider", provider)
            put("type", type)
            put("poster", poster)
        }
    }
}