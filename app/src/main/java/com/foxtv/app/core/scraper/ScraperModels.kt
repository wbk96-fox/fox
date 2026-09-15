package com.foxtv.app.core.scraper

import com.foxtv.app.domain.model.ProxyHeaders
import com.foxtv.app.domain.model.Stream
import com.foxtv.app.domain.model.StreamBehaviorHints

data class ScraperMediaRequest(
    val type: String, // "movie", "tv", "series"
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null
) {
    val isMovie: Boolean get() = type.equals("movie", ignoreCase = true)
    val isSeries: Boolean get() = type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

data class ScraperStreamResult(
    val name: String = "FoxTvHTTP",
    val title: String,
    val description: String? = null,
    val url: String,
    val quality: String? = null,
    val headers: Map<String, String>? = null,
    val behaviorHints: Map<String, Any?>? = null,
    val audioLanguage: String? = null,
    val subtitleLanguages: List<String> = emptyList(),
    val dubAvailable: Boolean? = null,
    val subAvailable: Boolean? = null,
    val originalLanguage: String? = null,
    val sourceLanguage: String? = null,
    val releaseLanguage: String? = null,
    val capability: String? = null,
    val sourceEvidenceUrl: String? = null
) {
    fun toDomainStream(): Stream {
        val finalHeaders = headers ?: emptyMap()
        return Stream(
            name = name,
            title = title,
            description = description,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = if (finalHeaders.isNotEmpty()) {
                    ProxyHeaders(request = finalHeaders, response = null)
                } else null
            ),
            addonName = "FoxTvHTTP",
            addonLogo = null,
            quality = quality,
            audioLanguage = audioLanguage,
            subtitleLanguages = subtitleLanguages,
            dubAvailable = dubAvailable,
            subAvailable = subAvailable,
            originalLanguage = originalLanguage,
            sourceLanguage = sourceLanguage,
            releaseLanguage = releaseLanguage,
            capability = capability,
            sourceEvidenceUrl = sourceEvidenceUrl
        )
    }
}

interface StreamScraper {
    val name: String get() = "FoxTvHTTP"
    suspend fun scrape(request: ScraperMediaRequest): List<ScraperStreamResult>
}
