package com.moovie.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Moovie Scraper API Wrapper is running! Add your CSX scrapers here.")
        }

        get("/api/scrape") {
            val tmdbId = call.request.queryParameters["tmdbId"]
            val provider = call.request.queryParameters["provider"]

            if (tmdbId == null || provider == null) {
                call.respondText("Missing tmdbId or provider parameters", status = io.ktor.http.HttpStatusCode.BadRequest)
                return@get
            }

            // Initialize the ported CSX Provider
            val cineStream = com.megix.CineStreamProvider()

            // TODO: Because CloudStream expects an Android Context (app.get()), 
            // you might need to mock AcraApplication.context here before calling .load()
            
            call.respondText("""
                {
                    "provider": "$provider",
                    "status": "ready",
                    "message": "CineStream has been successfully integrated! Pass the correct LoadResponse parameters to `cineStream.loadLinks()` to extract videos."
                }
            """.trimIndent(), io.ktor.http.ContentType.Application.Json)
        }
    }
}
