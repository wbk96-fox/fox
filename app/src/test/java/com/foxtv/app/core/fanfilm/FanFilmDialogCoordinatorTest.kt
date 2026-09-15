package com.foxtv.app.core.fanfilm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dialog bridge parks a Python worker thread until a human answers, so the properties
 * that matter are the ones that guarantee it always gets *an* answer: correlation, cancel,
 * stale-run rejection and timeout.
 */
class FanFilmDialogCoordinatorTest {

    private val coordinator = FanFilmDialogCoordinator()
    private val workers = Executors.newFixedThreadPool(4)

    @After
    fun tearDown() {
        coordinator.cancelAll()
        workers.shutdownNow()
    }

    private fun state(envelope: String) = JSONObject(envelope).optString("state")
    private fun value(envelope: String) = JSONObject(envelope).opt("value")

    private fun awaitAsync(
        runId: Int,
        kind: FanFilmDialogCoordinator.Kind,
        options: List<String> = emptyList(),
        timeoutMs: Long = 5_000,
    ): java.util.concurrent.Future<String> = workers.submit<String> {
        coordinator.await(
            runId = runId,
            kind = kind,
            heading = "heading",
            options = options,
            timeoutMs = timeoutMs,
        )
    }

    @Test
    fun `an answered select returns the chosen index`() {
        val pending = awaitAsync(1, FanFilmDialogCoordinator.Kind.SELECT, listOf("a", "b", "c"))
        val request = waitForVisible()

        coordinator.respond(request.id, 2)

        val envelope = pending.get(5, TimeUnit.SECONDS)
        assertEquals(FanFilmDialogCoordinator.STATE_OK, state(envelope))
        assertEquals(2, value(envelope))
    }

    @Test
    fun `a dismissal reports cancelled rather than a false answer`() {
        val pending = awaitAsync(1, FanFilmDialogCoordinator.Kind.YES_NO)
        val request = waitForVisible()

        coordinator.dismiss(request.id)

        val envelope = pending.get(5, TimeUnit.SECONDS)
        assertEquals(FanFilmDialogCoordinator.STATE_CANCELLED, state(envelope))
    }

    @Test
    fun `a response for a different id is ignored`() {
        val pending = awaitAsync(1, FanFilmDialogCoordinator.Kind.INPUT, timeoutMs = 1_500)
        val request = waitForVisible()

        coordinator.respond(request.id + 999, "wrong")

        // The real dialog is still waiting, and eventually times out rather than picking up
        // the foreign answer.
        val envelope = pending.get(5, TimeUnit.SECONDS)
        assertEquals(FanFilmDialogCoordinator.STATE_TIMEOUT, state(envelope))
    }

    @Test
    fun `cancelling a run releases its waiter immediately`() {
        val pending = awaitAsync(7, FanFilmDialogCoordinator.Kind.SELECT, listOf("a"))
        waitForVisible()

        coordinator.cancelRun(7)

        val envelope = pending.get(2, TimeUnit.SECONDS)
        assertEquals(FanFilmDialogCoordinator.STATE_STALE, state(envelope))
    }

    @Test
    fun `a request from an already retired run is refused without showing a dialog`() {
        coordinator.cancelRun(11)
        val envelope = coordinator.await(
            runId = 11,
            kind = FanFilmDialogCoordinator.Kind.OK,
            heading = "heading",
            timeoutMs = 500,
        )
        assertEquals(FanFilmDialogCoordinator.STATE_STALE, state(envelope))
    }

    @Test
    fun `a timeout resolves instead of blocking forever`() {
        val envelope = coordinator.await(
            runId = 1,
            kind = FanFilmDialogCoordinator.Kind.OK,
            heading = "heading",
            timeoutMs = 250,
        )
        assertEquals(FanFilmDialogCoordinator.STATE_TIMEOUT, state(envelope))
    }

    @Test
    fun `concurrent requests are queued and each gets its own answer`() {
        val first = awaitAsync(1, FanFilmDialogCoordinator.Kind.SELECT, listOf("first"))
        val head = waitForVisible()
        val second = awaitAsync(1, FanFilmDialogCoordinator.Kind.SELECT, listOf("second"))

        // Only one dialog is shown at a time.
        assertEquals(head.id, coordinator.visible.value?.id)

        coordinator.respond(head.id, 0)
        assertEquals(FanFilmDialogCoordinator.STATE_OK, state(first.get(5, TimeUnit.SECONDS)))

        val next = waitForVisible()
        assertTrue("the queued request must surface next", next.id != head.id)
        coordinator.respond(next.id, 0)
        assertEquals(FanFilmDialogCoordinator.STATE_OK, state(second.get(5, TimeUnit.SECONDS)))
    }

    @Test
    fun `nothing is left pending after every request resolves`() {
        val pending = awaitAsync(1, FanFilmDialogCoordinator.Kind.OK)
        val request = waitForVisible()
        coordinator.respond(request.id, true)
        pending.get(5, TimeUnit.SECONDS)

        // Cleanup is what keeps the map from growing across a long session.
        assertEquals(null, coordinator.visible.value)
    }

    private fun waitForVisible(): FanFilmDialogCoordinator.Request {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            coordinator.visible.value?.let { return it }
            Thread.sleep(10)
        }
        error("no dialog became visible")
    }
}
