package com.foxtv.app.core.plugin.cloudstream

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudstreamProviderRegistryTest {
    @Test
    fun `rollback removes delta from all provider views and rebuilds apiMap`() {
        val registry = CloudstreamProviderRegistry()
        val provider = provider("Rollback-${System.nanoTime()}")
        val before = registry.snapshot()
        insertIntoBothViews(provider)

        try {
            assertTrue(isInAllProviders(provider))
            assertTrue(isInApis(provider))
            assertApiMapPointsTo(provider)

            registry.removeRegistrationsAddedAfter(before)

            assertFalse(isInAllProviders(provider))
            assertFalse(isInApis(provider))
            assertFalse(APIHolder.apiMap.orEmpty().containsKey(provider.name))
        } finally {
            removeFromBothViews(provider)
        }
    }

    @Test
    fun `snapshot union protects pre-existing identities from either APIHolder view`() {
        val registry = CloudstreamProviderRegistry()
        val allProvidersOnly = provider("AllOnly-${System.nanoTime()}")
        val apisOnly = provider("ApisOnly-${System.nanoTime()}")
        insertIntoAllProviders(allProvidersOnly)
        APIHolder.addPluginMapping(apisOnly)
        val before = registry.snapshot()

        try {
            registry.claimNewRegistrations(
                ownerId = "external-plugin",
                before = before,
                declaredProviders = listOf(allProvidersOnly, apisOnly),
            )
            registry.unregisterOwner("external-plugin")

            assertTrue(isInAllProviders(allProvidersOnly))
            assertTrue(isInApis(apisOnly))
            assertApiMapPointsTo(apisOnly)
        } finally {
            removeFromBothViews(allProvidersOnly)
            removeFromBothViews(apisOnly)
        }
    }

    @Test
    fun `declared built-in remains while a distinct external identity is cleaned`() {
        val registry = CloudstreamProviderRegistry()
        val builtIn = provider("BuiltIn-${System.nanoTime()}")
        val external = provider("External-${System.nanoTime()}")
        insertIntoBothViews(builtIn)
        val before = registry.snapshot()
        insertIntoBothViews(external)

        try {
            registry.claimNewRegistrations(
                ownerId = "external-plugin",
                before = before,
                declaredProviders = listOf(builtIn, external),
            )
            registry.unregisterOwner("external-plugin")

            assertTrue(isInAllProviders(builtIn))
            assertTrue(isInApis(builtIn))
            assertApiMapPointsTo(builtIn)
            assertFalse(isInAllProviders(external))
            assertFalse(isInApis(external))
            assertFalse(APIHolder.apiMap.orEmpty().containsKey(external.name))
        } finally {
            removeFromBothViews(external)
            removeFromBothViews(builtIn)
        }
    }

    @Test
    fun `shared external provider remains until its last owner is removed`() {
        val registry = CloudstreamProviderRegistry()
        val provider = provider("Shared-${System.nanoTime()}")
        val beforeFirstOwner = registry.snapshot()
        insertIntoBothViews(provider)

        try {
            registry.claimNewRegistrations(
                ownerId = "plugin-a",
                before = beforeFirstOwner,
                declaredProviders = listOf(provider),
            )
            registry.claimNewRegistrations(
                ownerId = "plugin-b",
                before = registry.snapshot(),
                declaredProviders = listOf(provider),
            )

            registry.unregisterOwner("plugin-a")
            assertTrue(isInAllProviders(provider))
            assertTrue(isInApis(provider))
            assertApiMapPointsTo(provider)

            registry.unregisterOwner("plugin-b")
            assertFalse(isInAllProviders(provider))
            assertFalse(isInApis(provider))
            assertFalse(APIHolder.apiMap.orEmpty().containsKey(provider.name))
        } finally {
            removeFromBothViews(provider)
        }
    }

    private fun provider(name: String): MainAPI {
        val provider = mockk<MainAPI>(relaxed = true)
        every { provider.name } returns name
        return provider
    }

    private fun insertIntoBothViews(provider: MainAPI) {
        insertIntoAllProviders(provider)
        APIHolder.addPluginMapping(provider)
    }

    private fun insertIntoAllProviders(provider: MainAPI) {
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.add(provider)
        }
    }

    private fun removeFromBothViews(provider: MainAPI) {
        APIHolder.removePluginMapping(provider)
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.removeAll { it === provider }
        }
    }

    private fun isInAllProviders(provider: MainAPI): Boolean =
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.any { it === provider }
        }

    private fun isInApis(provider: MainAPI): Boolean {
        val apis = APIHolder.apis
        return synchronized(apis) { apis.any { it === provider } }
    }

    private fun assertApiMapPointsTo(provider: MainAPI) {
        val apis = APIHolder.apis
        val expectedIndex = synchronized(apis) { apis.indexOfLast { it === provider } }
        assertTrue(expectedIndex >= 0)
        assertEquals(expectedIndex, APIHolder.apiMap?.get(provider.name))
    }
}
