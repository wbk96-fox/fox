package com.foxtv.app.core.plugin.cloudstream

import com.foxtv.app.data.local.PluginDataStore
import com.foxtv.app.domain.model.ScraperInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExternalRepositorySynchronizerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `one failed download rejects the complete generation before activation`() = runBlocking {
        val repository = CloudstreamLifecycleTestFixtures.repository()
        val first = CloudstreamLifecycleTestFixtures.plugin("First")
        val second = CloudstreamLifecycleTestFixtures.plugin("Second")
        val firstTarget = oldTarget("first", "known-good-first")
        val firstBefore = firstTarget.readBytes()
        val preparedFirst = CloudstreamLifecycleTestFixtures.prepared(
            scraperId = "${repository.id}:${first.internalName}",
            targetFile = firstTarget,
        )
        val stagedFile = preparedFirst.stagedArtifact.stagedFile
        val dataStore = dataStore(existing = emptyList())
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        coEvery { loader.stageExtension("${repository.id}:${first.internalName}", first) } returns preparedFirst
        coEvery { loader.stageExtension("${repository.id}:${second.internalName}", second) } returns null
        val synchronizer = ExternalRepositorySynchronizer(
            dataStore,
            loader,
            ExternalPluginLifecycleCoordinator(),
        )

        val result = synchronizer.synchronizeLocked(
            repository = repository,
            parseResult = authoritative(first, second),
            requireExistingRepository = true,
        )

        assertTrue(result.installed.isEmpty())
        assertEquals(setOf("Second"), result.failedPluginNames.toSet())
        assertEquals(0, result.removedStaleCount)
        assertArrayEquals(firstBefore, firstTarget.readBytes())
        assertFalse(stagedFile.exists())
        CloudstreamLifecycleTestFixtures.assertNoTransactionFiles(requireNotNull(firstTarget.parentFile))
        coVerify(exactly = 0) {
            dataStore.commitExternalRepositoryState(any(), any(), any())
        }
        verify(exactly = 0) { loader.deleteExtension(any()) }
        verify(exactly = 0) { loader.evictCache(any()) }
    }

    @Test
    fun `rejected metadata commit restores active artifact and skips finalization`() = runBlocking {
        val repository = CloudstreamLifecycleTestFixtures.repository()
        val plugin = CloudstreamLifecycleTestFixtures.plugin("Provider")
        val target = oldTarget("provider", "known-good-provider")
        val before = target.readBytes()
        val prepared = CloudstreamLifecycleTestFixtures.prepared(
            scraperId = "${repository.id}:${plugin.internalName}",
            targetFile = target,
        )
        val dataStore = dataStore(existing = emptyList())
        coEvery {
            dataStore.commitExternalRepositoryState(any(), any(), requireExistingRepository = true)
        } returns false
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        coEvery { loader.stageExtension(any(), plugin) } returns prepared
        val synchronizer = ExternalRepositorySynchronizer(
            dataStore,
            loader,
            ExternalPluginLifecycleCoordinator(),
        )

        val failure = runCatching {
            synchronizer.synchronizeLocked(
                repository = repository,
                parseResult = authoritative(plugin),
                requireExistingRepository = true,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertArrayEquals(before, target.readBytes())
        CloudstreamLifecycleTestFixtures.assertNoTransactionFiles(requireNotNull(target.parentFile))
        verify(exactly = 0) { loader.evictCache(any()) }
        verify(exactly = 0) { loader.deleteExtension(any()) }
        verify(exactly = 0) { loader.setGloballyEnabled(any(), any()) }
    }

    @Test
    fun `successful generation commits merged metadata before deleting only stale owner`() = runBlocking {
        val repository = CloudstreamLifecycleTestFixtures.repository()
        val plugin = CloudstreamLifecycleTestFixtures.plugin("Retained")
        val retainedId = "${repository.id}:${plugin.internalName}"
        val staleId = "${repository.id}:Stale"
        val otherId = "other-repo:Other"
        val retained = CloudstreamLifecycleTestFixtures.scraper(
            id = retainedId,
            repositoryId = repository.id,
            enabled = false,
        )
        val stale = CloudstreamLifecycleTestFixtures.scraper(staleId, repository.id)
        val other = CloudstreamLifecycleTestFixtures.scraper(otherId, "other-repo")
        val target = oldTarget("retained", "old-retained")
        val newArtifact = CloudstreamLifecycleTestFixtures.validCs3("example.RetainedPlugin")
        val prepared = CloudstreamLifecycleTestFixtures.prepared(retainedId, target, newArtifact)
        val dataStore = dataStore(existing = listOf(retained, stale, other))
        val committedScrapers = slot<List<ScraperInfo>>()
        coEvery {
            dataStore.commitExternalRepositoryState(
                repository = any(),
                scrapers = capture(committedScrapers),
                requireExistingRepository = true,
            )
        } returns true
        val loader = mockk<ExternalExtensionLoader>(relaxed = true)
        coEvery { loader.stageExtension(retainedId, plugin) } returns prepared
        val synchronizer = ExternalRepositorySynchronizer(
            dataStore,
            loader,
            ExternalPluginLifecycleCoordinator(),
        )

        val result = synchronizer.synchronizeLocked(
            repository = repository,
            parseResult = authoritative(plugin),
            requireExistingRepository = true,
        )

        assertEquals(1, result.installed.size)
        assertEquals(1, result.removedStaleCount)
        assertTrue(result.failedPluginNames.isEmpty())
        assertArrayEquals(newArtifact, target.readBytes())
        assertEquals(setOf(retainedId, otherId), committedScrapers.captured.map { it.id }.toSet())
        assertFalse(committedScrapers.captured.single { it.id == retainedId }.enabled)
        verify(exactly = 1) { loader.deleteExtension(staleId) }
        verify(exactly = 0) { loader.deleteExtension(retainedId) }
        verify(exactly = 0) { loader.deleteExtension(otherId) }
        verify(exactly = 1) { loader.evictCache(retainedId) }
        verify(exactly = 1) { loader.setOwnersEnabled(listOf(retainedId), false) }
        verify(exactly = 1) {
            loader.setGloballyEnabled(true, match { it.toSet() == setOf(retainedId, otherId) })
        }
        CloudstreamLifecycleTestFixtures.assertNoTransactionFiles(requireNotNull(target.parentFile))
    }

    private fun dataStore(existing: List<ScraperInfo>): PluginDataStore =
        mockk<PluginDataStore>(relaxed = true).also { dataStore ->
            every { dataStore.scrapers } returns flowOf(existing)
            every { dataStore.pluginsEnabled } returns flowOf(true)
        }

    private fun authoritative(vararg plugins: com.foxtv.app.domain.model.ExternalPluginEntry) =
        ExternalRepoParseResult(
            name = "External Repository",
            description = "Verified generation",
            plugins = plugins.toList(),
        )

    private fun oldTarget(name: String, content: String): File =
        File(temporaryFolder.newFolder("extensions-$name"), "$name.cs3").apply {
            writeText(content)
        }
}
