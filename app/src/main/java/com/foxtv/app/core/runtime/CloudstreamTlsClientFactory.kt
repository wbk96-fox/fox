package com.foxtv.app.core.runtime

import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS material scoped to the Cloudstream/NiceHttp client. The provider is passed directly to
 * [SSLContext], so creating this object never changes the process-wide JCA provider order.
 */
internal data class ScopedTlsMaterial(
    val provider: Provider,
    val trustManager: X509TrustManager,
    val sslContext: SSLContext,
    val socketFactory: SSLSocketFactory,
)

internal object CloudstreamTlsClientFactory {
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .singleOrNull()
            ?: throw IllegalStateException("The platform did not expose exactly one X509TrustManager")
    }

    fun createTlsMaterial(
        provider: Provider = Conscrypt.newProvider(),
        trustManager: X509TrustManager = systemTrustManager(),
    ): ScopedTlsMaterial {
        val sslContext = SSLContext.getInstance("TLS", provider)
        sslContext.init(null, arrayOf(trustManager), null)
        return ScopedTlsMaterial(
            provider = provider,
            trustManager = trustManager,
            sslContext = sslContext,
            socketFactory = sslContext.socketFactory,
        )
    }

    fun build(
        builder: OkHttpClient.Builder,
        tlsMaterial: ScopedTlsMaterial = createTlsMaterial(),
    ): OkHttpClient = builder
        .sslSocketFactory(tlsMaterial.socketFactory, tlsMaterial.trustManager)
        .build()
}

/**
 * Executes a publication step at most once. A failed step is deliberately not remembered, so a
 * later call can retry; the initialized flag is published only after the whole step succeeds.
 */
internal class RetriableInitializer {
    @Volatile
    private var initialized = false

    val isInitialized: Boolean
        get() = initialized

    fun ensure(initializer: () -> Unit) {
        if (initialized) return

        synchronized(this) {
            if (initialized) return
            initializer()
            initialized = true
        }
    }
}
