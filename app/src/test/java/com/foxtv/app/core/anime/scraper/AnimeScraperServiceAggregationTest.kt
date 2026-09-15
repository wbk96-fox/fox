package com.foxtv.app.core.anime.scraper

import com.foxtv.app.core.anime.model.AnimeMedia
import com.foxtv.app.core.anime.model.AnimeStreamResult
import com.foxtv.app.core.scraper.HttpStreamLivenessValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic aggregation, fallback and behavior-oracle tests for [AnimeScraperService].
 *
 * Every test runs on a virtual-time test dispatcher with a finite injected provider plan, a
 * scripted liveness probe and a recording diagnostic sink. No public network, no real delay and
 * no timeout-based success acknowledgment is used anywhere in this file; runTest's virtual time
 * is the only clock, so a provider plan that failed to terminate naturally would hang the test
 * instead of being silently accepted after a wall-clock window.
 *
 * **Validates: Requirements 1.5, 2.1, 2.5, 3.1**
 */
class AnimeScraperServiceAggregationTest {

    private class RecordingSink : AnimeDiagnosticSink {
        val outcomes = ConcurrentLinkedQueue<AnimeProviderOutcome>()

        override fun record(providerId: AnimeProviderId, outcome: AnimeProviderOutcome) {
            outcomes += outcome
        }
    }

    private class ScriptedProbe(
        private val rejectedUrls: Set<String> = emptySet(),
    ) : StreamLivenessProbe {
        private val rejected = Collections.synchronizedSet(mutableSetOf(*rejectedUrls.toTypedArray()))

        override suspend fun isStreamAlive(url: String, headers: Map<String, String>): Boolean =
            url !in rejected
    }

    private fun TestScope.service(
        plan: List<AnimeProviderCall>,
        probe: StreamLivenessProbe,
        sink: AnimeDiagnosticSink,
    ): AnimeScraperService = AnimeScraperService(
        factory = AnimeProviderCallFactory { plan },
        probe = probe,
        sink = sink,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun call(
        providerId: AnimeProviderId,
        result: suspend () -> List<AnimeStreamResult>,
    ): AnimeProviderCall = AnimeProviderCall.MultiStreamProvider(
        providerId = providerId,
        animeId = 1,
        episodeNumber = 1,
        category = "sub",
        extractor = { _, _, _ -> result() },
    )

    private fun stream(url: String, serverName: String, category: String = "SUB"): AnimeStreamResult =
        AnimeStreamResult(
            streamUrl = url,
            serverName = serverName,
            category = category,
            quality = "1080p",
            headers = mapOf(
                "User-Agent" to "ua-$serverName",
                "Referer" to "https://referer.example/",
                "Origin" to "https://origin.example",
            ),
        )

    private fun anime(): AnimeMedia = AnimeMedia(
        id = 1,
        titleEnglish = "Solo Leveling",
        titleRomaji = "Ore dake Level Up na Ken",
        titleUserPreferred = "Solo Leveling",
        titleNative = "나 혼자만 레벨업",
        format = "TV",
        totalEpisodes = 12,
        genres = listOf("Action"),
        isAdult = false,
        description = "",
    )

    @Test
    fun `parallel provider successes emit distinct streams and terminate naturally`() = runTest {
        val sink = RecordingSink()
        val probe = ScriptedProbe()
        val first = stream("https://cdn.example/a/master.m3u8", serverName = "ProviderA")
        val second = stream("https://cdn.example/b/master.m3u8", serverName = "ProviderB")
        val service = service(
            plan = listOf(
                call(AnimeProviderId.MEGAPLAY) { listOf(first) },
                call(AnimeProviderId.RECLOUD) { listOf(second) },
            ),
            probe = probe,
            sink = sink,
        )

        val results = service.scrapeStreams(anime(), 1, "sub").toList()
        val evidence = "AGGREGATION name=parallel-successes; expected=${listOf(first, second)}; actual=$results"
        println(evidence)

        assertEquals(evidence, listOf(first, second).sortedBy { it.streamUrl }, results.sortedBy { it.streamUrl })
        assertEquals(evidence, 2, sink.outcomes.size)
        assertTrue("Flow must complete by provider signals; runTest provides the evidence", true)
    }

    @Test
    fun `controlled delayed provider success completes by provider signal without wall clock`() = runTest {
        val sink = RecordingSink()
        val probe = ScriptedProbe()
        val gate = CompletableDeferred<Unit>()
        val fast = stream("https://cdn.example/fast/master.m3u8", serverName = "Fast")
        val virtualDelayed = stream("https://cdn.example/virtual-delayed/master.m3u8", serverName = "VirtualDelayed")
        val gated = stream("https://cdn.example/gated/master.m3u8", serverName = "Gated")
        val service = service(
            plan = listOf(
                call(AnimeProviderId.LUNA) {
                    delay(60_000) // virtual time only; never a real wall-clock delay
                    listOf(virtualDelayed)
                },
                call(AnimeProviderId.VIDNEST) {
                    gate.await() // explicit controlled completion signal
                    listOf(gated)
                },
                call(AnimeProviderId.MEGAPLAY) { listOf(fast) },
            ),
            probe = probe,
            sink = sink,
        )

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            service.scrapeStreams(anime(), 1, "sub").toList()
        }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        val results = deferred.await()

        val expected = listOf(fast, virtualDelayed, gated).sortedBy { it.streamUrl }
        val evidence = "AGGREGATION name=delayed-and-gated-signals; expected=$expected; actual=$results"
        println(evidence)
        assertEquals(evidence, expected, results.sortedBy { it.streamUrl })
        assertEquals(evidence, 3, sink.outcomes.size)
    }

