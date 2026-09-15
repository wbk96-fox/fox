package com.foxtv.app.core.di

import android.content.Context
import com.foxtv.app.core.torrent.TorrServerApi
import com.foxtv.app.core.torrent.TorrServerBinary
import com.foxtv.app.core.torrent.TorrentService
import com.foxtv.app.core.torrent.TorrentSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TorrentModule {

    @Provides
    @Singleton
    fun provideTorrentSettings(
        @ApplicationContext context: Context
    ): TorrentSettings = TorrentSettings(context)

    @Provides
    @Singleton
    fun provideTorrServerBinary(
        @ApplicationContext context: Context
    ): TorrServerBinary = TorrServerBinary(context)

    @Provides
    @Singleton
    fun provideTorrServerApi(
        binary: TorrServerBinary
    ): TorrServerApi = TorrServerApi(binary)

    @Provides
    @Singleton
    fun provideTorrentService(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
        binary: TorrServerBinary,
        api: TorrServerApi
    ): TorrentService = TorrentService(appContext, binary, api)
}
