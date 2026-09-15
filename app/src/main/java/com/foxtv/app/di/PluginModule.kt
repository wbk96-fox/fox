package com.foxtv.app.di

import com.foxtv.app.core.auth.AuthManager
import com.foxtv.app.core.plugin.PluginManager
import com.foxtv.app.core.plugin.PluginRuntime
import com.foxtv.app.core.plugin.cloudstream.ExternalExtensionLoader
import com.foxtv.app.core.plugin.cloudstream.ExternalExtensionRunner
import com.foxtv.app.core.plugin.cloudstream.ExternalPluginLifecycleCoordinator
import com.foxtv.app.core.plugin.cloudstream.ExternalRepoParser
import com.foxtv.app.core.plugin.cloudstream.ExternalRepositorySynchronizer
import com.foxtv.app.core.sync.PluginSyncService
import com.foxtv.app.data.local.PluginDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun providePluginRuntime(): PluginRuntime {
        return PluginRuntime()
    }

    @Provides
    @Singleton
    fun providePluginManager(
        dataStore: PluginDataStore,
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager,
        externalRepoParser: ExternalRepoParser,
        externalExtensionLoader: ExternalExtensionLoader,
        externalExtensionRunner: ExternalExtensionRunner,
        externalPluginLifecycle: ExternalPluginLifecycleCoordinator,
        externalRepositorySynchronizer: ExternalRepositorySynchronizer,
    ): PluginManager {
        return PluginManager(
            dataStore, runtime, pluginSyncService, authManager,
            externalRepoParser, externalExtensionLoader, externalExtensionRunner,
            externalPluginLifecycle, externalRepositorySynchronizer,
        )
    }
}
