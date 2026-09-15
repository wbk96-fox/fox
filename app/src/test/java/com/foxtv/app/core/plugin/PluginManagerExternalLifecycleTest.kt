package com.foxtv.app.core.plugin

import com.foxtv.app.core.auth.AuthManager
import com.foxtv.app.core.plugin.cloudstream.CloudstreamLifecycleTestFixtures
import com.foxtv.app.core.plugin.cloudstream.ExternalExtensionLoader
import com.foxtv.app.core.plugin.cloudstream.ExternalExtensionRunner
import com.foxtv.app.core.plugin.cloudstream.ExternalPluginLifecycleCoordinator
import com.foxtv.app.core.plugin.cloudstream.ExternalRepoParseResult
import com.foxtv.app.core.plugin.cloudstream.ExternalRepoParser
import com.foxtv.app.core.plugin.cloudstream.ExternalRepositorySyncResult
import com.foxtv.app.core.plugin.cloudstream.ExternalRepositorySynchronizer
import com.foxtv.app.core.sync.PluginSyncService
import com.foxtv.app.data.local.PluginDataStore
import com.foxtv.app.domain.model.PluginRepository
import com.foxtv.app.domain.model.RepositoryType
import com.foxtv.app.domain.model.ScraperInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerExternalLifecycleTest {
    @Test
    fun `non authoritative refresh preserves current generation without invoking synchronizer`() =
        runBlocking {
            val harness = harness()
            coEvery { harness.parser.tryParse(harness.repository.url) } returns ExternalRepoParseResult(
                name = "Rejected generation",
                description = null,
                plugins = emptyList(),
                hasActiveInvalidEntries = true,
                rejectedPluginCount = 1,
            )

            val result = harness.manager.refreshRepository(harness.repository.id)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("previous generation was preserved"))
            coVerify(exactly = 0) {
                harness.synchronizer.synchronizeLocked(any(), any(), any())
            }
            coVerify(exactly = 0) {
                harness.dataStore.commitExternalRepositoryState(any(), any(), any())
            }
            verify(exactly = 0) { harness.loader.deleteExtension(any()) }
        }

    @Test
    fun `refresh and disable are serialized by the production lifecycle coordinator`() = runBlocking {
        val harness = harness()
        val plugin = CloudstreamLifecycleTestFixtures.plugin("Provider")
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val disablePersisted = CompletableDeferred<Unit>()
        coEvery { harness.parser.tryParse(harness.repository.url) } returns ExternalRepoParseResult(
            name = harness.repository.name,
            description = harness.repository.description,
            plugins = listOf(plugin),
        )
        coEvery {
            harness.synchronizer.synchronizeLocked(any(), any(), requireExistingRepository = true)
        } coAnswers {
            refreshEntered.complete(Unit)
            releaseRefresh.await()
            ExternalRepositorySyncResult(
                installed = listOf(harness.externalScraper),
                failedPluginNames = emptyList(),
                removedStaleCount = 0,
            )
        }
        coEvery { harness.dataStore.saveScrapers(any()) } coAnswers {
            disablePersisted.complete(Unit)
            true
        }

        val refresh = async { harness.manager.refreshRepository(harness.repository.id) }
        withTimeout(2_000) { refreshEntered.await() }
        val disable = async { harness.manager.toggleScraper(harness.externalScraper.id, false) }

        val prematureCommit = withTimeoutOrNull(150) { disablePersisted.await() }
        assertNull("Disable must wait for the in-progress refresh transaction", prematureCommit)

        releaseRefresh.complete(Unit)
        assertTrue(refresh.await().isSuccess)
        disable.await()
        withTimeout(2_000) { disablePersisted.await() }
        verify(exactly = 1) {
            harness.loader.setOwnersEnabled(listOf(harness.externalScraper.id), false)
        }
    }

    @Test
    fun `single owner disable persists before unload and a rejected write has no side effects`() =
        runBlocking {
            val committed = harness()
            coEvery { committed.dataStore.saveScrapers(any()) } returns true

            committed.manager.toggleScraper(committed.externalScraper.id, false)

            coVerifyOrder {
                committed.dataStore.saveScrapers(match { scrapers ->
                    scrapers.single { it.id == committed.externalScraper.id }.enabled.not()
                })
                committed.loader.setOwnersEnabled(listOf(committed.externalScraper.id), false)
            }

            val rejected = harness()
            coEvery { rejected.dataStore.saveScrapers(any()) } returns false

            rejected.manager.toggleScraper(rejected.externalScraper.id, false)

            verify(exactly = 0) { rejected.loader.setOwnersEnabled(any(), any()) }
        }

    @Test
    fun `global disable persists first and unloads only external owners`() = runBlocking {
        val harness = harness(includeJsScraper = true)
        coEvery { harness.dataStore.setPluginsEnabled(false) } returns true

        harness.manager.setPluginsEnabled(false)

        coVerifyOrder {
            harness.dataStore.setPluginsEnabled(false)
            harness.loader.setGloballyEnabled(false, listOf(harness.externalScraper.id))
        }

        val rejected = harness(includeJsScraper = true)
        coEvery { rejected.dataStore.setPluginsEnabled(false) } returns false

        rejected.manager.setPluginsEnabled(false)

        verify(exactly = 0) { rejected.loader.setGloballyEnabled(any(), any()) }
    }

    @Test
    fun `DEX generation and JS refresh cannot overwrite each other`() = runBlocking {
        val server = MockWebServer()
        try {
            val harness = harness()
            val jsRepository = CloudstreamLifecycleTestFixtures.repository(
                id = "js-repo",
                url = server.url("/manifest.json").toString(),
            ).copy(name = "JS Repository", type = RepositoryType.FOXTV_JS)
            harness.repositoryState.value = listOf(harness.repository, jsRepository)
            val plugin = CloudstreamLifecycleTestFixtures.plugin("Provider")
            val newDexGeneration = harness.externalScraper.copy(
                name = "New DEX Generation",
                version = "2",
            )
            val dexEntered = CompletableDeferred<Unit>()
            val releaseDex = CompletableDeferred<Unit>()
            coEvery { harness.parser.tryParse(harness.repository.url) } returns ExternalRepoParseResult(
                name = harness.repository.name,
                description = harness.repository.description,
                plugins = listOf(plugin),
            )
            coEvery {
                harness.synchronizer.synchronizeLocked(any(), any(), requireExistingRepository = true)
            } coAnswers {
                dexEntered.complete(Unit)
                releaseDex.await()
                harness.scraperState.value = listOf(newDexGeneration)
                ExternalRepositorySyncResult(
                    installed = listOf(newDexGeneration),
                    failedPluginNames = emptyList(),
                    removedStaleCount = 0,
                )
            }
            var savedScrapers = emptyList<ScraperInfo>()
            coEvery { harness.dataStore.saveScrapers(any()) } coAnswers {
                savedScrapers = firstArg()
                harness.scraperState.value = savedScrapers
                true
            }
            val code = "function scrape() { return []; }"
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "name":"JS Repository",
                      "version":"1",
                      "scrapers":[{
                        "id":"Javascript",
                        "name":"Javascript",
                        "version":"1",
                        "filename":"provider.js"
                      }]
                    }
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().addHeader("Content-Length", code.toByteArray().size),
            )
            server.enqueue(MockResponse().setBody(code))

            val dexRefresh = async { harness.manager.refreshRepository(harness.repository.id) }
            withTimeout(2_000) { dexEntered.await() }
            val jsRefresh = async { harness.manager.refreshRepository(jsRepository.id) }

            val requestBeforeDexCommit = withTimeoutOrNull(150) {
                while (server.requestCount == 0) yield()
                server.requestCount
            }
            assertNull(
                "JS refresh must not take its full-list snapshot while DEX owns the lifecycle lock",
                requestBeforeDexCommit,
            )

            releaseDex.complete(Unit)
            assertTrue(dexRefresh.await().isSuccess)
            assertTrue(jsRefresh.await().isSuccess)
            assertEquals("2", savedScrapers.single { it.id == newDexGeneration.id }.version)
            assertTrue(savedScrapers.any { it.id == "${jsRepository.id}:Javascript" })
            assertEquals(3, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `manifest disabled owner cannot be enabled by user toggle`() = runBlocking {
        val harness = harness(manifestEnabled = false)
        coEvery { harness.dataStore.saveScrapers(any()) } returns true

        harness.manager.toggleScraper(harness.externalScraper.id, true)

        coVerify {
            harness.dataStore.saveScrapers(match { scrapers ->
                val updated = scrapers.single { it.id == harness.externalScraper.id }
                !updated.enabled && !updated.manifestEnabled
            })
        }
        verify(exactly = 1) {
            harness.loader.setOwnersEnabled(listOf(harness.externalScraper.id), false)
        }
    }

    private data class Harness(
        val manager: PluginManager,
        val dataStore: PluginDataStore,
        val parser: ExternalRepoParser,
        val loader: ExternalExtensionLoader,
        val synchronizer: ExternalRepositorySynchronizer,
        val repository: PluginRepository,
        val externalScraper: ScraperInfo,
        val repositoryState: MutableStateFlow<List<PluginRepository>>,
        val scraperState: MutableStateFlow<List<ScraperInfo>>,
    )

    private fun harness(
        includeJsScraper: Boolean = false,
        manifestEnabled: Boolean = true,
    ): Harness {
        val repository = CloudstreamLifecycleTestFixtures.repository()
        val externalScraper = CloudstreamLifecycleTestFixtures.scraper(
            id = "${repository.id}:Provider",
            repositoryId = repository.id,
            enabled = manifestEnabled,
            manifestEnabled = manifestEnabled,
        )
        val scrapers = buildList {
            add(externalScraper)
            if (includeJsScraper) {
                add(
                    CloudstreamLifecycleTestFixtures.scraper(
                        id = "js-repo:Javascript",
                        repositoryId = "js-repo",
                        type = RepositoryType.FOXTV_JS,
                    ),
                )
            }
        }
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        val repositoryState = MutableStateFlow(listOf(repository))
        val scraperState = MutableStateFlow(scrapers)
        every { dataStore.repositories } returns repositoryState
        every { dataStore.scrapers } returns scraperState
        every { dataStore.pluginsEnabled } returns MutableStateFlow(true)
        every { dataStore.groupStreamsByRepository } returns MutableStateFlow(false)
        val parser = mockk<ExternalRepoParser>()
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        val runner = mockk<ExternalExtensionRunner>(relaxed = true)
        val synchronizer = mockk<ExternalRepositorySynchronizer>()
        val manager = PluginManager(
            dataStore = dataStore,
            runtime = mockk<PluginRuntime>(relaxed = true),
            pluginSyncService = mockk<PluginSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            externalRepoParser = parser,
            externalExtensionLoader = loader,
            externalExtensionRunner = runner,
            externalPluginLifecycle = ExternalPluginLifecycleCoordinator(),
            externalRepositorySynchronizer = synchronizer,
        )
        return Harness(
            manager = manager,
            dataStore = dataStore,
            parser = parser,
            loader = loader,
            synchronizer = synchronizer,
            repository = repository,
            externalScraper = externalScraper,
            repositoryState = repositoryState,
            scraperState = scraperState,
        )
    }
}