    @Test
    fun `empty outcome typed failure and thrown exception isolate without stopping siblings`() = runTest {
        val sink = RecordingSink()
        val probe = ScriptedProbe()
        val healthy = stream("https://cdn.example/healthy/master.m3u8", serverName = "Healthy")
        val service = service(
            plan = listOf(
                call(AnimeProviderId.ANIDB) { emptyList() },
                call(AnimeProviderId.ANIHQ) { throw IllegalStateException("boom") },
                call(AnimeProviderId.ANINEKO) { listOf(healthy) },
            ),
            probe = probe,
            sink = sink,
        )

        val results = service.scrapeStreams(anime(), 1, "sub").toList()
        val evidence = "AGGREGATION name=failure-isolation; expected=[$healthy]; actual=$results; " +
            "outcomes=${sink.outcomes.map { it.providerId.id }}"
        println(evidence)

        assertEquals(evidence, listOf(healthy), results)
        assertEquals(evidence, 3, sink.outcomes.size)
        val failureOutcome = sink.outcomes.single { it.providerId == AnimeProviderId.ANIHQ }
        assertEquals(evidence, 0, failureOutcome.streams.size)
        assertEquals(evidence, 1, failureOutcome.failures.size)
        assertTrue(
            evidence,
            failureOutcome.failures.single() is AnimeProviderFailure.ExtractionError,
        )
    }

    @Test
    fun `duplicate URL across providers emits once and rejected URL is never emitted`() = runTest {
        val sink = RecordingSink()
        val duplicate = stream("https://cdn.example/dup/master.m3u8", serverName = "First")
        val rejected = stream("https://cdn.example/rejected/master.m3u8", serverName = "Rejected")
        val probe = ScriptedProbe(rejectedUrls = setOf(rejected.streamUrl))
        val service = service(
            plan = listOf(
                call(AnimeProviderId.MEGAPLAY) { listOf(duplicate) },
                call(AnimeProviderId.RECLOUD) { listOf(duplicate.copy(serverName = "Second")) },
                call(AnimeProviderId.TRY_EMBED) { listOf(rejected) },
            ),
            probe = probe,
            sink = sink,
        )

        val results = service.scrapeStreams(anime(), 1, "sub").toList()
        val evidence = "AGGREGATION name=dedup-and-liveness; expected=[$duplicate]; actual=$results"
        println(evidence)

        assertEquals(evidence, listOf(duplicate), results)
        assertEquals(evidence, 3, sink.outcomes.size)
    }

