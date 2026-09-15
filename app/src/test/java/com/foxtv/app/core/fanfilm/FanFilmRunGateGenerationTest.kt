package com.foxtv.app.core.fanfilm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FanFilmRunGateGenerationTest {
    @Test fun staleGenerationIsRejected() {
        val gate = FanFilmRunGate()
        gate.begin(1L, 10, "plugin://a")
        assertTrue(gate.accepts(1L, 10, "plugin://a"))
        assertFalse(gate.accepts(2L, 10, "plugin://a"))
        assertFalse(gate.accepts(1L, 11, "plugin://a"))
        assertFalse(gate.accepts(1L, 10, "plugin://b"))
    }

    @Test fun sameUrlInFlightIsBlocked() {
        val gate = FanFilmRunGate()
        gate.begin(1L, 10, "plugin://a")
        assertTrue(gate.isInFlightFor("plugin://a"))
        assertFalse(gate.isInFlightFor("plugin://b"))
        gate.finish(10)
        assertFalse(gate.isInFlightFor("plugin://a"))
    }
}
