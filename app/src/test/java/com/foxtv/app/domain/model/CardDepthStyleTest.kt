package com.foxtv.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDepthStyleTest {
    @Test
    fun disabledStyleDisablesEverySurface() {
        val style = CardDepthStyle()

        CardDepthSurface.entries.forEach { surface ->
            assertFalse(style.isEnabledFor(surface))
        }
    }

    @Test
    fun enabledStyleRespectsIndependentSurfaceToggles() {
        val style = CardDepthStyle(enabled = true, castEnabled = false)

        assertTrue(style.isEnabledFor(CardDepthSurface.POSTERS))
        assertFalse(style.isEnabledFor(CardDepthSurface.CAST))
    }
}
