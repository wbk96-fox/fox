package com.foxtv.app.core.debrid

import com.foxtv.app.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersTest {

    @Test
    fun `configured services include every provider with a saved api key`() {
        // All five registered providers (Torbox, Premiumize, Real-Debrid, AllDebrid,
        // Debrid-Link) are visibleInUi = true; DebridSettingsScreen renders an API key row
        // for each of them via DebridProviders.visible(). A provider is only excluded from
        // configuredServices() by having no saved key, never by visibility.
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            realDebridApiKey = "rd"
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(
            setOf(DebridProviders.Torbox, DebridProviders.RealDebrid),
            services.map { it.provider }.toSet()
        )
        assertTrue(DebridProviders.isVisible(DebridProviders.TORBOX_ID))
        assertTrue(DebridProviders.isVisible(DebridProviders.REAL_DEBRID_ID))
    }

    @Test
    fun `configured services exclude providers without a saved api key`() {
        val settings = DebridSettings(enabled = true, torboxApiKey = "tb")

        val services = DebridProviders.configuredServices(settings)

        assertEquals(listOf(DebridProviders.Torbox), services.map { it.provider })
    }

    @Test
    fun `every registered provider is visible in the UI`() {
        // DebridSettingsScreen iterates DebridProviders.visible() to build the API key list;
        // a provider with visibleInUi = false would silently disappear from that screen with
        // no way for the user to configure it.
        val ids = listOf(
            DebridProviders.TORBOX_ID,
            DebridProviders.PREMIUMIZE_ID,
            DebridProviders.REAL_DEBRID_ID,
            DebridProviders.ALL_DEBRID_ID,
            DebridProviders.DEBRID_LINK_ID
        )
        ids.forEach { id -> assertTrue("$id should be visible", DebridProviders.isVisible(id)) }
        assertEquals(ids.size, DebridProviders.visible().size)
    }
}
