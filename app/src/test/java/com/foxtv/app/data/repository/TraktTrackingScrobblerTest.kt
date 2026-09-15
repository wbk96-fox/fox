package com.foxtv.app.data.repository

import com.foxtv.app.core.tracking.TrackingExternalIds
import com.foxtv.app.core.tracking.TrackingMediaKind
import com.foxtv.app.core.tracking.TrackingMediaReference
import com.foxtv.app.core.tracking.TrackingScrobbleAction
import com.foxtv.app.core.tracking.TrackingScrobbleEvent
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TraktTrackingScrobblerTest {
    @Test
    fun `pause preserves trakt stop behavior`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = TraktTrackingScrobbler(
            service = service,
            episodeMappingService = mockk(relaxed = true)
        )

        scrobbler.scrobble(
            action = TrackingScrobbleAction.PAUSE,
            event = TrackingScrobbleEvent(
                media = TrackingMediaReference(
                    kind = TrackingMediaKind.MOVIE,
                    ids = TrackingExternalIds(imdb = "tt0111161")
                ),
                progressPercent = 45.0
            )
        )

        coVerify(exactly = 1) { service.scrobbleStop(any(), 45f) }
        coVerify(exactly = 0) { service.scrobblePause(any(), any()) }
    }
}
