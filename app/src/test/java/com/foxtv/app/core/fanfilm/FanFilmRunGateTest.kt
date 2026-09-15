package com.foxtv.app.core.fanfilm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is what keeps a rapid second press on an already-loading FanFilm row
 * from superseding (and discarding) the in-flight run — the device symptom was
 * "This FanFilm folder is empty". These are the decisions that must hold.
 */
class FanFilmRunGateTest {

    @Test
    fun `a repeated request for an in-flight URL is refused`() {
        val gate = FanFilmRunGate()
        gate.begin(1, "plugin://plugin.video.fanfilm/movie/trending")

        assertTrue(gate.isInFlightFor("plugin://plugin.video.fanfilm/movie/trending"))
    }

    @Test
    fun `a different URL is allowed while another run is in flight`() {
        val gate = FanFilmRunGate()
        gate.begin(1, "plugin://plugin.video.fanfilm/movie/")

        assertFalse(gate.isInFlightFor("plugin://plugin.video.fanfilm/movie/trending"))
    }

    @Test
    fun `the same URL is allowed again once the run delivered its outcome`() {
        val gate = FanFilmRunGate()
        gate.begin(1, "plugin://plugin.video.fanfilm/movie/trending")
        gate.finish(1)

        assertFalse(gate.isInFlightFor("plugin://plugin.video.fanfilm/movie/trending"))
    }

    @Test
    fun `a terminal outcome from a stale run does not release the active one`() {
        val gate = FanFilmRunGate()
        gate.begin(1, "plugin://plugin.video.fanfilm/movie/")
        gate.begin(2, "plugin://plugin.video.fanfilm/series/")

        gate.finish(1)

        assertTrue(gate.isInFlightFor("plugin://plugin.video.fanfilm/series/"))
        assertFalse(gate.isInFlightFor("plugin://plugin.video.fanfilm/movie/"))
    }

    @Test
    fun `a cancelled run frees its URL immediately`() {
        val gate = FanFilmRunGate()
        gate.begin(1, "plugin://plugin.video.fanfilm/movie/trending")
        gate.cancel(1)

        assertFalse(gate.isInFlightFor("plugin://plugin.video.fanfilm/movie/trending"))
    }

    @Test
    fun `cancelling a stale run leaves the active one untouched`() {
        val gate = FanFilmRunGate()
        gate.begin(1, "plugin://plugin.video.fanfilm/movie/")
        gate.begin(2, "plugin://plugin.video.fanfilm/series/")

        gate.cancel(1)

        assertTrue(gate.isInFlightFor("plugin://plugin.video.fanfilm/series/"))
    }

    @Test
    fun `events not tied to a run always pass the filter`() {
        val gate = FanFilmRunGate()

        assertTrue(gate.accepts(0))

        gate.begin(1, "plugin://plugin.video.fanfilm/movie/")
        assertTrue(gate.accepts(0))
    }

    @Test
    fun `stale run events are rejected after a newer run begins`() {
        val gate = FanFilmRunGate()
        gate.begin(1, "plugin://plugin.video.fanfilm/movie/")
        gate.begin(2, "plugin://plugin.video.fanfilm/series/")

        assertFalse(gate.accepts(1))
        assertTrue(gate.accepts(2))
    }
}