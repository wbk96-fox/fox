package com.foxtv.app.core.plugin.cloudstream

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Owner-aware lifecycle for providers registered in Cloudstream's process-wide APIHolder. */
@Singleton
class CloudstreamProviderRegistry @Inject constructor() {
    class Snapshot internal constructor(
        private val providers: Set<MainAPI>,
    ) {
        internal fun contains(provider: MainAPI): Boolean = providers.contains(provider)
    }

    private val lock = Any()
    private val ownersByProvider = IdentityHashMap<MainAPI, MutableSet<String>>()

    /** Captures the identity union because Cloudstream exposes two independent provider views. */
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(currentProviderIdentities())
    }

    /**
     * Claims every global identity added since [before]. Declared providers which predate the
     * snapshot are claimed only when this registry already owns that exact external identity.
     */
    fun claimNewRegistrations(
        ownerId: String,
        before: Snapshot,
        declaredProviders: List<MainAPI> = emptyList(),
    ) = synchronized(lock) {
        val additions = newIdentitySet()
        currentProviderIdentities().forEach { provider ->
            if (!before.contains(provider)) additions += provider
        }

        additions.forEach { provider -> addOwner(provider, ownerId) }
        declaredProviders.forEach { provider ->
            if (additions.contains(provider) || ownersByProvider.containsKey(provider)) {
                addOwner(provider, ownerId)
            }
        }
    }

    /** Rolls back exact provider identities added after [before] from every APIHolder view. */
    fun removeRegistrationsAddedAfter(before: Snapshot) = synchronized(lock) {
        val additions = currentProviderIdentities().filterNot(before::contains)
        additions.forEach { provider ->
            ownersByProvider.remove(provider)
            removeFromGlobalViews(provider)
        }
    }

    /** Removes an owner's providers only after the last owner of each exact identity is gone. */
    fun unregisterOwner(ownerId: String) = synchronized(lock) {
        val providersToRemove = mutableListOf<MainAPI>()
        val iterator = ownersByProvider.entries.iterator()
        while (iterator.hasNext()) {
            val (provider, owners) = iterator.next()
            owners.remove(ownerId)
            if (owners.isEmpty()) {
                providersToRemove += provider
                iterator.remove()
            }
        }
        providersToRemove.forEach(::removeFromGlobalViews)
    }

    private fun addOwner(provider: MainAPI, ownerId: String) {
        val owners = ownersByProvider[provider]
            ?: mutableSetOf<String>().also { ownersByProvider[provider] = it }
        owners += ownerId
    }

    private fun currentProviderIdentities(): MutableSet<MainAPI> {
        val providers = newIdentitySet()
        synchronized(APIHolder.allProviders) {
            providers.addAll(APIHolder.allProviders)
        }
        val mappedProviders = APIHolder.apis
        synchronized(mappedProviders) {
            providers.addAll(mappedProviders)
        }
        return providers
    }

    private fun removeFromGlobalViews(provider: MainAPI) {
        // This is the supported Cloudstream API for keeping apis and apiMap consistent.
        APIHolder.removePluginMapping(provider)
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.removeAll { it === provider }
        }
    }

    private fun newIdentitySet(): MutableSet<MainAPI> =
        Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())
}
