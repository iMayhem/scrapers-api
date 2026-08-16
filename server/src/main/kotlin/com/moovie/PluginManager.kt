package com.moovie

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.runtime.loader.ExtensionLoader
import java.io.File

/**
 * Loads Cloudstream plugin jars (from the phisher builds branch or any CS3 repo)
 * into isolated classloaders and exposes their registered providers.
 *
 * Scrapers are plain .jar/.cs3 files dropped into the plugins dir. Run
 * scripts/update-plugins.sh (or hit /api/reload) to pull the latest builds.
 */
object PluginManager {

    private val config: ScraperConfig by lazy { Config.load() }
    private val pluginsDir: File by lazy { File(config.pluginsDir).apply { mkdirs() } }

    fun init() {
        println("[PLUGINS] Scanning ${pluginsDir.absolutePath} for plugin jars...")
        val loaded = ExtensionLoader.rescanAndLoadNewPlugins(pluginsDir)
        println("[PLUGINS] Loaded $loaded plugins. Providers: ${providers().size}")
    }

    fun reload() {
        ExtensionLoader.plugins.keys.toList().forEach { path ->
            try {
                ExtensionLoader.unloadPlugin(path)
            } catch (t: Throwable) {
                println("[PLUGINS] Failed to unload $path: ${t.message}")
            }
        }
        init()
    }

    fun providers(): List<MainAPI> =
        APIHolder.allProviders
            .asSequence()
            .filter { it.isEnabled() }
            .sortedBy { it.name.lowercase() }
            .toList()

    private fun MainAPI.isEnabled(): Boolean {
        if (config.enabledProviders.isNotEmpty() && name !in config.enabledProviders) return false
        if (name in config.disabledProviders) return false
        return true
    }

    fun providersForType(type: String): List<MainAPI> {
        val wanted = when (type) {
            "show", "series" -> setOf(TvType.TvSeries)
            "anime" -> setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
            else -> setOf(TvType.Movie, TvType.AnimeMovie)
        }
        return providers().filter { it.supportedTypes.any(wanted::contains) }
    }

    fun providerByName(name: String): MainAPI? = providers().firstOrNull { it.name == name }
}