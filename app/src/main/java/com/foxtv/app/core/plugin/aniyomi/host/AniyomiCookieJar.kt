package com.foxtv.app.core.plugin.aniyomi.host

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Bounded in-memory cookie storage with OkHttp's RFC-aware request matching. */
internal class AniyomiCookieJar(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : CookieJar {
    init {
        require(capacity > 0) { "Cookie capacity must be positive" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<CookieIdentity, StoredCookie>()
    private var sequence = 0L

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = clock()
        synchronized(lock) {
            removeExpired(now)
            cookies.forEach { cookie ->
                val identity = CookieIdentity.from(cookie)
                if (cookie.expiresAt <= now) {
                    entries.remove(identity)
                } else if (cookie.matches(url)) {
                    entries[identity] = StoredCookie(cookie, sequence++)
                }
            }
            while (entries.size > capacity) {
                val oldest = entries.minByOrNull { it.value.sequence }?.key ?: break
                entries.remove(oldest)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clock()
        return synchronized(lock) {
            removeExpired(now)
            entries.values
                .asSequence()
                .map(StoredCookie::cookie)
                .filter { it.matches(url) }
                .toList()
        }
    }

    private fun removeExpired(now: Long) {
        entries.entries.removeAll { it.value.cookie.expiresAt <= now }
    }

    private data class StoredCookie(
        val cookie: Cookie,
        val sequence: Long,
    )

    private data class CookieIdentity(
        val name: String,
        val domain: String,
        val path: String,
        val hostOnly: Boolean,
    ) {
        companion object {
            fun from(cookie: Cookie): CookieIdentity = CookieIdentity(
                name = cookie.name,
                domain = cookie.domain.lowercase(),
                path = cookie.path,
                hostOnly = cookie.hostOnly,
            )
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
