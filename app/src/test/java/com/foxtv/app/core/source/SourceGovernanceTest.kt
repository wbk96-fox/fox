package com.foxtv.app.core.source

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceGovernanceTest {
    @Test fun verifiedRegistryContainsCorePolishEntries() {
        assertNotNull(SourceGovernanceRegistry.records.firstOrNull { it.id == "cda" })
        assertNotNull(SourceGovernanceRegistry.records.firstOrNull { it.id == "filman" })
        assertNotNull(SourceGovernanceRegistry.records.firstOrNull { it.id == "anime-odcinki" })
    }

    @Test fun unknownProviderCannotBecomePlaybackByDefault() {
        assertFalse(SourceGovernanceRegistry.allowsPlayback("unknown-provider"))
    }

    @Test fun screenshotCatalogContainsP2PIndexesAsDiscoveryOnly() {
        for (name in listOf("BTDig", "CinemaMovies", "Devil-Torrents", "TorrentLeech")) {
            val record = SourceGovernanceRegistry.catalogRecord(name)
            assertNotNull(record)
            assertTrue(!record.capabilities.contains(SourceCapability.VERIFIED_IN_APP_MEDIA))
            assertFalse(SourceGovernanceRegistry.allowsPlayback(name))
        }
    }

    @Test fun implementedPolishProviderCanUseInAppPlaybackCapability() {
        assertTrue(SourceGovernanceRegistry.allowsPlayback("AnimeZone"))
        assertTrue(SourceGovernanceRegistry.allowsDiscovery("Anime-Odcinki"))
    }
}