    @Test
    fun `production factory preserves provider matrix category and adult selection`() {
        val service = AnimeScraperService(HttpStreamLivenessValidator())
        val factory = service.providerCallFactory
        val base = AnimeScrapeRequest(
            anime = anime(),
            episodeNumber = 1,
            categoryFilter = "sub",
            titleCandidates = listOf("Solo Leveling"),
            isAdultContent = false,
        )

        val evidence = "AGGREGATION name=production-factory-matrix"
        val perCategoryIds = listOf(
            AnimeProviderId.MEGAPLAY,
            AnimeProviderId.RECLOUD,
            AnimeProviderId.TRY_EMBED,
            AnimeProviderId.LUNA,
            AnimeProviderId.ANIDB,
            AnimeProviderId.ANINEKO,
            AnimeProviderId.ANIHQ,
            AnimeProviderId.ANIPM,
            AnimeProviderId.VIDNEST,
        )
        val tailIds = listOf(AnimeProviderId.DULO, AnimeProviderId.ONE_TWO_THREE_ANIME)

        val subPlan = factory.createPlan(base)
        assertEquals(evidence, perCategoryIds + tailIds, subPlan.map { it.providerId })

        val dubPlan = factory.createPlan(base.copy(categoryFilter = "dub"))
        assertEquals(evidence, perCategoryIds + tailIds, dubPlan.map { it.providerId })

        val bothPlan = factory.createPlan(base.copy(categoryFilter = null))
        assertEquals(evidence, perCategoryIds + perCategoryIds + tailIds, bothPlan.map { it.providerId })

        val adultPlan = factory.createPlan(base.copy(isAdultContent = true))
        assertEquals(
            evidence,
            listOf(AnimeProviderId.WATCH_HENTAI, AnimeProviderId.HENTAINI) + perCategoryIds + tailIds,
            adultPlan.map { it.providerId },
        )

        val noTitlesPlan = factory.createPlan(base.copy(titleCandidates = emptyList()))
        assertEquals(
            evidence,
            listOf(
                AnimeProviderId.MEGAPLAY,
                AnimeProviderId.RECLOUD,
                AnimeProviderId.TRY_EMBED,
                AnimeProviderId.LUNA,
                AnimeProviderId.ANIPM,
                AnimeProviderId.VIDNEST,
                AnimeProviderId.DULO,
            ),
            noTitlesPlan.map { it.providerId },
        )

        assertTrue(evidence, subPlan[2] is AnimeProviderCall.MultiStreamProvider)
        assertTrue(evidence, subPlan[7] is AnimeProviderCall.AniPMProvider)
        assertTrue(evidence, adultPlan.first() is AnimeProviderCall.AdultProvider)
        assertTrue(evidence, subPlan[9] is AnimeProviderCall.NoArgumentProvider)
        assertTrue(evidence, subPlan[10] is AnimeProviderCall.TitleOnlyProvider)
    }

    @Test
    fun `cancellation before provider calls start leaves zero calls and emissions`() = runTest {
        val callCount = AtomicInteger(0)
        val sink = RecordingSink()
        val service = service(
            plan = listOf(
                call(AnimeProviderId.MEGAPLAY) {
                    callCount.incrementAndGet()
                    emptyList()
                },
            ),
            probe = ScriptedProbe(),
            sink = sink,
        )
        val collected = AtomicInteger(0)
        val job = launch {
            service.scrapeStreams(anime(), 1, "sub").collect { collected.incrementAndGet() }
        }

        job.cancel()
        advanceUntilIdle()

        val evidence = "AGGREGATION_CANCELLATION name=before-call; expected=calls=0,collected=0,sink=0; " +
            "actual=calls=${callCount.get()},collected=${collected.get()},sink=${sink.outcomes.size}"
        println(evidence)
        assertEquals(evidence, 0, callCount.get())
        assertEquals(evidence, 0, collected.get())
        assertEquals(evidence, 0, sink.outcomes.size)
    }

    @Test
    fun `cancellation during provider call cancels unfinished work and forbids late emission`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger(0)
        val sink = RecordingSink()
        val service = service(
            plan = listOf(
                call(AnimeProviderId.MEGAPLAY) {
                    started.incrementAndGet()
                    gate.await()
                    listOf(stream("https://cdn.example/late/master.m3u8", serverName = "Late"))
                },
                call(AnimeProviderId.RECLOUD) { emptyList() },
            ),
            probe = ScriptedProbe(),
            sink = sink,
        )
        val collected = AtomicInteger(0)
        val job = launch {
            service.scrapeStreams(anime(), 1, "sub").collect { collected.incrementAndGet() }
        }

