package com.foxtv.app.core.source

import kotlin.test.Test
import kotlin.test.assertTrue

class PolishSourcePriorityTest {
    @Test
    fun implementedPolishProvidersRankBeforeUnknown() {
        assertTrue(PolishSourcePriority.priority("Filman") < PolishSourcePriority.priority("SomeForeignScraper"))
        assertTrue(PolishSourcePriority.priority("AnimeZone") < PolishSourcePriority.priority("VidSrc"))
    }
}
