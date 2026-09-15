package com.foxtv.app.core.plugin.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalExtensionLoaderLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `owner disable unloads owned identities while preserving built in and shared identities`() {
        val providerRegistry = CloudstreamProviderRegistry()
        val extractorRegistry = ExternalExtractorRegistry()
        val loader = loader(providerRegistry, extractorRegistry)
        val builtInProvider = provider("BuiltIn-${System.nanoTime()}")
        val externalProvider = provider("External-${System.nanoTime()}")
        val builtInExtractor = extractor("BuiltIn", "https://shared-${System.nanoTime()}.invalid")
        val externalExtractor = extractor("External", builtInExtractor.mainUrl)
        insertProvider(builtInProvider)
        insertExtractor(builtInExtractor)
        val beforeExternal = providerRegistry.snapshot()
        insertProvider(externalProvider)

        try {
            providerRegistry.claimNewRegistrations("owner-a", beforeExternal, listOf(externalProvider))
            providerRegistry.claimNewRegistrations(
                "owner-b",
                providerRegistry.snapshot(),
                listOf(externalProvider),
            )
            extractorRegistry.registerExtractor("owner-a", externalExtractor)
            extractorRegistry.registerExtractor("owner-b", externalExtractor)

            loader.setOwnersEnabled(listOf("owner-a"), false)
            assertTrue(isProviderRegistered(externalProvider))
            assertTrue(isExtractorRegistered(externalExtractor))

            loader.setOwnersEnabled(listOf("owner-b"), false)
            assertFalse(isProviderRegistered(externalProvider))
            assertFalse(isExtractorRegistered(externalExtractor))
            assertTrue(isProviderRegistered(builtInProvider))
            assertTrue(isExtractorRegistered(builtInExtractor))
            assertTrue(loader.loadExtension("owner-a").isEmpty())
            assertNull(loader.getApi("owner-b"))
        } finally {
            removeProvider(externalProvider)
            removeProvider(builtInProvider)
            extractorRegistry.removeExact(listOf(externalExtractor))
            removeExtractor(builtInExtractor)
        }
    }

    @Test
    fun `global disable unloads installed owners and re-enable remains lazy`() {
        val providerRegistry = CloudstreamProviderRegistry()
        val extractorRegistry = ExternalExtractorRegistry()
        val loader = loader(providerRegistry, extractorRegistry)
        val ownerId = "global-owner"
        val externalProvider = provider("Global-${System.nanoTime()}")
        val externalExtractor = extractor("Global", "https://global-${System.nanoTime()}.invalid")
        val before = providerRegistry.snapshot()
        insertProvider(externalProvider)

        try {
            providerRegistry.claimNewRegistrations(ownerId, before, listOf(externalProvider))
            extractorRegistry.registerExtractor(ownerId, externalExtractor)

            loader.setGloballyEnabled(false, listOf(ownerId))

            assertFalse(isProviderRegistered(externalProvider))
            assertFalse(isExtractorRegistered(externalExtractor))
            assertTrue(loader.loadExtension(ownerId).isEmpty())
            assertNull(loader.getApi(ownerId))

            loader.setGloballyEnabled(true, listOf(ownerId))
            assertFalse(isProviderRegistered(externalProvider))
            assertFalse(isExtractorRegistered(externalExtractor))
        } finally {
            removeProvider(externalProvider)
            extractorRegistry.removeExact(listOf(externalExtractor))
        }
    }

    @Test
    fun `initial cleartext artifact is rejected before a request`() = runBlocking {
        val httpServer = MockWebServer()
        try {
            val loader = loader(
                CloudstreamProviderRegistry(),
                ExternalExtractorRegistry(),
                OkHttpClient(),
            )
            val plugin = CloudstreamLifecycleTestFixtures.plugin("Cleartext").copy(
                url = httpServer.url("/Cleartext.cs3").toString(),
            )

            val prepared = loader.stageExtension("repo:Cleartext", plugin)

            assertNull(prepared)
            assertTrue(httpServer.requestCount == 0)
        } finally {
            httpServer.close()
        }
    }

    @Test
    fun `HTTPS to HTTP artifact redirect never requests the cleartext target`() =
        withTlsServer { httpsServer, trustedClient ->
            val httpServer = MockWebServer()
            try {
                httpsServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", httpServer.url("/Downgrade.cs3")),
                )
                val loader = loader(
                    CloudstreamProviderRegistry(),
                    ExternalExtractorRegistry(),
                    trustedClient,
                )
                val plugin = CloudstreamLifecycleTestFixtures.plugin("Downgrade").copy(
                    url = httpsServer.url("/Downgrade.cs3").toString(),
                )

                val prepared = runBlocking {
                    loader.stageExtension("repo:Downgrade", plugin)
                }

                assertNull(prepared)
                assertTrue(httpsServer.requestCount == 1)
                assertTrue(httpServer.requestCount == 0)
            } finally {
                httpServer.close()
            }
        }

    private fun loader(
        providerRegistry: CloudstreamProviderRegistry,
        extractorRegistry: ExternalExtractorRegistry,
        client: OkHttpClient = OkHttpClient(),
    ): ExternalExtensionLoader {
        val filesDir = temporaryFolder.newFolder("files-${System.nanoTime()}")
        val codeCacheDir = temporaryFolder.newFolder("code-cache-${System.nanoTime()}")
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.codeCacheDir } returns codeCacheDir
        every { context.classLoader } returns javaClass.classLoader
        return ExternalExtensionLoader(context, extractorRegistry, providerRegistry, client)
    }

    private fun provider(name: String): MainAPI = mockk<MainAPI>(relaxed = true).also {
        every { it.name } returns name
    }

    private fun extractor(name: String, mainUrl: String): ExtractorApi =
        mockk<ExtractorApi>(relaxed = true).also {
            every { it.name } returns name
            every { it.mainUrl } returns mainUrl
        }

    private fun insertProvider(provider: MainAPI) {
        synchronized(APIHolder.allProviders) { APIHolder.allProviders.add(provider) }
        APIHolder.addPluginMapping(provider)
    }

    private fun removeProvider(provider: MainAPI) {
        APIHolder.removePluginMapping(provider)
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.removeAll { it === provider }
        }
    }

    private fun isProviderRegistered(provider: MainAPI): Boolean {
        val inAll = synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.any { it === provider }
        }
        val apis = APIHolder.apis
        val inApis = synchronized(apis) { apis.any { it === provider } }
        return inAll || inApis
    }

    private fun insertExtractor(extractor: ExtractorApi) {
        synchronized(extractorApis) { extractorApis.add(extractor) }
    }

    private fun removeExtractor(extractor: ExtractorApi) {
        synchronized(extractorApis) { extractorApis.removeAll { it === extractor } }
    }

    private fun isExtractorRegistered(extractor: ExtractorApi): Boolean =
        synchronized(extractorApis) { extractorApis.any { it === extractor } }

    private fun withTlsServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        val localhostCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(localhostCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(localhostCertificate.certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .build()

        try {
            block(server, client)
        } finally {
            server.close()
        }
    }
}
