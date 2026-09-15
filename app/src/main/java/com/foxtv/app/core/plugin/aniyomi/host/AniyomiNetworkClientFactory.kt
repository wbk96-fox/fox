package com.foxtv.app.core.plugin.aniyomi.host

import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Creates the isolated normal-TLS client used only by the Aniyomi host API. */
internal class AniyomiNetworkClientFactory(
    private val applicationCacheDirectory: File,
    private val cookieJar: AniyomiCookieJar,
    private val versionNameProvider: () -> String,
) {
    fun createClient(): OkHttpClient {
        val cacheDirectory = File(applicationCacheDirectory, CACHE_DIRECTORY_NAME)
        check((cacheDirectory.isDirectory || cacheDirectory.mkdirs()) && cacheDirectory.isDirectory) {
            "Aniyomi HTTP cache directory is unavailable"
        }
        val userAgent = defaultUserAgentProvider()

        return OkHttpClient.Builder()
            .cache(Cache(cacheDirectory, CACHE_SIZE_BYTES))
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = if (original.header("User-Agent") == null) {
                    original.newBuilder().header("User-Agent", userAgent).build()
                } else {
                    original
                }
                chain.proceed(request)
            }
            .build()
    }

    fun defaultUserAgentProvider(): String =
        "FOX.TV/${versionNameProvider().ifBlank { "dev" }} (Aniyomi host)"

    private companion object {
        const val CACHE_DIRECTORY_NAME = "aniyomi_host_http_cache"
        const val CACHE_SIZE_BYTES = 5L * 1024L * 1024L
    }
}
