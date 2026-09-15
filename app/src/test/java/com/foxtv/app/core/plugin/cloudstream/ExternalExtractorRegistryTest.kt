package com.foxtv.app.core.plugin.cloudstream

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalExtractorRegistryTest {
    @Test
    fun `shared externally-owned extractor remains until its last owner is removed`() {
        val registry = ExternalExtractorRegistry()
        val extractor = extractor(
            mainUrl = "https://registry-test-${System.nanoTime()}.invalid",
            name = "RegistryTest",
        )

        try {
            registry.registerExtractor("plugin-a", extractor)
            registry.registerExtractor("plugin-b", extractor)
            assertTrue(isRegistered(extractor))

            registry.unregisterOwner("plugin-a")
            assertTrue(isRegistered(extractor))

            registry.unregisterOwner("plugin-b")
            assertFalse(isRegistered(extractor))
        } finally {
            registry.removeExact(listOf(extractor))
        }
    }

    @Test
    fun `unregister never removes a pre-existing built-in extractor identity`() {
        val registry = ExternalExtractorRegistry()
        val extractor = extractor(
            mainUrl = "https://built-in-test-${System.nanoTime()}.invalid",
            name = "BuiltInTest",
        )
        insertGlobally(extractor)

        try {
            registry.registerExtractor("external-plugin", extractor)
            registry.unregisterOwner("external-plugin")
            assertTrue(isRegistered(extractor))
        } finally {
            removeGlobally(extractor)
        }
    }

    @Test
    fun `foreign claims track distinct identities with the same mainUrl independently`() {
        val registry = ExternalExtractorRegistry()
        val sharedUrl = "https://shared-url-${System.nanoTime()}.invalid"
        val first = extractor(sharedUrl, "ForeignA")
        val second = extractor(sharedUrl, "ForeignB")
        insertGlobally(first)
        insertGlobally(second)

        try {
            registry.claimNewRegistrations("plugin-a", listOf(first))
            registry.claimNewRegistrations("plugin-b", listOf(second))

            registry.unregisterOwner("plugin-a")
            assertFalse(isRegistered(first))
            assertTrue(isRegistered(second))

            registry.unregisterOwner("plugin-b")
            assertFalse(isRegistered(second))
        } finally {
            registry.removeExact(listOf(first, second))
        }
    }

    @Test
    fun `built-in and external distinct identities may share the same mainUrl`() {
        val registry = ExternalExtractorRegistry()
        val sharedUrl = "https://built-in-shared-${System.nanoTime()}.invalid"
        val builtIn = extractor(sharedUrl, "BuiltIn")
        val external = extractor(sharedUrl, "External")
        insertGlobally(builtIn)

        try {
            registry.registerExtractor("external-plugin", external)
            assertTrue(isRegistered(builtIn))
            assertTrue(isRegistered(external))

            registry.unregisterOwner("external-plugin")
            assertTrue(isRegistered(builtIn))
            assertFalse(isRegistered(external))
        } finally {
            registry.removeExact(listOf(external))
            removeGlobally(builtIn)
        }
    }

    private fun extractor(mainUrl: String, name: String): ExtractorApi {
        val extractor = mockk<ExtractorApi>(relaxed = true)
        every { extractor.mainUrl } returns mainUrl
        every { extractor.name } returns name
        return extractor
    }

    private fun insertGlobally(extractor: ExtractorApi) {
        synchronized(extractorApis) { extractorApis.add(extractor) }
    }

    private fun removeGlobally(extractor: ExtractorApi) {
        synchronized(extractorApis) { extractorApis.removeAll { it === extractor } }
    }

    private fun isRegistered(extractor: ExtractorApi): Boolean = synchronized(extractorApis) {
        extractorApis.any { it === extractor }
    }
}
