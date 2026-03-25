package com.moovie

import com.moovie.plugins.CineStreamExtractors
import com.moovie.plugins.SubtitleFile
import com.moovie.plugins.ExtractorLink
import com.moovie.plugins.app
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // ── Configure your test case here ──────────────────────────────────
    val imdbId = "tt14539740"  // Pathaan (2023)
    val title  = "Pathaan"
    val year   = "2023"
    val season: Int? = null
    val episode: Int? = null
    // ───────────────────────────────────────────────────────────────────

    println("=".repeat(60))
    println("DEBUG RogMovies")
    println("  imdbId  = $imdbId")
    println("  title   = $title")
    println("  year    = $year")
    println("  season  = $season  episode = $episode")
    println("  proxy   = ${System.getenv("SCRAPER_PROXY") ?: "(using hardcoded default)"}")
    println("=".repeat(60))

    // ── Quick redirect probe on a known FSLv2 vcloud.zip/re.php link ──
    println("\n[PROBE] Testing vcloud.zip/re.php redirect chain via proxy...")
    val testReUrl = "https://vcloud.zip/re.php?l=aHR0cHM6Ly81NTYxMzhkY2E3MzY3NzYzZWQ0NmVlY2FhNDI4NGVjYS5yMi5jbG91ZGZsYXJlc3RvcmFnZS5jb20vaHViMi9QYXRoYWFuLjIwMjMuMTA4MHAuQU1aTi5XZWJSaXAuTXVsdGkuRERQNS4xLkhFVkMlMjAtJTIwVmVnYW1vdmllcy50by5ta3Y"
    try {
        val res = app.get(CineStreamExtractors.getProxiedUrl(testReUrl), allowRedirects = false)
        println("[PROBE] HTTP ${res.code}")
        println("[PROBE] Headers: ${res.headers}")
        val loc = res.headers["location"] ?: res.headers["Location"]
        println("[PROBE] Location: $loc")
        // If the proxy itself followed the redirect, check the final URL
        println("[PROBE] Final URL: ${res.url}")
    } catch (e: Exception) {
        println("[PROBE] FAILED: ${e.message}")
    }

    // ── Also probe a direct filebee link to see what we get ──
    println("\n[PROBE] Testing filebee.xyz direct link...")
    val testFilebee = "https://filebee.xyz/file/641a0a2539d80efbd68fa2d0"
    try {
        val res = app.get(
            CineStreamExtractors.getProxiedUrl(testFilebee),
            headers = mapOf("Origin" to "https://filebee.xyz", "Referer" to "https://filebee.xyz/"),
            allowRedirects = false
        )
        println("[PROBE] HTTP ${res.code}")
        val loc = res.headers["location"] ?: res.headers["Location"]
        println("[PROBE] Location: $loc")
        println("[PROBE] Final URL: ${res.url}")
        if (loc == null) println("[PROBE] Body preview: ${res.text.take(300)}")
    } catch (e: Exception) {
        println("[PROBE] FAILED: ${e.message}")
    }

    println("\n" + "=".repeat(60))
    println("Full scrape:")
    println("=".repeat(60))

    val links = mutableListOf<ExtractorLink>()
    CineStreamExtractors.invokeRogmovies(
        imdbId = imdbId, title = title, year = year,
        season = season, episode = episode,
        subtitleCallback = { _: SubtitleFile -> },
        callback = { link: ExtractorLink -> links.add(link) }
    )

    println("=".repeat(60))
    println("RESULTS: ${links.size} stream(s) found")
    links.forEachIndexed { i, link ->
        println("  [$i] name='${link.name}' quality=${link.quality} type=${link.type}")
        println("       url=${link.url}")
    }
    println("=".repeat(60))
}
