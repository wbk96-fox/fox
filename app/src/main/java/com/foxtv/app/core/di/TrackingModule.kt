package com.foxtv.app.core.di

import com.foxtv.app.data.simkl.AndroidSimklAuthStorage
import com.foxtv.app.data.simkl.AndroidSimklSyncStorage
import com.foxtv.app.data.simkl.SimklAuthStorage
import com.foxtv.app.data.simkl.SimklApiSyncRemote
import com.foxtv.app.data.simkl.SimklSyncRemote
import com.foxtv.app.data.simkl.SimklSyncStorage
import com.foxtv.app.core.tracking.TrackingLibraryProvider
import com.foxtv.app.core.tracking.TrackingProvider
import com.foxtv.app.data.repository.TraktTrackingLibraryProvider
import com.foxtv.app.data.repository.TraktTrackingProvider
import com.foxtv.app.data.simkl.SimklLibraryService
import com.foxtv.app.core.tracking.TrackingHistoryWriter
import com.foxtv.app.core.tracking.TrackingProgressProvider
import com.foxtv.app.data.repository.TraktTrackingHistoryWriter
import com.foxtv.app.data.repository.TraktTrackingProgressProvider
import com.foxtv.app.data.simkl.SimklTrackingHistoryWriter
import com.foxtv.app.data.simkl.SimklTrackingProgressProvider
import com.foxtv.app.data.simkl.SimklTrackingProvider
import com.foxtv.app.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {
    @Binds
    @Singleton
    abstract fun bindSimklAuthStorage(storage: AndroidSimklAuthStorage): SimklAuthStorage

    @Binds
    @IntoSet
    abstract fun bindSimklProfileScopedCredentialStore(
        storage: AndroidSimklAuthStorage
    ): ProfileScopedCredentialStore

    @Binds
    @Singleton
    abstract fun bindSimklSyncStorage(storage: AndroidSimklSyncStorage): SimklSyncStorage

    @Binds
    @Singleton
    abstract fun bindSimklSyncRemote(remote: SimklApiSyncRemote): SimklSyncRemote

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingLibraryProvider(
        provider: TraktTrackingLibraryProvider
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingLibraryProvider(
        provider: SimklLibraryService
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProgressProvider(
        provider: TraktTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProgressProvider(
        provider: SimklTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingHistoryWriter(
        writer: TraktTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingHistoryWriter(
        writer: SimklTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProvider(
        provider: TraktTrackingProvider
    ): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProvider(
        provider: SimklTrackingProvider
    ): TrackingProvider
}
