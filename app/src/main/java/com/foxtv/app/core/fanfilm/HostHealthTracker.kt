package com.foxtv.app.core.fanfilm

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-lived reputation for FanFilm hosting providers.
 *
 * Streaming hosts fail transiently: rate limits, regional blocks, an expired token.
 * Remembering which ones just failed lets the fallback chain skip them for a while
 * instead of retrying the same dead host on every attempt.
 *
 * The important property (AGENTS.md §41) is that a penalty *expires*. Nothing here
 * blacklists permanently: a host that failed three minutes ago is tried again, and a
 * single success clears its record. That keeps a temporary outage from hiding a host
 * for the rest of the session, which is the bug in the reference implementation this
 * replaces.
 */
@Singleton
class HostHealthTracker internal constructor(
    private val clock: () -> Long,
) {
    /**
     * Injected constructor.
     *
     * Declared separately rather than as a default argument: Kotlin compiles a default on
     * the primary constructor into two JVM constructors and Hilt then sees two `@Inject`
     * entry points for the same type. The clock seam stays available to tests through the
     * internal primary constructor.
     */
    @Inject
    constructor() : this(System::currentTimeMillis)


    data class Health(
        val host: String,
        val successes: Int,
        val failures: Int,
        val consecutiveFailures: Int,
        val lastSuccessAtMs: Long,
        val lastFailureAtMs: Long,
        val lastLatencyMs: Long,
        val cooldownUntilMs: Long,
    ) {
        val averageLatencyMs: Long get() = lastLatencyMs
    }

    private val entries = ConcurrentHashMap<String, Health>()

    fun normalize(host: String): String =
        host.trim().lowercase().removePrefix("www.").substringBefore(':')

    /** Record a working host and clear any cooldown. */
    fun recordSuccess(host: String, latencyMs: Long = 0) {
        val key = normalize(host)
        if (key.isEmpty()) return
        entries.compute(key) { _, existing ->
            val now = clock()
            (existing ?: empty(key)).copy(
                successes = (existing?.successes ?: 0) + 1,
                consecutiveFailures = 0,
                lastSuccessAtMs = now,
                lastLatencyMs = latencyMs,
                cooldownUntilMs = 0,
            )
        }
    }

    /**
     * Record a failure and extend the cooldown.
     *
     * The cooldown grows with consecutive failures but is capped, so a host that is
     * genuinely down is skipped quickly while one that hiccuped once is barely
     * penalised.
     */
    fun recordFailure(host: String) {
        val key = normalize(host)
        if (key.isEmpty()) return
        entries.compute(key) { _, existing ->
            val now = clock()
            val streak = (existing?.consecutiveFailures ?: 0) + 1
            val cooldown = cooldownForStreak(streak)
            (existing ?: empty(key)).copy(
                failures = (existing?.failures ?: 0) + 1,
                consecutiveFailures = streak,
                lastFailureAtMs = now,
                cooldownUntilMs = now + cooldown,
            ).also {
                Log.i(TAG, "host $key failed ${streak}x; cooling down for ${cooldown / 1000}s")
            }
        }
    }

    /** Whether [host] is currently cooling down. */
    fun isCoolingDown(host: String): Boolean {
        val entry = entries[normalize(host)] ?: return false
        return entry.cooldownUntilMs > clock()
    }

    fun remainingCooldownMs(host: String): Long {
        val entry = entries[normalize(host)] ?: return 0
        return (entry.cooldownUntilMs - clock()).coerceAtLeast(0)
    }

    fun health(host: String): Health? = entries[normalize(host)]

    fun snapshot(): List<Health> = entries.values.sortedBy { it.host }

    /**
     * Order [hosts] so healthy ones come first.
     *
     * Ranking, best to worst: not cooling down before cooling down, then fewer
     * consecutive failures, then more recent success, then lower latency. Hosts in
     * cooldown are moved to the back rather than removed — if every host is cooling
     * down the user still gets a list to choose from.
     */
    fun <T> rank(hosts: List<T>, hostOf: (T) -> String): List<T> {
        val now = clock()
        return hosts.sortedWith(
            compareBy(
                { entries[normalize(hostOf(it))]?.let { h -> h.cooldownUntilMs > now } ?: false },
                { entries[normalize(hostOf(it))]?.consecutiveFailures ?: 0 },
                { -(entries[normalize(hostOf(it))]?.lastSuccessAtMs ?: 0L) },
                { entries[normalize(hostOf(it))]?.lastLatencyMs ?: 0L },
            )
        )
    }

    /** Forget everything; used when the user explicitly retries a failed search. */
    fun reset() {
        entries.clear()
    }

    private fun empty(host: String) = Health(
        host = host,
        successes = 0,
        failures = 0,
        consecutiveFailures = 0,
        lastSuccessAtMs = 0,
        lastFailureAtMs = 0,
        lastLatencyMs = 0,
        cooldownUntilMs = 0,
    )

    internal fun cooldownForStreak(streak: Int): Long = when {
        streak <= 1 -> FIRST_COOLDOWN_MS
        streak == 2 -> SECOND_COOLDOWN_MS
        else -> MAX_COOLDOWN_MS
    }

    companion object {
        private const val TAG = "FanFilmHostHealth"

        const val FIRST_COOLDOWN_MS = 60_000L
        const val SECOND_COOLDOWN_MS = 180_000L
        const val MAX_COOLDOWN_MS = 600_000L
    }
}
