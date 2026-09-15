package com.foxtv.app.core.plugin.aniyomi

import com.foxtv.app.core.plugin.aniyomi.host.AniyomiCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniyomiCookieJarTest {
    @Test
    fun domainPathAndSecureRulesUseOkHttpRfcMatching() {
        var now = 1_700_000_000_000L
        val jar = AniyomiCookieJar(clock = { now })
        val responseUrl = "https://api.example.test/library/login".toHttpUrl()
        jar.saveFromResponse(
            responseUrl,
            listOf(
                cookie("domain", "wide", "example.test", "/", now, hostOnly = false),
                cookie("host", "exact", "api.example.test", "/", now, hostOnly = true),
                cookie("path", "library", "api.example.test", "/library", now, hostOnly = true),
                cookie("secure", "yes", "api.example.test", "/", now, hostOnly = true, secure = true),
            ),
        )

        assertEquals(
            setOf("domain", "host", "path", "secure"),
            jar.loadForRequest("https://api.example.test/library/items".toHttpUrl()).mapTo(linkedSetOf(), { cookie: Cookie -> cookie.name }),
        )
        assertEquals(
            setOf("domain"),
            jar.loadForRequest("https://child.example.test/library/items".toHttpUrl()).mapTo(linkedSetOf(), { cookie: Cookie -> cookie.name }),
        )
        assertEquals(
            setOf("domain", "host"),
            jar.loadForRequest("http://api.example.test/other".toHttpUrl()).mapTo(linkedSetOf(), { cookie: Cookie -> cookie.name }),
        )
    }

    @Test
    fun replacementIdentityIncludesHostOnlyAndExpiredCookiesAreRemoved() {
        var now = 1_700_000_000_000L
        val jar = AniyomiCookieJar(clock = { now })
        val url = "https://api.example.test/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(
                cookie("session", "first", "api.example.test", "/", now, hostOnly = true),
                cookie("session", "domain", "api.example.test", "/", now, hostOnly = false),
            ),
        )
        jar.saveFromResponse(
            url,
            listOf(cookie("session", "replacement", "api.example.test", "/", now, hostOnly = true)),
        )

        assertEquals(
            setOf("replacement", "domain"),
            jar.loadForRequest(url).mapTo(linkedSetOf(), { cookie: Cookie -> cookie.value }),
        )

        now += 61_000L
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun deletionCookieRemovesMatchingIdentityImmediately() {
        val now = 1_700_000_000_000L
        val jar = AniyomiCookieJar(clock = { now })
        val url = "https://api.example.test/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(cookie("session", "active", "api.example.test", "/", now, hostOnly = true)),
        )
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder()
                    .name("session")
                    .value("deleted")
                    .hostOnlyDomain("api.example.test")
                    .path("/")
                    .expiresAt(now)
                    .build(),
            ),
        )

        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun capacityEvictsOldestStoredIdentityWithoutUnboundedGrowth() {
        val now = 1_700_000_000_000L
        val jar = AniyomiCookieJar(capacity = 2, clock = { now })
        val url = "https://api.example.test/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(
                cookie("first", "1", "api.example.test", "/", now, hostOnly = true),
                cookie("second", "2", "api.example.test", "/", now, hostOnly = true),
                cookie("third", "3", "api.example.test", "/", now, hostOnly = true),
            ),
        )

        assertEquals(
            listOf("second", "third"),
            jar.loadForRequest(url).map({ cookie: Cookie -> cookie.name }),
        )
    }

    @Test
    fun responseCannotStoreCookieThatDoesNotMatchItsOrigin() {
        val now = 1_700_000_000_000L
        val jar = AniyomiCookieJar(clock = { now })
        val url = "https://api.example.test/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(cookie("foreign", "value", "unrelated.test", "/", now, hostOnly = true)),
        )

        assertTrue(jar.loadForRequest("https://unrelated.test/".toHttpUrl()).isEmpty())
    }

    private fun cookie(
        name: String,
        value: String,
        domain: String,
        path: String,
        now: Long,
        hostOnly: Boolean,
        secure: Boolean = false,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            path(path)
            expiresAt(now + 60_000L)
            if (secure) secure()
        }
        .build()
}
