package com.foxtv.app.core.fanfilm.di

import com.foxtv.app.core.fanfilm.FanFilmPlaybackStateProvider
import com.foxtv.app.core.fanfilm.NoPlaybackStateProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wiring for the FanFilm runtime.
 *
 * Everything else in `core.fanfilm` is constructor-injected `@Singleton`, so only the
 * one interface needs an explicit binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FanFilmModule {

    /**
     * Live playback state for the Kodi compatibility layer.
     *
     * FanFilm's service polls `Player.*` info labels. FOX.TV answers them from
     * [com.foxtv.app.core.fanfilm.FanFilmPlaybackStateRegistry], which the player
     * populates while a session is active and clears when it ends; before the first
     * playback (and in unit tests) it reports "nothing playing", which is exactly what
     * Kodi returns in that situation.
     */
    @Binds
    @Singleton
    abstract fun bindPlaybackStateProvider(
        implementation: NoPlaybackStateProvider,
    ): FanFilmPlaybackStateProvider
}
