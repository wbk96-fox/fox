package eu.kanade.tachiyomi.animesource

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Parent-owned synchronization point used only by the controlled M3 child artifact. */
object AniyomiLoadTestProbe {
    @Volatile
    private var entered = CountDownLatch(1)

    @Volatile
    private var blocker = CountDownLatch(1)

    private val interrupted = AtomicInteger(0)

    @JvmStatic
    fun reset() {
        entered = CountDownLatch(1)
        blocker = CountDownLatch(1)
        interrupted.set(0)
    }

    @JvmStatic
    fun blockUntilInterrupted() {
        entered.countDown()
        try {
            blocker.await()
            error("Controlled Aniyomi load blocker was released without cancellation")
        } catch (error: InterruptedException) {
            interrupted.incrementAndGet()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Controlled Aniyomi initialization was interrupted", error)
        }
    }

    fun awaitEntered(): Boolean = entered.await(10, TimeUnit.SECONDS)

    fun interruptionCount(): Int = interrupted.get()
}
