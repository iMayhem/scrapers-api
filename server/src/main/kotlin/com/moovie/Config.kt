package com.moovie

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ScraperConfig(
    val repoUrl: String,
    val pluginsDir: String,
    val enabledProviders: Set<String>,
    val disabledProviders: Set<String>,
    val scrapeTimeoutSeconds: Int,
    val preferHls: Boolean,
)

object Config {
    private val configFile: File by lazy {
        File(System.getenv("CONFIG_FILE") ?: "config.json")
    }

    fun load(): ScraperConfig {
        val defaults = ScraperConfig(
            repoUrl = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/repo.json",
            pluginsDir = System.getenv("PLUGINS_DIR") ?: "plugins",
            enabledProviders = emptySet(),
            disabledProviders = emptySet(),
            scrapeTimeoutSeconds = 90,
            preferHls = true,
        )

        if (!configFile.exists()) return defaults

        return try {
            val json = JSONObject(configFile.readText())
            defaults.copy(
                repoUrl = json.optString("repoUrl", defaults.repoUrl),
                pluginsDir = json.optString("pluginsDir", defaults.pluginsDir),
                enabledProviders = json.optJSONArray("enabledProviders")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } ?: defaults.enabledProviders,
                disabledProviders = json.optJSONArray("disabledProviders")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } ?: defaults.disabledProviders,
                scrapeTimeoutSeconds = json.optInt("scrapeTimeoutSeconds", defaults.scrapeTimeoutSeconds),
                preferHls = json.optBoolean("preferHls", defaults.preferHls),
            )
        } catch (e: Exception) {
            println("[CONFIG] Failed to parse config.json, using defaults: ${e.message}")
            defaults
        }
    }

    fun toJson(config: ScraperConfig): JSONObject = JSONObject().apply {
        put("repoUrl", config.repoUrl)
        put("pluginsDir", config.pluginsDir)
        put("enabledProviders", JSONArray(config.enabledProviders.toList()))
        put("disabledProviders", JSONArray(config.disabledProviders.toList()))
        put("scrapeTimeoutSeconds", config.scrapeTimeoutSeconds)
        put("preferHls", config.preferHls)
    }
}