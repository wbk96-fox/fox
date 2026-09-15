package com.foxtv.app.core.plugin.aniyomi.host

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.api.hasFactory

internal interface AniyomiHostRegistry {
    fun hasApplication(): Boolean
    fun getApplication(): Application
    fun addApplication(application: Application)
    fun hasNetworkHelper(): Boolean
    fun getNetworkHelper(): NetworkHelper
    fun addNetworkHelper(networkHelper: NetworkHelper)
}

internal class InjektAniyomiHostRegistry : AniyomiHostRegistry {
    override fun hasApplication(): Boolean = Injekt.hasFactory<Application>()
    override fun getApplication(): Application = Injekt.get()
    override fun addApplication(application: Application) = Injekt.addSingleton(application)

    override fun hasNetworkHelper(): Boolean = Injekt.hasFactory<NetworkHelper>()
    override fun getNetworkHelper(): NetworkHelper = Injekt.get()
    override fun addNetworkHelper(networkHelper: NetworkHelper) = Injekt.addSingleton(networkHelper)
}

/** Publishes the two exact parent services required by Jellyfin before plugin startup. */
class AniyomiHostRuntime internal constructor(
    private val application: Application,
    private val networkHelper: NetworkHelper,
    private val registry: AniyomiHostRegistry,
) {
    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return

            val hasApplication = registry.hasApplication()
            val hasNetworkHelper = registry.hasNetworkHelper()
            val registeredApplication = if (hasApplication) registry.getApplication() else null
            val registeredNetworkHelper = if (hasNetworkHelper) registry.getNetworkHelper() else null

            if (registeredApplication != null && registeredApplication !== application) {
                throw AniyomiHostBootstrapException(
                    "Injekt already contains a different Application instance",
                )
            }
            if (registeredNetworkHelper != null && registeredNetworkHelper !== networkHelper) {
                throw AniyomiHostBootstrapException(
                    "Injekt already contains a different NetworkHelper instance",
                )
            }

            if (!hasApplication) registry.addApplication(application)
            if (!hasNetworkHelper) registry.addNetworkHelper(networkHelper)
            initialized = true
        }
    }

    internal fun isInitialized(): Boolean = initialized

    private companion object {
        val initializationLock = Any()
    }
}

class AniyomiHostBootstrapException(message: String) : IllegalStateException(message)
