package com.moovie

import com.moovie.plugins.CineStreamExtractors
import com.moovie.plugins.SubtitleFile
import com.moovie.plugins.ExtractorLink
import kotlinx.coroutines.runBlocking

/**
 * Standalone debug runner for RogMovies.
 * Run with: ./gradlew runDebugRog
 *
 * Tweak the params below to test different titles.
 * Uses the real moovie-proxy (SCRAPER_PROXY env var or the hardcoded default).
 */
fun main() = runBlocking {
    // ── Configure your test case here ──────────────────────────────────
    val imdbId = "tt14539740"  // Pathaan (2023) - Bollywood, likely on RogMovies
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

    val links = mutableListOf<ExtractorLink>()

    CineStreamExtractors.invokeRogmovies(
        imdbId = imdbId,
        title  = title,
        year   = year,
        season = season,
        episode = episode,
        subtitleCallback = { sub: SubtitleFile ->
            println("[SUB] lang=${sub.lang} url=${sub.url}")
        },
        callback = { link: ExtractorLink ->
            links.add(link)
        }
    )

    println("=".repeat(60))
    println("RESULTS: ${links.size} stream(s) found")
    links.forEachIndexed { i, link ->
        println("  [$i] name='${link.name}' quality=${link.quality} type=${link.type}")
        println("       url=${link.url}")
        if (link.headers.isNotEmpty()) println("       headers=${link.headers}")
    }
    if (links.isEmpty()) println("  (none — check logs above for where it died)")
    println("=".repeat(60))
}
