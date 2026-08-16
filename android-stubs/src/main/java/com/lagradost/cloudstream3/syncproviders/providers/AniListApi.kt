package com.lagradost.cloudstream3.syncproviders.providers

/**
 * Minimal stub of the CS3 AniListApi so plugins referencing
 * `AccountManager.Companion.getAniListApi()` can load with the exact
 * expected return type. All operations return defaults; no AniList
 * integration server-side.
 */
class AniListApi {
    fun searchAnime(query: String): Any? = null
    fun getAnimeById(id: Int): Any? = null
    fun getAnimeList(): Any? = emptyList<Any>()
    fun getCurrentUserInfo(): Any? = null
    fun refreshUserInfo(): Any? = null
    fun saveAnime(animeId: Int, save: Boolean) {}
    fun deleteAnime(animeId: Int) {}
    fun markEpisodeAsWatched(animeId: Int, episode: Int) {}

    data class Title(
        val english: String? = null,
        val romaji: String? = null,
        val native: String? = null,
        val userPreferred: String? = null,
    )

    data class CoverImage(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null,
        val color: String? = null,
    )

    data class SeasonNextAiringEpisode(
        val airingAt: Long? = null,
        val timeUntilAiring: Long? = null,
        val episode: Int? = null,
    )

    data class RecommendationConnection(
        val nodes: List<Any>? = null,
    )
}