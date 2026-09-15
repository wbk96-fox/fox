package com.foxtv.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.foxtv.app.core.plugin.cloudstream.CloudstreamLifecycleTestFixtures
import com.foxtv.app.core.profile.ProfileManager
import com.foxtv.app.domain.model.PluginRepository
import com.foxtv.app.domain.model.ScraperInfo
import com.foxtv.app.domain.model.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDataStoreExternalStateTest {
    @Test
    fun `external repository and scraper generation commit in one datastore update`() = runBlocking {
        val harness = harness(primaryProfile())
        val repository = CloudstreamLifecycleTestFixtures.repository()
        val scraper = CloudstreamLifecycleTestFixtures.scraper(
            id = "${repository.id}:Provider",
            repositoryId = repository.id,
        )

        val committed = harness.target.commitExternalRepositoryState(
            repository = repository,
            scrapers = listOf(scraper),
            requireExistingRepository = false,
        )

        assertTrue(committed)
        assertEquals(1, harness.store.updateCount)
        assertEquals(listOf(repository), harness.target.repositories.first())
        assertEquals(listOf(scraper), harness.target.scrapers.first())
    }

    @Test
    fun `require existing failure leaves both repository and scraper state unchanged`() = runBlocking {
        val harness = harness(primaryProfile())
        val repository = CloudstreamLifecycleTestFixtures.repository()
        val scraper = CloudstreamLifecycleTestFixtures.scraper(
            id = "${repository.id}:Provider",
            repositoryId = repository.id,
        )

        val failure = runCatching {
            harness.target.commitExternalRepositoryState(
                repository = repository,
                scrapers = listOf(scraper),
                requireExistingRepository = true,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, harness.store.updateCount)
        assertTrue(harness.target.repositories.first().isEmpty())
        assertTrue(harness.target.scrapers.first().isEmpty())
    }

    @Test
    fun `external repository removal updates descriptors and scrapers atomically`() = runBlocking {
        val harness = harness(primaryProfile())
        val repository = CloudstreamLifecycleTestFixtures.repository()
        val scraper = CloudstreamLifecycleTestFixtures.scraper(
            id = "${repository.id}:Provider",
            repositoryId = repository.id,
        )
        harness.target.commitExternalRepositoryState(repository, listOf(scraper), false)
        harness.store.resetUpdateCount()

        val removed = harness.target.removeExternalRepositoryState(repository.id, emptyList())

        assertTrue(removed)
        assertEquals(1, harness.store.updateCount)
        assertTrue(harness.target.repositories.first().isEmpty())
        assertTrue(harness.target.scrapers.first().isEmpty())
    }

    @Test
    fun `shared secondary profile rejects lifecycle writes without touching primary store`() =
        runBlocking {
            val shared = UserProfile(
                id = 2,
                name = "Shared",
                avatarColorHex = "#000000",
                usesPrimaryPlugins = true,
            )
            val harness = harness(shared)
            val repository = CloudstreamLifecycleTestFixtures.repository()
            val scraper = CloudstreamLifecycleTestFixtures.scraper(
                id = "${repository.id}:Provider",
                repositoryId = repository.id,
            )

            assertFalse(
                harness.target.commitExternalRepositoryState(
                    repository,
                    listOf(scraper),
                    requireExistingRepository = false,
                ),
            )
            assertFalse(harness.target.removeExternalRepositoryState(repository.id, emptyList()))
            assertFalse(harness.target.setPluginsEnabled(false))
            assertEquals(0, harness.store.updateCount)
        }

    private data class Harness(
        val target: PluginDataStore,
        val store: TestPreferencesDataStore,
    )

    private fun harness(activeProfile: UserProfile): Harness {
        val store = TestPreferencesDataStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(activeProfile.id)
        every { profileManager.profiles } returns MutableStateFlow(listOf(primaryProfile(), activeProfile).distinctBy { it.id })
        every { profileManager.activeProfile } returns activeProfile
        val target = PluginDataStore(
            context = mockk<Context>(relaxed = true),
            moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
            factory = factory,
            profileManager = profileManager,
        )
        return Harness(target, store)
    }

    private fun primaryProfile() = UserProfile(
        id = 1,
        name = "Primary",
        avatarColorHex = "#FFFFFF",
    )

    private class TestPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        var updateCount: Int = 0
            private set

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            val updated = transform(state.value)
            updateCount += 1
            state.value = updated
            updated
        }

        fun resetUpdateCount() {
            updateCount = 0
        }
    }
}
