package com.foxtv.app.core.plugin.aniyomi.di

import com.foxtv.app.core.plugin.aniyomi.AndroidAniyomiClassLoaderFactory
import com.foxtv.app.core.plugin.aniyomi.AndroidAniyomiPackageArchiveReader
import com.foxtv.app.core.plugin.aniyomi.AniyomiArtifactStore
import com.foxtv.app.core.plugin.aniyomi.AniyomiClassLoaderFactory
import com.foxtv.app.core.plugin.aniyomi.AniyomiCodeCacheStore
import com.foxtv.app.core.plugin.aniyomi.AniyomiExtensionApkInspector
import com.foxtv.app.core.plugin.aniyomi.AniyomiLoadBoundary
import com.foxtv.app.core.plugin.aniyomi.AniyomiLoadBoundaryStateStore
import com.foxtv.app.core.plugin.aniyomi.AniyomiLifecycleStateStore
import com.foxtv.app.core.plugin.aniyomi.AniyomiPackageArchiveReader
import com.foxtv.app.core.plugin.aniyomi.AniyomiLifecycleStartup
import com.foxtv.app.core.plugin.aniyomi.AniyomiSelectionChangeBarrier
import com.foxtv.app.core.plugin.aniyomi.AniyomiStartupGate
import com.foxtv.app.core.plugin.aniyomi.AniyomiStaticApkInspector
import com.foxtv.app.core.plugin.aniyomi.AniyomiTrustedPolicyProvider
import com.foxtv.app.core.plugin.aniyomi.FileAniyomiArtifactStore
import com.foxtv.app.core.plugin.aniyomi.FileAniyomiCodeCacheStore
import com.foxtv.app.core.plugin.aniyomi.FileAniyomiLifecycleStateStore
import com.foxtv.app.core.plugin.aniyomi.FileAniyomiLoadBoundaryStateStore
import com.foxtv.app.core.plugin.aniyomi.OfficialJellyfinAniyomiPolicyProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Dedicated Aniyomi inspection, staging and controlled M3 load-boundary dependencies. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AniyomiPluginModule {
    @Binds
    @Singleton
    abstract fun bindPackageArchiveReader(
        implementation: AndroidAniyomiPackageArchiveReader,
    ): AniyomiPackageArchiveReader

    @Binds
    @Singleton
    abstract fun bindStaticInspector(
        implementation: AniyomiExtensionApkInspector,
    ): AniyomiStaticApkInspector

    @Binds
    @Singleton
    abstract fun bindTrustedPolicyProvider(
        implementation: OfficialJellyfinAniyomiPolicyProvider,
    ): AniyomiTrustedPolicyProvider

    @Binds
    @Singleton
    abstract fun bindArtifactStore(
        implementation: FileAniyomiArtifactStore,
    ): AniyomiArtifactStore

    @Binds
    @Singleton
    abstract fun bindLifecycleStateStore(
        implementation: FileAniyomiLifecycleStateStore,
    ): AniyomiLifecycleStateStore

    @Binds
    @Singleton
    abstract fun bindLoadBoundaryStateStore(
        implementation: FileAniyomiLoadBoundaryStateStore,
    ): AniyomiLoadBoundaryStateStore

    @Binds
    @Singleton
    abstract fun bindCodeCacheStore(
        implementation: FileAniyomiCodeCacheStore,
    ): AniyomiCodeCacheStore

    @Binds
    @Singleton
    abstract fun bindClassLoaderFactory(
        implementation: AndroidAniyomiClassLoaderFactory,
    ): AniyomiClassLoaderFactory

    @Binds
    @Singleton
    abstract fun bindStartupGate(
        implementation: AniyomiLifecycleStartup,
    ): AniyomiStartupGate

    @Binds
    @Singleton
    abstract fun bindSelectionChangeBarrier(
        implementation: AniyomiLoadBoundary,
    ): AniyomiSelectionChangeBarrier
}
