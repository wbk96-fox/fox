package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/** Parent-owned network services exposed to Aniyomi sources. */
class NetworkHelper internal constructor(
    val client: OkHttpClient,
    private val userAgentProvider: () -> String,
) {
    fun defaultUserAgentProvider(): String = userAgentProvider()
}
