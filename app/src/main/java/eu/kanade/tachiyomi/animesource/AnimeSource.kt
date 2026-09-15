package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import rx.Observable

/** Binary-compatible source contract hosted by FOX.TV. */
interface AnimeSource {
    val id: Long
    val name: String
    val lang: String
        get() = ""

    suspend fun getAnimeDetails(anime: SAnime): SAnime
    suspend fun getEpisodeList(anime: SAnime): List<SEpisode>
    suspend fun getVideoList(episode: SEpisode): List<Video>

    @Deprecated("Use getAnimeDetails")
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime>

    @Deprecated("Use getEpisodeList")
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>>

    @Deprecated("Use getVideoList")
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>>
}
