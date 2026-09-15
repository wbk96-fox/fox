package com.foxtv.app.ui.screens.settings

import com.foxtv.app.data.simkl.SimklAuthState
import com.foxtv.app.data.simkl.SimklConnectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklSettingsConnectionPolicyTest {
    @Test
    fun `connected account with missing identity requests recovery`() {
        assertTrue(
            shouldRefreshMissingSimklIdentity(
                state = SimklAuthState(isAuthenticated = true),
                isRefreshBlocked = false
            )
        )
    }

    @Test
    fun `identity recovery skips known disconnected and blocked states`() {
        assertFalse(
            shouldRefreshMissingSimklIdentity(
                state = SimklAuthState(),
                isRefreshBlocked = false
            )
        )
        assertFalse(
            shouldRefreshMissingSimklIdentity(
                state = SimklAuthState(isAuthenticated = true, username = "Viewer"),
                isRefreshBlocked = false
            )
        )
        assertFalse(
            shouldRefreshMissingSimklIdentity(
                state = SimklAuthState(isAuthenticated = true),
                isRefreshBlocked = true
            )
        )
    }

    @Test
    fun `connected transition keeps pin polling alive for identity fetch`() {
        assertFalse(shouldCancelSimklPolling(SimklConnectionMode.CONNECTED))
        assertFalse(shouldCancelSimklPolling(SimklConnectionMode.AWAITING_APPROVAL))
        assertTrue(shouldCancelSimklPolling(SimklConnectionMode.DISCONNECTED))
    }
}
