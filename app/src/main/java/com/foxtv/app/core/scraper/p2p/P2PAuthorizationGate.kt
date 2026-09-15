package com.foxtv.app.core.scraper.p2p

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit authorization gate for public P2P indexes. Index discovery never implies
 * a right to consume the returned media. Playback requires an explicit user intent
 * (P2P enabled) and a user-owned transport/debrid path.
 */
@Singleton
class P2PAuthorizationGate @Inject constructor() {
    fun allowDiscovery(p2pEnabled: Boolean): Boolean = p2pEnabled
    fun allowPlayback(isUserSuppliedTransport: Boolean): Boolean = isUserSuppliedTransport
}
