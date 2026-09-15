package eu.kanade.tachiyomi.animesource.model

import java.io.Serializable

interface SAnime : Serializable {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var update_strategy: AnimeUpdateStrategy
    var initialized: Boolean

    fun getGenres(): List<String>? = genre
        ?.takeUnless(String::isBlank)
        ?.split(", ")
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.distinct()
        ?.takeIf(List<String>::isNotEmpty)

    fun copy(): SAnime = create().also { copy ->
        copy.url = url
        copy.title = title
        copy.artist = artist
        copy.author = author
        copy.description = description
        copy.genre = genre
        copy.status = status
        copy.thumbnail_url = thumbnail_url
        copy.update_strategy = update_strategy
        copy.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SAnime = SAnimeImpl()
    }
}
