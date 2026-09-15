package com.foxtv.app.core.fanfilm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of host health is that penalties *expire*. A tracker that never forgets is
 * how a host that hiccuped once disappears for the rest of the session.
 */
class HostHealthTrackerTest {

    private var now = 1_000_000L
    private val tracker = HostHealthTracker(clock = { now })

    @Test
    fun `a fresh host is not cooling down`() {
        assertFalse(tracker.isCoolingDown("cda.pl"))
    }

    @Test
    fun `a failure starts a cooldown that expires`() {
        tracker.recordFailure("cda.pl")
        assertTrue(tracker.isCoolingDown("cda.pl"))

        now += HostHealthTracker.FIRST_COOLDOWN_MS - 1
        assertTrue(tracker.isCoolingDown("cda.pl"))

        now += 2
        assertFalse("the cooldown must expire on its own", tracker.isCoolingDown("cda.pl"))
    }

    @Test
    fun `consecutive failures lengthen the cooldown up to a cap`() {
        assertEquals(HostHealthTracker.FIRST_COOLDOWN_MS, tracker.cooldownForStreak(1))
        assertEquals(HostHealthTracker.SECOND_COOLDOWN_MS, tracker.cooldownForStreak(2))
        assertEquals(HostHealthTracker.MAX_COOLDOWN_MS, tracker.cooldownForStreak(3))
        assertEquals(HostHealthTracker.MAX_COOLDOWN_MS, tracker.cooldownForStreak(25))
    }

    @Test
    fun `a success clears the cooldown and the failure streak`() {
        tracker.recordFailure("streamtape.com")
        tracker.recordFailure("streamtape.com")
        assertTrue(tracker.isCoolingDown("streamtape.com"))

        tracker.recordSuccess("streamtape.com", latencyMs = 250)

        assertFalse(tracker.isCoolingDown("streamtape.com"))
        val health = tracker.health("streamtape.com")!!
        assertEquals(0, health.consecutiveFailures)
        assertEquals(250L, health.lastLatencyMs)
    }

    @Test
    fun `host names are normalised so www and ports do not create separate records`() {
        tracker.recordFailure("WWW.CDA.PL:443")
        assertTrue(tracker.isCoolingDown("cda.pl"))
        assertEquals("cda.pl", tracker.health("cda.pl")?.host)
    }

    @Test
    fun `ranking puts cooling down hosts last without dropping them`() {
        tracker.recordSuccess("good.example", latencyMs = 100)
        tracker.recordFailure("bad.example")

        val ranked = tracker.rank(listOf("bad.example", "unknown.example", "good.example")) { it }

        assertEquals(3, ranked.size)
        assertEquals("bad.example", ranked.last())
        assertTrue(ranked.first() in setOf("good.example", "unknown.example"))
    }

    @Test
    fun `blank host names are ignored`() {
        tracker.recordFailure("")
        tracker.recordSuccess("   ")
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `reset forgets everything`() {
        tracker.recordFailure("a.example")
        tracker.reset()
        assertFalse(tracker.isCoolingDown("a.example"))
        assertTrue(tracker.snapshot().isEmpty())
    }
}
