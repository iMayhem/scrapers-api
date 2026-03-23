package com.moovie.plugins

/** Container for data fetched during MALSync requests */
data class MalSyncData(
    val title: String?,
    val zorotitle: String?,
    val hianimeurl: String?,
    val animepaheUrl: String?,
    val aniId: Int?,
    val episode: Int?,
    val year: Int?,
    val origin: String
)

/** * Defines a provider and its execution logic for Standard, Anime, and MALSync data.
 */
data class ProviderDef(
    val key: String,
    val displayName: String,
    val isTorrent: Boolean = false,
    val executeStandard: (suspend (res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeAnime: (suspend (res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeMalSync: (suspend (data: MalSyncData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null
)

object ProviderRegistry {

    val builtInProviders = listOf(
        // ── Direct HTTP Providers ─────────────────────────────────
        ProviderDef(
            key = "p_moviebox", displayName = "Moviebox",
            executeStandard = { res, subCb, cb -> CineStreamExtractors.invokeMoviebox(res.title, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> CineStreamExtractors.invokeMoviebox(res.imdbTitle, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        )
    )

    val keys get() = builtInProviders.map { it.key }
    val namesMap get() = builtInProviders.associate { it.key to it.displayName }
    val torrentKeys get() = builtInProviders.filter { it.isTorrent }.map { it.key }.toSet()
}
