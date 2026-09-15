package com.foxtv.app.core.plugin.aniyomi

import com.foxtv.app.core.plugin.aniyomi.AniyomiContractReference.Kind
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class AniyomiHostAbiContractTest {
    @Test
    fun hostLedgerMatchesPinnedDexBidirectionallyAndEverySymbolResolves() {
        val ledger = AniyomiResearchContracts.referenceLedger("HOST_API_REFERENCES.json")
        val actual = AniyomiDexContractReader.references(AniyomiResearchContracts.apk, ledger.scopePrefixes)

        assertExactReferences(ledger.records, actual)
        assertEquals(20, actual.count { it.kind == Kind.CLASS })
        assertEquals(67, actual.count { it.kind == Kind.METHOD })
        assertEquals(3, actual.count { it.kind == Kind.FIELD })
        assertEquals(90, actual.size)
        actual.sortedBy(AniyomiContractReference::canonical).forEach(AniyomiJvmAbiResolver::requireResolvable)
    }

    @Test
    fun parentClasspathLedgerMatchesPinnedDexBidirectionallyAndEverySymbolResolves() {
        val ledger = AniyomiResearchContracts.referenceLedger("PARENT_CLASSPATH_REFERENCES.json")
        val actual = AniyomiDexContractReader.references(AniyomiResearchContracts.apk, ledger.scopePrefixes)

        assertExactReferences(ledger.records, actual)
        assertEquals(70, actual.count { it.kind == Kind.CLASS })
        assertEquals(137, actual.count { it.kind == Kind.METHOD })
        assertEquals(14, actual.count { it.kind == Kind.FIELD })
        assertEquals(221, actual.size)
        actual.sortedBy(AniyomiContractReference::canonical).forEach(AniyomiJvmAbiResolver::requireResolvable)
    }

    @Test
    fun callSiteLedgerMatchesPinnedDexOffsetsCalleesAndMasksExactly() {
        val ledger = AniyomiResearchContracts.callSiteLedger()
        val targets = ledger.records.mapTo(linkedSetOf()) {
            AniyomiDexContractReader.MethodKey(it.calleeOwner, it.calleeName, it.calleeDescriptor)
        }
        val actual = AniyomiDexContractReader.callSites(AniyomiResearchContracts.apk, targets)

        val missing = ledger.records - actual
        val unexpected = actual - ledger.records
        assertTrue(
            "DEX call-site ledger mismatch\nmissing=${missing.sortedBy { it.codeUnitOffset }}" +
                "\nunexpected=${unexpected.sortedBy { it.codeUnitOffset }}",
            missing.isEmpty() && unexpected.isEmpty(),
        )
        assertEquals(14, actual.size)
        assertEquals(setOf(6, 8, 40), actual.mapTo(linkedSetOf(), AniyomiDexCallSite::mask))
        assertEquals(11, actual.count { it.calleeName == "GET\$default" && it.mask == 6 })
        assertEquals(1, actual.count { it.calleeName == "POST\$default" && it.mask == 8 })
        assertEquals(2, actual.count { it.calleeName == "<init>" && it.mask == 40 })
    }

    @Test
    fun requestDefaultMasksProduceThePinnedArgumentSemantics() {
        val requestsClass = Class.forName("eu.kanade.tachiyomi.network.RequestsKt")
        val getByString = requestsClass.getDeclaredMethod(
            "GET\$default",
            String::class.java,
            Headers::class.java,
            CacheControl::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        val getByHttpUrl = requestsClass.getDeclaredMethod(
            "GET\$default",
            okhttp3.HttpUrl::class.java,
            Headers::class.java,
            CacheControl::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        val post = requestsClass.getDeclaredMethod(
            "POST\$default",
            String::class.java,
            Headers::class.java,
            RequestBody::class.java,
            CacheControl::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )

        val stringGet = getByString.invoke(null, "https://example.test/string", null, null, 6, null) as Request
        val urlGet = getByHttpUrl.invoke(null, "https://example.test/url".toHttpUrl(), null, null, 6, null) as Request
        listOf(stringGet, urlGet).forEach { request ->
            assertEquals("GET", request.method)
            assertEquals(setOf("Cache-Control"), request.headers.names())
            assertEquals(600, request.cacheControl.maxAgeSeconds)
        }

        val headers = Headers.headersOf("X-Aniyomi-Test", "preserved")
        val body = FormBody.Builder().add("key", "value").build()
        val postRequest = post.invoke(
            null,
            "https://example.test/post",
            headers,
            body,
            null,
            8,
            null,
        ) as Request
        assertEquals("POST", postRequest.method)
        assertEquals("preserved", postRequest.header("X-Aniyomi-Test"))
        assertSame(body, postRequest.body)
        assertEquals(600, postRequest.cacheControl.maxAgeSeconds)
    }

    @Test
    fun videoSyntheticConstructorMaskDefaultsHeadersAndAudioButPreservesSubtitles() {
        val constructor = syntheticVideoConstructor()
        val subtitles = listOf(Track("https://example.test/sub.vtt", "pl"))
        val ignoredHeaders = Headers.headersOf("X-Must-Be", "defaulted")
        val ignoredAudio = listOf(Track("https://example.test/audio.m4a", "en"))

        val video = constructor.newInstance(
            "bitrate-12000000",
            "1080p",
            "https://example.test/video.m3u8",
            ignoredHeaders,
            subtitles,
            ignoredAudio,
            40,
            null,
        ) as Video

        assertEquals("bitrate-12000000", video.url)
        assertEquals("1080p", video.quality)
        assertEquals("https://example.test/video.m3u8", video.videoUrl)
        assertNull(video.headers)
        assertSame(subtitles, video.subtitleTracks)
        assertTrue(video.audioTracks.isEmpty())
    }

    private fun syntheticVideoConstructor(): Constructor<*> = Video::class.java.getDeclaredConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        Headers::class.java,
        List::class.java,
        List::class.java,
        Int::class.javaPrimitiveType,
        Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
    )

    private fun assertExactReferences(
        expected: Set<AniyomiContractReference>,
        actual: Set<AniyomiContractReference>,
    ) {
        val missing = (expected - actual).map(AniyomiContractReference::canonical).sorted()
        val unexpected = (actual - expected).map(AniyomiContractReference::canonical).sorted()
        assertTrue(
            "DEX reference ledger mismatch\nmissing=$missing\nunexpected=$unexpected",
            missing.isEmpty() && unexpected.isEmpty(),
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun verifyFixtureIdentityBeforeContractTests() {
            AniyomiResearchContracts.verifyPinnedArtifact()
        }
    }
}
