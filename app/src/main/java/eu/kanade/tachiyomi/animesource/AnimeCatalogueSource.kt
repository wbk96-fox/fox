package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import rx.Observable

interface AnimeCatalogueSource : AnimeSource {
    override val lang: String
    val supportsLatest: Boolean

    suspend fun getPopularAnime(page: Int): AnimesPage
    suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage
    suspend fun getLatestUpdates(page: Int): AnimesPage
    fun getFilterList(): AnimeFilterList

    @Deprecated("Use getPopularAnime")
    fun fetchPopularAnime(page: Int): Observable<AnimesPage>

    @Deprecated("Use getSearchAnime")
    fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage>

    @Deprecated("Use getLatestUpdates")
    fun fetchLatestUpdates(page: Int): Observable<AnimesPage>
}
