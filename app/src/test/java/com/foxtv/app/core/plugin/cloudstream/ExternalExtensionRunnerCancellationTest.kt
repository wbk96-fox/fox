package com.foxtv.app.core.plugin.cloudstream

import com.foxtv.app.core.plugin.TestDiagnostics
import com.foxtv.app.core.tmdb.TmdbMetadataService
import com.foxtv.app.core.tmdb.TmdbService
import com.lagradost.cloudstream3.MainAPI
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalExtensionRunnerCancellationTest {
    @Test
    fun `normal execution propagates cancellation from provider preparation`() = runBlocking {
        val harness = harness()

        val failure = runCatching {
            harness.runner.execute("repo:Provider", "603", "movie", null, null)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun `diagnostic execution propagates cancellation instead of reporting an empty result`() =
        runBlocking {
            val harness = harness()

            val failure = runCatching {
                harness.runner.executeWithDiagnostics(
                    scraperId = "repo:Provider",
                    tmdbId = "603",
                    mediaType = "movie",
                    season = null,
                    episode = null,
                    diagnostics = TestDiagnostics(),
                )
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        }

    private data class Harness(val runner: ExternalExtensionRunner)

    private fun harness(): Harness {
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        val api = mockk<MainAPI>(relaxed = true)
        every { api.name } returns "CancellationProvider"
        every { loader.getApi("repo:Provider") } returns api
        every {
            loader.loadExtensionWithDiagnostics("repo:Provider", any())
        } returns listOf(api)
        val metadata = mockk<TmdbMetadataService>()
        coEvery { metadata.fetchEnrichment(any(), any()) } throws
            CancellationException("caller cancelled")
        return Harness(
            ExternalExtensionRunner(
                extensionLoader = loader,
                extractorRegistry = mockk<ExternalExtractorRegistry>(relaxed = true),
                tmdbMetadataService = metadata,
                tmdbService = mockk<TmdbService>(relaxed = true),
            ),
        )
    }
}
