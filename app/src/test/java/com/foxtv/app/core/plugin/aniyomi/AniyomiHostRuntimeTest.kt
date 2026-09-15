package com.foxtv.app.core.plugin.aniyomi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostBootstrapException
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostRegistry
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostRuntime
import com.foxtv.app.core.plugin.aniyomi.host.InjektAniyomiHostRegistry
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AniyomiHostRuntimeTest {
    @Test
    fun emptyRegistryPublishesBothExactInstancesOnceAndRepeatIsNoOp() {
        val application = application()
        val helper = networkHelper()
        val registry = FakeRegistry()
        val runtime = AniyomiHostRuntime(application, helper, registry)

        runtime.initialize()
        runtime.initialize()

        assertTrue(runtime.isInitialized())
        assertSame(application, registry.registeredApplication)
        assertSame(helper, registry.registeredNetworkHelper)
        assertEquals(1, registry.applicationAdds.get())
        assertEquals(1, registry.networkAdds.get())
    }

    @Test
    fun sameRegisteredInstancesAreAcceptedWithoutMutation() {
        val application = application()
        val helper = networkHelper()
        val registry = FakeRegistry(registeredApplication = application, registeredNetworkHelper = helper)
        val runtime = AniyomiHostRuntime(application, helper, registry)

        runtime.initialize()

        assertTrue(runtime.isInitialized())
        assertEquals(0, registry.applicationAdds.get())
        assertEquals(0, registry.networkAdds.get())
    }

    @Test
    fun foreignApplicationFailsBeforeEitherMissingKeyCanBePublished() {
        val expectedApplication = application()
        val registry = FakeRegistry(registeredApplication = application())
        val runtime = AniyomiHostRuntime(expectedApplication, networkHelper(), registry)

        assertThrows(AniyomiHostBootstrapException::class.java, runtime::initialize)

        assertFalse(runtime.isInitialized())
        assertEquals(0, registry.applicationAdds.get())
        assertEquals(0, registry.networkAdds.get())
    }

    @Test
    fun foreignNetworkHelperFailsBeforeMissingApplicationCanBePublished() {
        val expectedHelper = networkHelper()
        val registry = FakeRegistry(registeredNetworkHelper = networkHelper())
        val runtime = AniyomiHostRuntime(application(), expectedHelper, registry)

        assertThrows(AniyomiHostBootstrapException::class.java, runtime::initialize)

        assertFalse(runtime.isInitialized())
        assertEquals(0, registry.applicationAdds.get())
        assertEquals(0, registry.networkAdds.get())
    }

    @Test
    fun throwingExistingFactoryPropagatesBeforeMutation() {
        val failure = IllegalStateException("registry factory failed")
        val registry = FakeRegistry(registeredApplication = application(), applicationReadFailure = failure)
        val runtime = AniyomiHostRuntime(application(), networkHelper(), registry)

        val thrown = assertThrows(IllegalStateException::class.java, runtime::initialize)

        assertSame(failure, thrown)
        assertFalse(runtime.isInitialized())
        assertEquals(0, registry.applicationAdds.get())
        assertEquals(0, registry.networkAdds.get())
    }

    @Test
    fun throwingSecondPreflightCheckCannotLeavePartialPublication() {
        val registry = FakeRegistry(networkPresenceFailure = IllegalStateException("hasFactory failed"))
        val runtime = AniyomiHostRuntime(application(), networkHelper(), registry)

        assertThrows(IllegalStateException::class.java, runtime::initialize)

        assertFalse(runtime.isInitialized())
        assertEquals(0, registry.applicationAdds.get())
        assertEquals(0, registry.networkAdds.get())
    }

    @Test
    fun concurrentInitializationPublishesEachKeyExactlyOnce() {
        val registry = FakeRegistry()
        val runtime = AniyomiHostRuntime(application(), networkHelper(), registry)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(32) {
                executor.submit {
                    check(start.await(10, TimeUnit.SECONDS))
                    runtime.initialize()
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertTrue(runtime.isInitialized())
        assertEquals(1, registry.applicationAdds.get())
        assertEquals(1, registry.networkAdds.get())
    }

    @Test
    fun singletonFactoryPublishesExactlyOneInstanceAcrossConcurrentReads() {
        val registrar = DefaultRegistrar()
        val factoryCalls = AtomicInteger()
        val workerCount = 16
        val workersReady = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val enteringGet = CountDownLatch(workerCount)
        val factoryStarted = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        registrar.addSingletonFactory(
            uy.kohesive.injekt.api.fullType<StringBuilder>(),
        ) {
            factoryCalls.incrementAndGet()
            factoryStarted.countDown()
            check(releaseFactory.await(10, TimeUnit.SECONDS))
            StringBuilder("singleton")
        }

        val executor = Executors.newFixedThreadPool(workerCount)
        val futures = List(workerCount) {
            executor.submit<StringBuilder> {
                workersReady.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                enteringGet.countDown()
                registrar.get()
            }
        }
        try {
            assertTrue(workersReady.await(10, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(factoryStarted.await(10, TimeUnit.SECONDS))
            assertTrue(enteringGet.await(10, TimeUnit.SECONDS))
            releaseFactory.countDown()

            val instances = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, factoryCalls.get())
            instances.drop(1).forEach { assertSame(instances.first(), it) }
        } finally {
            start.countDown()
            releaseFactory.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun globalInjektIntegrationPublishesExactKeysAndDefaultImplReadsSourcePreferences() {
        synchronized(AniyomiResearchContracts.globalInjektLock) {
            val previousScope = Injekt
            try {
                Injekt = InjektScope(DefaultRegistrar())
                val preferences = mockk<SharedPreferences>()
                val application = application()
                every {
                    application.getSharedPreferences("source_42", Context.MODE_PRIVATE)
                } returns preferences
                val helper = networkHelper()
                val runtime = AniyomiHostRuntime(application, helper, InjektAniyomiHostRegistry())

                runtime.initialize()

                assertSame(application, Injekt.get<Application>())
                assertSame(helper, Injekt.get<NetworkHelper>())
                val source: ConfigurableAnimeSource = TestConfigurableAnimeSource(id = 42L)
                val defaultImpls = Class.forName(
                    "eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource\$DefaultImpls",
                )
                val method = defaultImpls.getDeclaredMethod(
                    "getSourcePreferences",
                    ConfigurableAnimeSource::class.java,
                )
                val actual = try {
                    method.invoke(null, source)
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }

                assertSame(preferences, actual)
                verify(exactly = 1) {
                    application.getSharedPreferences("source_42", Context.MODE_PRIVATE)
                }
            } finally {
                Injekt = previousScope
            }
        }
    }

    private class TestConfigurableAnimeSource(
        override val id: Long,
    ) : ConfigurableAnimeSource {
        override val name: String = "Aniyomi host test source"

        override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) = Unit

        override suspend fun getAnimeDetails(
            anime: eu.kanade.tachiyomi.animesource.model.SAnime,
        ): eu.kanade.tachiyomi.animesource.model.SAnime = anime

        override suspend fun getEpisodeList(
            anime: eu.kanade.tachiyomi.animesource.model.SAnime,
        ): List<eu.kanade.tachiyomi.animesource.model.SEpisode> = emptyList()

        override suspend fun getVideoList(
            episode: eu.kanade.tachiyomi.animesource.model.SEpisode,
        ): List<eu.kanade.tachiyomi.animesource.model.Video> = emptyList()

        @Deprecated("Use getAnimeDetails")
        override fun fetchAnimeDetails(
            anime: eu.kanade.tachiyomi.animesource.model.SAnime,
        ): rx.Observable<eu.kanade.tachiyomi.animesource.model.SAnime> = rx.Observable.just(anime)

        @Deprecated("Use getEpisodeList")
        override fun fetchEpisodeList(
            anime: eu.kanade.tachiyomi.animesource.model.SAnime,
        ): rx.Observable<List<eu.kanade.tachiyomi.animesource.model.SEpisode>> = rx.Observable.just(emptyList())

        @Deprecated("Use getVideoList")
        override fun fetchVideoList(
            episode: eu.kanade.tachiyomi.animesource.model.SEpisode,
        ): rx.Observable<List<eu.kanade.tachiyomi.animesource.model.Video>> = rx.Observable.just(emptyList())
    }

    private fun application(): Application = mockk(relaxed = true)

    private fun networkHelper(): NetworkHelper = NetworkHelper(OkHttpClient()) { "FOX.TV/test" }

    private class FakeRegistry(
        @Volatile var registeredApplication: Application? = null,
        @Volatile var registeredNetworkHelper: NetworkHelper? = null,
        private val applicationReadFailure: RuntimeException? = null,
        private val networkReadFailure: RuntimeException? = null,
        private val applicationPresenceFailure: RuntimeException? = null,
        private val networkPresenceFailure: RuntimeException? = null,
    ) : AniyomiHostRegistry {
        val applicationAdds = AtomicInteger()
        val networkAdds = AtomicInteger()

        override fun hasApplication(): Boolean {
            applicationPresenceFailure?.let { throw it }
            return registeredApplication != null
        }

        override fun getApplication(): Application {
            applicationReadFailure?.let { throw it }
            return requireNotNull(registeredApplication)
        }

        override fun addApplication(application: Application) {
            check(registeredApplication == null)
            registeredApplication = application
            applicationAdds.incrementAndGet()
        }

        override fun hasNetworkHelper(): Boolean {
            networkPresenceFailure?.let { throw it }
            return registeredNetworkHelper != null
        }

        override fun getNetworkHelper(): NetworkHelper {
            networkReadFailure?.let { throw it }
            return requireNotNull(registeredNetworkHelper)
        }

        override fun addNetworkHelper(networkHelper: NetworkHelper) {
            check(registeredNetworkHelper == null)
            registeredNetworkHelper = networkHelper
            networkAdds.incrementAndGet()
        }
    }
}