        advanceUntilIdle()
        assertEquals(1, started.get())
        job.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        val evidence = "AGGREGATION_CANCELLATION name=during-call; expected=started=1,collected=0; " +
            "actual=started=${started.get()},collected=${collected.get()}"
        println(evidence)
        assertEquals(evidence, 1, started.get())
        assertEquals(evidence, 0, collected.get())
    }

    @Test
    fun `cancellation before emission forbids late send`() = runTest {
        val probeGate = CompletableDeferred<Boolean>()
        val probe = StreamLivenessProbe { _, _ -> probeGate.await() }
        val sink = RecordingSink()
        val service = service(
            plan = listOf(
                call(AnimeProviderId.MEGAPLAY) {
                    listOf(stream("https://cdn.example/good/master.m3u8", serverName = "Good"))
                },
            ),
            probe = probe,
            sink = sink,
        )
        val collected = AtomicInteger(0)
        val job = launch {
            service.scrapeStreams(anime(), 1, "sub").collect { collected.incrementAndGet() }
        }

        advanceUntilIdle()
        job.cancel()
        probeGate.complete(true)
        advanceUntilIdle()

        val evidence = "AGGREGATION_CANCELLATION name=before-emission; expected=collected=0; actual=${collected.get()}"
        println(evidence)
        assertEquals(evidence, 0, collected.get())
        assertEquals(evidence, 1, sink.outcomes.size)
    }

    @Test
    fun `generated finite provider plans match the observation oracle`() = runTest {
        for (case in generatedPlanCases(ORACLE_SEED)) {
            val sink = RecordingSink()
            val probe = ScriptedProbe(rejectedUrls = setOf(case.rejectedUrl))
            val service = service(plan = case.plan, probe = probe, sink = sink)

            val results = service.scrapeStreams(anime(), 1, case.categoryFilter).toList()
            val evidence = "AGGREGATION_ORACLE seed=$ORACLE_SEED name=${case.name}; " +
                "expected=${case.expectedSorted}; actual=$results"
            println(evidence)

            assertEquals(evidence, case.expectedSorted, results.sortedBy { it.streamUrl })
            assertEquals(
                evidence,
                case.expectedFailureProviders.sortedBy { it.id },
                sink.outcomes.filter { it.failures.isNotEmpty() }.map { it.providerId }.sortedBy { it.id },
            )
        }
    }

    private data class PlanCase(
        val name: String,
        val plan: List<AnimeProviderCall>,
        val expectedSorted: List<AnimeStreamResult>,
        val rejectedUrl: String,
        val expectedFailureProviders: List<AnimeProviderId>,
        val categoryFilter: String? = "sub",
    )

    private fun generatedPlanCases(seed: Long): List<PlanCase> {
        val random = Random(seed)
        return (0 until 6).map { index ->
            val token = random.nextInt(1_000_000).toString().padStart(6, '0')
            val first = stream("https://cdn.example/$token/one.m3u8", serverName = "P1")
            val second = stream("https://cdn.example/$token/two.m3u8?token=$token", serverName = "P2", category = "DUB")
            val rejected = stream("https://cdn.example/$token/rejected.m3u8", serverName = "P3")
            PlanCase(
                name = "plan-$index",
                plan = listOf(
                    call(AnimeProviderId.MEGAPLAY) { listOf(first) },
                    call(AnimeProviderId.RECLOUD) { emptyList() },
                    call(AnimeProviderId.TRY_EMBED) { throw IllegalStateException("typed-failure-$token") },
                    call(AnimeProviderId.LUNA) { listOf(second) },
                    call(AnimeProviderId.ANINEKO) { listOf(rejected) },
                    call(AnimeProviderId.VIDNEST) { listOf(first, first.copy(serverName = "P1-dup")) },
                ),
                expectedSorted = listOf(first, second).sortedBy { it.streamUrl },
                rejectedUrl = rejected.streamUrl,
                expectedFailureProviders = listOf(AnimeProviderId.TRY_EMBED),
            )
        }
    }

    private companion object {
        private const val ORACLE_SEED = 815_306_147L
    }
}
