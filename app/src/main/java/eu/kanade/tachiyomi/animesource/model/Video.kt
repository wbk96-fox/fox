package eu.kanade.tachiyomi.animesource.model

import okhttp3.Headers

data class Track(val url: String, val lang: String)

open class Video(
    val url: String = "",
    val quality: String = "",
    var videoUrl: String? = null,
    val headers: Headers? = null,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
) {
    @Volatile
    var status: State = State.QUEUE

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }
}
