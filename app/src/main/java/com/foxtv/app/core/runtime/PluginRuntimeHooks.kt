package com.foxtv.app.core.runtime

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import com.foxtv.app.FoxTvApplication
import com.foxtv.app.core.network.IPv4FirstDns
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.app
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

private const val TAG = "PluginRuntimeHooks"

object PluginRuntimeHooks {
    @Volatile private var application: Application? = null

    private val cloudstreamInitializer = RetriableInitializer()

    fun onApplicationCreate(application: Application) {
        // Defer native TLS and baseClient initialization until a Cloudstream extension is used.
        this.application = application
        AcraApplication.context = application
    }

    /**
     * Lazily publishes the Cloudstream/NiceHttp client. Conscrypt is scoped to this one client:
     * it is never inserted into the process-wide Security provider list. If scoped Conscrypt is
     * unavailable on a device, OkHttp's secure platform TLS is used without weakening trust or
     * hostname verification. A complete failure remains retryable on the next invocation.
     */
    fun ensureCloudstreamInitialized() {
        val currentApp = application ?: return

        try {
            cloudstreamInitializer.ensure {
                val client = buildCloudstreamClient(currentApp)
                app.baseClient = client
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to initialize NiceHttp client (API ${Build.VERSION.SDK_INT}); will retry: ${e.message}",
                e,
            )
        } catch (e: LinkageError) {
            Log.w(
                TAG,
                "NiceHttp TLS linkage failure (API ${Build.VERSION.SDK_INT}); will retry: ${e.message}",
                e,
            )
        }
    }

    private fun buildCloudstreamClient(application: Application): OkHttpClient {
        return try {
            CloudstreamTlsClientFactory.build(baseClientBuilder(application))
        } catch (e: Exception) {
            Log.w(TAG, "Scoped Conscrypt unavailable; using secure platform TLS: ${e.message}")
            baseClientBuilder(application).build()
        } catch (e: LinkageError) {
            Log.w(TAG, "Scoped Conscrypt linkage unavailable; using secure platform TLS: ${e.message}")
            baseClientBuilder(application).build()
        }
    }

    private fun baseClientBuilder(application: Application): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .cookieJar(FoxTvApplication.extensionCookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .cache(
                Cache(
                    directory = File(application.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L,
                ),
            )

    fun onActivityCreate(activity: Activity) {
        AcraApplication.setActivity(activity)
    }

    fun onActivityDestroy() {
        AcraApplication.setActivity(null)
    }
}
