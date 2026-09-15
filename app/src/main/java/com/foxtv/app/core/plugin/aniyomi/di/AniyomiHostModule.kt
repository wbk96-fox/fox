package com.foxtv.app.core.plugin.aniyomi.di

import android.app.Application
import android.content.Context
import com.foxtv.app.BuildConfig
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiCookieJar
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostRegistry
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostRuntime
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiNetworkClientFactory
import com.foxtv.app.core.plugin.aniyomi.host.InjektAniyomiHostRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.kanade.tachiyomi.network.NetworkHelper
import javax.inject.Singleton

/** Parent-only Aniyomi services. This module does not bind an extension loader or raw OkHttpClient. */
@Module
@InstallIn(SingletonComponent::class)
internal object AniyomiHostModule {
    @Provides
    @Singleton
    fun provideAniyomiCookieJar(): AniyomiCookieJar = AniyomiCookieJar()

    @Provides
    @Singleton
    fun provideAniyomiNetworkClientFactory(
        @ApplicationContext context: Context,
        cookieJar: AniyomiCookieJar,
    ): AniyomiNetworkClientFactory = AniyomiNetworkClientFactory(
        applicationCacheDirectory = context.cacheDir,
        cookieJar = cookieJar,
        versionNameProvider = { BuildConfig.VERSION_NAME },
    )

    @Provides
    @Singleton
    fun provideAniyomiNetworkHelper(factory: AniyomiNetworkClientFactory): NetworkHelper =
        NetworkHelper(factory.createClient(), factory::defaultUserAgentProvider)

    @Provides
    @Singleton
    fun provideAniyomiHostRegistry(): AniyomiHostRegistry = InjektAniyomiHostRegistry()

    @Provides
    @Singleton
    fun provideAniyomiHostRuntime(
        @ApplicationContext context: Context,
        networkHelper: NetworkHelper,
        registry: AniyomiHostRegistry,
    ): AniyomiHostRuntime {
        val application = context.applicationContext as? Application
            ?: error("Application context is not an Application")
        return AniyomiHostRuntime(application, networkHelper, registry)
    }
}
