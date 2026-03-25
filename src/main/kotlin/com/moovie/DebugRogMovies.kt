package com.moovie

import com.moovie.plugins.CineStreamExtractors
import com.moovie.plugins.ExtractorLink
import com.moovie.plugins.app
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val imdbId = "tt14539740"  // Pathaan (2023)
    val title  = "Pathaan"
    val year   = "2023"
    val season: Int? = null
    val episode: Int? = null

    println("=".repeat(60))
    println("DEBUG MoviesDrive")
    println("  imdbId=$imdbId  title=$title  year=$year")
    println("=".repeat(60))

    // Step 1: probe the search API directly
    val moviesdriveAPI = "https://new1.moviesdrives.my"
    println("\n[PROBE] Searching: $moviesdriveAPI/searchapi.php?q=$imdbId")
    try {
        val resp = app.get(CineStreamExtractors.getProxiedUrl("$moviesdriveAPI/searchapi.php?q=$imdbId"))
        println("[PROBE] HTTP ${resp.code} len=${resp.text.length}")
        println("[PROBE] Body: ${resp.text.take(600)}")
    } catch (e: Exception) {
        println("[PROBE] FAILED: ${e.message}")
    }

    println("\n" + "=".repeat(60))
    println("Full invokeMoviesDrive:")
    println("=".repeat(60))

    val links = mutableListOf<ExtractorLink>()
    CineStreamExtractors.invokeMoviesDrive(
        imdbId = imdbId, title = title, year = year,
        season = season, episode = episode,
        callback = { link: ExtractorLink -> links.add(link) }
    )

    println("=".repeat(60))
    println("RESULTS: ${links.size} stream(s)")
    links.forEachIndexed { i, link ->
        println("  [$i] name='${link.name}' quality=${link.quality} type=${link.type}")
        println("       url=${link.url}")
    }
    if (links.isEmpty()) println("  (none)")
    println("=".repeat(60))
}
