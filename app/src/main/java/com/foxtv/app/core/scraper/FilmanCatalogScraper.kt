package com.foxtv.app.core.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Public-catalog discovery adapter for Filman. It intentionally exposes only
 * catalog/deep-link capability; protected embeds are left to their authorized resolver path.
 */
class FilmanCatalogScraper : StreamScraper {
    override val name: String = "Filman"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun scrape(request: ScraperMediaRequest): List<ScraperStreamResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(request.title, "UTF-8")
        val req = Request.Builder()
            .url("https://filman.cc/?s=$q")
            .header("User-Agent", UA)
            .build()
        runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<ScraperStreamResult>()
                val doc = Jsoup.parse(response.body?.string().orEmpty(), "https://filman.cc")
                val links = doc.select("a[href]")
                    .mapNotNull { it.attr("abs:href") }
                    .filter { href -> href.startsWith("https://filman.cc/") && href.count { it == '/' } >= 3 }
                    .distinct()
                    .take(5)
                links.map { href ->
                    ScraperStreamResult(
                        name = name,
                        title = request.title,
                        description = "Filman public catalog",
                        url = href,
                        capability = "DISCOVERY",
                        audioLanguage = "pl",
                        sourceLanguage = "pl",
                        dubAvailable = true,
                        subAvailable = true,
                        sourceEvidenceUrl = "https://filman.cc/"
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    }
}
