package com.foxtv.app.core.di

import com.foxtv.app.data.repository.AddonRepositoryImpl
import com.foxtv.app.data.repository.CatalogRepositoryImpl
import com.foxtv.app.data.repository.LibraryRepositoryImpl
import com.foxtv.app.data.repository.MetaRepositoryImpl
import com.foxtv.app.data.repository.StreamRepositoryImpl
import com.foxtv.app.data.repository.SubtitleRepositoryImpl
import com.foxtv.app.data.repository.SyncRepositoryImpl
import com.foxtv.app.data.repository.WatchProgressRepositoryImpl
import com.foxtv.app.domain.repository.AddonRepository
import com.foxtv.app.domain.repository.CatalogRepository
import com.foxtv.app.domain.repository.LibraryRepository
import com.foxtv.app.domain.repository.MetaRepository
import com.foxtv.app.domain.repository.StreamRepository
import com.foxtv.app.domain.repository.SubtitleRepository
import com.foxtv.app.domain.repository.SyncRepository
import com.foxtv.app.domain.repository.WatchProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAddonRepository(impl: AddonRepositoryImpl): AddonRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindMetaRepository(impl: MetaRepositoryImpl): MetaRepository

    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: StreamRepositoryImpl): StreamRepository

    @Binds
    @Singleton
    abstract fun bindSubtitleRepository(impl: SubtitleRepositoryImpl): SubtitleRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindWatchProgressRepository(impl: WatchProgressRepositoryImpl): WatchProgressRepository
}
