package com.foxtv.app.core.source

/**
 * Deterministic PL-first ordering for already-implemented providers.
 * Never removes an unknown provider; it only gives verified PL implementations a
 * stable preference when multiple streams compete for the same title.
 */
object PolishSourcePriority {
    private val priorities = linkedMapOf(
        "filman" to 0,
        "cda" to 1,
        "cdahd" to 2,
        "anime-odcinki" to 3,
        "frixysubs" to 4,
        "shinden" to 5,
        "animezone" to 6,
        "docchi" to 7,
        "bajeczki24" to 8,
        "vestroiakr" to 9,
        "maxvod" to 10,
        "zaluknij" to 11,
        "obejrzyj" to 12,
        "ekinotv" to 13,
        "serialevip" to 14,
        "tvp vod" to 15,
        "35mm" to 16,
        "ninateka" to 17,
    )

    fun priority(name: String?): Int {
        val value = name?.trim()?.lowercase().orEmpty()
        return priorities.entries.firstOrNull { value == it.key || value.contains(it.key) }?.value
            ?: Int.MAX_VALUE
    }
}
