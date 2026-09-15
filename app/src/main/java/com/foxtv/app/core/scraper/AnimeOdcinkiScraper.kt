package com.foxtv.app.core.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Public-catalog discovery adapter for anime-odcinki.pl.
 * It deliberately returns discovery/deep-link capability only; it does not bypass
 * protected players, paywalls or DRM.
 */
class AnimeOdcinkiScraper : StreamScraper {
    override val name: String = "Anime-Odcinki"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun scrape(request: ScraperMediaRequest): List<ScraperStreamResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(request.title, "UTF-8")
        val search = Request.Builder()
            .url("https://anime-odcinki.pl/?s=$q")
            .header("User-Agent", UA)
            .build()
        runCatching {
            client.newCall(search).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<ScraperStreamResult>()
                val doc = Jsoup.parse(response.body?.string().orEmpty(), "https://anime-odcinki.pl")
                val link = doc.select("a[href]")
                    .mapNotNull { a -> a.attr("abs:href").takeIf { it.contains("anime", ignoreCase = true) } }
                    .firstOrNull()
                    ?: return@use emptyList<ScraperStreamResult>()
                listOf(
                    ScraperStreamResult(
                        name = name,
                        title = request.title,
                        description = "Anime-Odcinki public catalog",
                        url = link,
                        capability = "DISCOVERY",
                        sourceLanguage = "pl",
                        subtitleLanguages = listOf("pl"),
                        subAvailable = true,
                        sourceEvidenceUrl = "https://anime-odcinki.pl/"
                    )
                )
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    }
}
