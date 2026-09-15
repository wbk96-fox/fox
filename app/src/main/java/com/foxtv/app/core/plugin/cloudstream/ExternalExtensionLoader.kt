package com.foxtv.app.core.plugin.cloudstream

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import com.foxtv.app.core.plugin.TestDiagnostics
import dalvik.system.DexClassLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.foxtv.app.core.network.IPv4FirstDns
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtensionLoader"

/**
 * Checks whether an instance looks like a CloudStream plugin by checking if it has
 * plugin-like methods. Covers three cases:
 * 1. Our Plugin class: load(Activity?), load(Context), getRegisteredMainAPIs()
 * 2. Library's BasePlugin: load() (no-arg), registerMainAPI()
 * 3. Foreign plugins with similar signatures
 */
private fun looksLikePlugin(instance: Any): Boolean {
    // Fast path: check if it's an instance of the library's BasePlugin
    if (instance is BasePlugin) return true

    val clazz = instance.javaClass
    // Check for any form of load() method
    val hasLoad = try {
        clazz.getMethod("load", Context::class.java) != null
    } catch (_: NoSuchMethodException) {
        try {
            clazz.getMethod("load", android.app.Activity::class.java) != null
        } catch (_: NoSuchMethodException) {
            try {
                // BasePlugin-style no-arg load()
                clazz.getMethod("load") != null
            } catch (_: NoSuchMethodException) {
                false
            }
        }
    }
    val hasRegisteredAPIs = try {
        clazz.getMethod("getRegisteredMainAPIs") != null
    } catch (_: NoSuchMethodException) {
        false
    }
    return hasLoad || hasRegisteredAPIs
}

private fun snapshotCloudstreamProviderIdentities(): Set<MainAPI> {
    val identities = Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())
    synchronized(APIHolder.allProviders) {
        identities.addAll(APIHolder.allProviders)
    }
    val mappedProviders = APIHolder.apis
    synchronized(mappedProviders) {
        identities.addAll(mappedProviders)
    }
    return identities
}

/**
 * Wraps a plugin instance loaded from a foreign classloader or with a non-standard base class.
 * Handles three plugin patterns:
 * 1. Our Plugin: load(Activity?), load(Context), getRegisteredMainAPIs()
 * 2. Library's BasePlugin: load() no-arg, registers to APIHolder.allProviders + extractorApis
 * 3. Foreign plugins with similar signatures
 */
private class ReflectivePluginWrapper(private val foreignInstance: Any) : Plugin() {
    override fun load(activity: android.app.Activity?) {
        load(activity as? Context ?: AcraApplication.context ?: return)
    }

    override fun load(context: Context) {
        // Snapshot global registries before load() to detect what this plugin adds
        val providersBefore = snapshotCloudstreamProviderIdentities()
        val extractorsBefore = synchronized(extractorApis) { extractorApis.toList() }

        val clazz = foreignInstance.javaClass
        var loaded = false

        // Try load(Context) first
        try {
            val m = clazz.getMethod("load", Context::class.java)
            m.invoke(foreignInstance, context)
            loaded = true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is ClassCastException) {
                Log.d(TAG, "ReflectivePluginWrapper: load(Context) got ClassCastException, retrying with null Activity")
                try {
                    val m = clazz.getMethod("load", android.app.Activity::class.java)
                    m.invoke(foreignInstance, null)
                    loaded = true
                } catch (e2: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load(Activity) also failed: ${e2.message}")
                }
            } else {
                Log.w(TAG, "ReflectivePluginWrapper: load(Context) threw: ${cause?.message ?: e.message}")
            }
        } catch (_: NoSuchMethodException) {
            // Try load(Activity?) next
            try {
                val m = clazz.getMethod("load", android.app.Activity::class.java)
                m.invoke(foreignInstance, null)
                loaded = true
            } catch (_: NoSuchMethodException) {
                // Try no-arg load() (BasePlugin pattern)
                try {
                    val m = clazz.getMethod("load")
                    m.invoke(foreignInstance)
                    loaded = true
                    Log.d(TAG, "ReflectivePluginWrapper: loaded via no-arg load()")
                } catch (e: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load() (no-arg) failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ReflectivePluginWrapper: load(Activity) failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReflectivePluginWrapper: load() failed: ${e.message}")
        }

        // First try reading local registration lists (our Plugin pattern)
        try {
            val getter = clazz.getMethod("getRegisteredMainAPIs")
            @Suppress("UNCHECKED_CAST")
            val apis = getter.invoke(foreignInstance) as? List<MainAPI> ?: emptyList()
            apis.forEach { registerMainAPI(it) }
        } catch (_: Exception) {
            // No local list — check global APIHolder for newly registered providers
            // (BasePlugin.registerMainAPI adds to APIHolder.allProviders)
            val newProviders = snapshotCloudstreamProviderIdentities()
                .filterNot(providersBefore::contains)
            if (newProviders.isNotEmpty()) {
                Log.d(TAG, "ReflectivePluginWrapper: found ${newProviders.size} providers via APIHolder")
                newProviders.forEach { registerMainAPI(it) }
            }
        }

        try {
            val getter = clazz.getMethod("getRegisteredExtractorAPIs")
            @Suppress("UNCHECKED_CAST")
            val extractors = getter.invoke(foreignInstance) as? List<ExtractorApi> ?: emptyList()
            extractors.forEach { registerExtractorAPI(it) }
        } catch (_: Exception) {
            // Check global extractorApis for newly registered extractors
            val newExtractors = extractorApis.toList() - extractorsBefore.toSet()
            if (newExtractors.isNotEmpty()) {
                Log.d(TAG, "ReflectivePluginWrapper: found ${newExtractors.size} extractors via extractorApis")
                newExtractors.forEach { registerExtractorAPI(it) }
            }
        }
    }
}

/**
 * Manages downloading, loading, and caching of DEX-based external extensions (.cs3 files).
 */
@Singleton
class ExternalExtensionLoader(
    @param:ApplicationContext private val context: Context,
    private val extractorRegistry: ExternalExtractorRegistry,
    private val providerRegistry: CloudstreamProviderRegistry,
    httpClient: OkHttpClient,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        extractorRegistry: ExternalExtractorRegistry,
        providerRegistry: CloudstreamProviderRegistry,
    ) : this(
        context = context,
        extractorRegistry = extractorRegistry,
        providerRegistry = providerRegistry,
        httpClient = OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .build(),
    )

    private val httpClient = httpClient.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    /** Cache of loaded MainAPI instances by scraper ID */
    private val apiCache = ConcurrentHashMap<String, MainAPI>()

    /** Cache of loaded class loaders by scraper ID */
    private val classLoaderCache = ConcurrentHashMap<String, DexClassLoader>()

    /** Tracks which scraper IDs have already been scanned for extractors */
    private val extractorPreloadedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class GlobalRegistrySnapshot(
        val providers: CloudstreamProviderRegistry.Snapshot,
        val extractors: List<ExtractorApi>,
    )

    internal data class PreparedExternalExtension(
        val scraperId: String,
        val stagedArtifact: StagedExternalExtension,
        val byteCount: Long,
    )

    private val disabledOwnerIds = mutableSetOf<String>()

    @Volatile
    private var globallyEnabled = true

    private val extensionsDir: File
        get() = File(context.filesDir, "cs_extensions").also { it.mkdirs() }

    private val codeCacheDir: File
        get() = File(context.codeCacheDir, "cs_dex_cache").also { it.mkdirs() }

    /** Sanitize scraper ID for use as a filename (colons are not safe on all filesystems). */
    private fun safeFileName(scraperId: String): String =
        scraperId.replace(':', '_').replace('/', '_')

    /**
     * Downloads and fully validates a .cs3 without changing the active file or caches. Only HTTPS
     * is accepted at both the requested and effective response URL.
     */
    internal suspend fun stageExtension(
        scraperId: String,
        plugin: com.foxtv.app.domain.model.ExternalPluginEntry,
    ): PreparedExternalExtension? = withContext(Dispatchers.IO) {
        com.foxtv.app.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()
        val expectedSize = plugin.fileSize
        val expectedHash = plugin.fileHash
            ?.takeIf { it.startsWith("sha256-") }
            ?.removePrefix("sha256-")
            ?.lowercase()
        if (expectedSize == null || expectedHash == null) {
            Log.e(TAG, "Rejected extension $scraperId without verified size/hash metadata")
            return@withContext null
        }

        val artifactUrl = plugin.url.trim().toHttpUrlOrNull()?.takeIf { it.isHttps }
        if (artifactUrl == null) {
            Log.e(TAG, "Rejected non-HTTPS extension URL for $scraperId")
            return@withContext null
        }

        try {
            val targetFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
            val request = Request.Builder()
                .url(artifactUrl)
                .header("User-Agent", "FOX.TV/1.0")
                .build()
            val call = httpClient.newCall(request)

            call.awaitResponse().use { response ->
                if (!response.request.url.isHttps) {
                    Log.e(TAG, "Rejected non-HTTPS final extension URL for $scraperId")
                    return@withContext null
                }
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download extension $scraperId: HTTP ${response.code}")
                    return@withContext null
                }
                currentCoroutineContext().ensureActive()
                val body = response.body
                val staged = ExternalExtensionInstaller.stageAndValidate(
                    targetFile = targetFile,
                    source = body.byteStream(),
                    declaredContentLength = body.contentLength(),
                    spec = ExternalExtensionArtifactSpec(
                        expectedSize = expectedSize,
                        expectedSha256 = expectedHash,
                    ),
                    loadabilityValidator = { stagedFile ->
                        synchronized(this@ExternalExtensionLoader) {
                            val stagedClassLoader = DexClassLoader(
                                stagedFile.absolutePath,
                                codeCacheDir.absolutePath,
                                null,
                                context.classLoader,
                            )
                            val snapshot = snapshotGlobalRegistries()
                            try {
                                findAndLoadPlugin(stagedClassLoader, stagedFile) != null
                            } finally {
                                removeRegistrationsAddedAfter(snapshot)
                            }
                        }
                    },
                )
                PreparedExternalExtension(
                    scraperId = scraperId,
                    stagedArtifact = staged,
                    byteCount = staged.stagedFile.length(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error staging extension $scraperId: ${e.message}", e)
            null
        } catch (e: LinkageError) {
            Log.e(TAG, "Incompatible extension $scraperId: ${e.message}", e)
            null
        }
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response) { _, responseToClose, _ ->
                            responseToClose.close()
                        }
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

    /**
     * Load a .cs3 DEX file and return the MainAPI instance(s) registered by the plugin.
     */
    @Synchronized
    fun loadExtension(scraperId: String): List<MainAPI> {
        if (!isOwnerLoadAllowed(scraperId)) {
            Log.w(TAG, "Refusing to load disabled extension: $scraperId")
            return emptyList()
        }
        // Check cache first
        apiCache[scraperId]?.let { return listOf(it) }
        com.foxtv.app.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()

        val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
        if (!dexFile.exists()) {
            Log.e(TAG, "DEX file not found for $scraperId: ${dexFile.absolutePath}")
            return emptyList()
        }

        // Ensure DEX file is read-only (fix for existing downloads on API 28+)
        ensureDexReadOnly(dexFile)

        val registrySnapshot = snapshotGlobalRegistries()
        var retainGlobalRegistrations = false
        var declaredProviders = emptyList<MainAPI>()
        return try {
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                codeCacheDir.absolutePath,
                null,
                context.classLoader
            )
            classLoaderCache[scraperId] = classLoader

            // Check for critical class shadowing
            try {
                @Suppress("DEPRECATION")
                val inspectDex = dalvik.system.DexFile(dexFile)
                val allEntries = inspectDex.entries().toList()
                inspectDex.close()
                val criticalShadows = allEntries.filter { className ->
                    className == "com.lagradost.cloudstream3.MainActivityKt" ||
                    className == "com.lagradost.cloudstream3.MainAPIKt" ||
                    className == "com.lagradost.cloudstream3.utils.ExtractorApiKt" ||
                    className == "com.lagradost.cloudstream3.utils.AppUtilsKt"
                }
                if (criticalShadows.isNotEmpty()) {
                    Log.w(TAG, "Extension $scraperId shadows critical classes: $criticalShadows")
                }
            } catch (_: Exception) {}

            // Find and instantiate the plugin class
            val plugin = findAndLoadPlugin(classLoader, dexFile)
            if (plugin == null) {
                Log.e(TAG, "No @CloudstreamPlugin class found in $scraperId")
                return emptyList()
            }

            // Ensure global stubs are initialized for extensions
            AcraApplication.context = context
            extractorRegistry.installGlobal()

            // Call load() to trigger registerMainAPI() calls.
            // Dispatch on Context so plugins that override load(Context) — upstream's
            // primary overload — get their impl invoked. Plugins that only override
            // load(Activity?) or no-arg load() still work: stub's load(Context) casts
            // the arg to Activity? and chains through.
            val activity = AcraApplication.getActivity()
            try {
                plugin.load((activity as Context?) ?: context)
            } catch (e: Exception) {
                Log.w(TAG, "plugin.load() threw (partial load, ${plugin.registeredMainAPIs.size} APIs so far): ${e.message}", e)
            } catch (e: Error) {
                val missingClass = extractMissingClassName(e)
                if (missingClass != null) {
                    Log.w(TAG, "plugin.load() MISSING CLASS: $missingClass (${plugin.registeredMainAPIs.size} APIs so far)", e)
                } else {
                    Log.w(TAG, "plugin.load() linkage error (partial load, ${plugin.registeredMainAPIs.size} APIs so far): ${e.message}", e)
                }
            }

            // Register any extractors the plugin provides
            extractorRegistry.registerAll(scraperId, plugin.registeredExtractorAPIs)

            var apis = plugin.registeredMainAPIs
            Log.d(TAG, "After load(): ${apis.size} MainAPIs, ${plugin.registeredExtractorAPIs.size} extractors")

            // FALLBACK: If load() registered 0 APIs or 0 extractors, scan DEX directly.
            if (apis.isEmpty() || plugin.registeredExtractorAPIs.isEmpty()) {
                Log.d(TAG, "Fallback: scanning DEX for MainAPI/ExtractorApi subclasses in $scraperId")
                val fallbackApis = mutableListOf<MainAPI>()
                val fallbackExtractors = mutableListOf<ExtractorApi>()
                try {
                    @Suppress("DEPRECATION")
                    val inspectDex = dalvik.system.DexFile(dexFile)
                    val allClasses = inspectDex.entries().toList()
                    inspectDex.close()

                    val candidates = allClasses.filter { className ->
                        !className.contains('$') &&
                        !className.contains("Plugin") &&
                        !className.contains("Fragment") &&
                        className.startsWith("com.")
                    }

                    for (className in candidates) {
                        try {
                            val clazz = classLoader.loadClass(className)
                            if (apis.isEmpty()
                                && MainAPI::class.java.isAssignableFrom(clazz)
                                && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)
                                && !clazz.isInterface) {
                                val instance = clazz.getDeclaredConstructor().newInstance() as MainAPI
                                fallbackApis.add(instance)
                                Log.d(TAG, "Fallback found MainAPI: ${instance.name} ($className)")
                            } else if (ExtractorApi::class.java.isAssignableFrom(clazz)
                                && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {
                                val instance = clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                                fallbackExtractors.add(instance)
                                Log.d(TAG, "Fallback found ExtractorApi: ${instance.name} (${instance.mainUrl})")
                            }
                        } catch (e: Error) {
                            val missing = extractMissingClassName(e)
                            if (missing != null) {
                                Log.w(TAG, "Fallback skip $className: MISSING $missing")
                            }
                        } catch (_: Exception) {
                            // Skip classes that can't be instantiated
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback DEX scan failed: ${e.message}")
                }

                if (fallbackApis.isNotEmpty()) {
                    Log.d(TAG, "Fallback found ${fallbackApis.size} MainAPIs")
                    apis = fallbackApis
                }
                if (fallbackExtractors.isNotEmpty()) {
                    Log.d(TAG, "Fallback found ${fallbackExtractors.size} ExtractorApis")
                    extractorRegistry.registerAll(scraperId, fallbackExtractors)
                }
            }

            declaredProviders = apis
            apis.forEach { api ->
                apiCache["$scraperId:${api.name}"] = api
            }

            // Also cache the first API under the plain scraper ID
            if (apis.isNotEmpty()) {
                apiCache[scraperId] = apis.first()
            }

            Log.d(TAG, "Loaded extension $scraperId: ${apis.size} providers (${apis.joinToString { it.name }})")
            retainGlobalRegistrations = apis.isNotEmpty()
            apis
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load extension $scraperId: ${e.message}", e)
            emptyList()
        } catch (e: Error) {
            val missingClass = extractMissingClassName(e)
            if (missingClass != null) {
                Log.e(TAG, "Failed to load extension $scraperId: MISSING CLASS: $missingClass (${e.javaClass.simpleName})", e)
            } else {
                Log.e(TAG, "Failed to load extension $scraperId (linkage error): ${e.message}", e)
            }
            emptyList()
        } finally {
            finishRegistryCapture(
                scraperId,
                registrySnapshot,
                retainGlobalRegistrations,
                declaredProviders,
            )
        }
    }

    /**
     * Get a cached MainAPI for the given scraper ID, loading if necessary.
     */
    fun getApi(scraperId: String): MainAPI? {
        if (!isOwnerLoadAllowed(scraperId)) return null
        return apiCache[scraperId] ?: run {
            val apis = loadExtension(scraperId)
            apis.firstOrNull()
        }
    }

    /**
     * Load extension with diagnostic output.
     */
    @Synchronized
    fun loadExtensionWithDiagnostics(scraperId: String, diagnostics: TestDiagnostics): List<MainAPI> {
        if (!isOwnerLoadAllowed(scraperId)) {
            diagnostics.addStep("Extension disabled; load refused")
            return emptyList()
        }
        apiCache[scraperId]?.let {
            diagnostics.addStep("MainAPI cached: ${it.name}")
            return listOf(it)
        }
        com.foxtv.app.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()

        val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
        if (!dexFile.exists()) {
            diagnostics.addStep("DEX file NOT FOUND: ${dexFile.name}")
            return emptyList()
        }
        diagnostics.addStep("DEX: ${dexFile.length()} bytes")

        // Ensure read-only
        ensureDexReadOnly(dexFile)

        val registrySnapshot = snapshotGlobalRegistries()
        var retainGlobalRegistrations = false
        var declaredProviders = emptyList<MainAPI>()
        return try {
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                codeCacheDir.absolutePath,
                null,
                context.classLoader
            )
            classLoaderCache[scraperId] = classLoader

            val allClasses: List<String>
            try {
                @Suppress("DEPRECATION")
                val inspectDex = dalvik.system.DexFile(dexFile)
                allClasses = inspectDex.entries().toList()
                inspectDex.close()
            } catch (e: Exception) {
                diagnostics.addStep("DEX inspection failed: ${e.message?.take(100)}")
                return emptyList()
            }

            val shadowsPlugin = allClasses.any { it == "com.lagradost.cloudstream3.plugins.Plugin" }
            if (shadowsPlugin) {
                diagnostics.addStep("WARNING: DEX contains its own Plugin class!")
            }

            val plugin = findAndLoadPlugin(classLoader, dexFile)
            if (plugin == null) {
                diagnostics.addStep("No @CloudstreamPlugin found in DEX")
                return emptyList()
            }

            val sameClass = plugin.javaClass.superclass == Plugin::class.java
            diagnostics.addStep("Plugin: ${plugin.javaClass.simpleName}, sameBaseClass=$sameClass, isWrapper=${plugin is ReflectivePluginWrapper}")

            AcraApplication.context = context
            extractorRegistry.installGlobal()

            val activity = AcraApplication.getActivity()
            try {
                plugin.load((activity as Context?) ?: context)
                diagnostics.addStep("load(Context): OK, ${plugin.registeredMainAPIs.size} APIs")
            } catch (e: Exception) {
                diagnostics.addStep("load() FAILED: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            } catch (e: Error) {
                val missing = extractMissingClassName(e)
                diagnostics.addStep("load() ERROR: ${missing ?: e.message?.take(120)}")
            }

            extractorRegistry.registerAll(scraperId, plugin.registeredExtractorAPIs)

            var apis = plugin.registeredMainAPIs

            if (apis.isEmpty() || plugin.registeredExtractorAPIs.isEmpty()) {
                diagnostics.addStep("Fallback: scanning DEX for MainAPI + ExtractorApi subclasses...")
                val fallbackApis = mutableListOf<MainAPI>()
                val fallbackExtractors = mutableListOf<ExtractorApi>()
                val candidates = allClasses.filter { className ->
                    !className.contains('$') &&
                    !className.contains("Plugin") &&
                    !className.contains("Fragment") &&
                    className.startsWith("com.")
                }

                for (className in candidates) {
                    try {
                        val clazz = classLoader.loadClass(className)
                        if (apis.isEmpty()
                            && MainAPI::class.java.isAssignableFrom(clazz)
                            && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)
                            && !clazz.isInterface) {
                            val instance = clazz.getDeclaredConstructor().newInstance() as MainAPI
                            fallbackApis.add(instance)
                            diagnostics.addStep("Found API: ${instance.name} (${clazz.simpleName})")
                        } else if (ExtractorApi::class.java.isAssignableFrom(clazz)
                            && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {
                            val instance = clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                            fallbackExtractors.add(instance)
                            diagnostics.addStep("Found Extractor: ${instance.name} (${instance.mainUrl})")
                        }
                    } catch (e: Error) {
                        val missing = extractMissingClassName(e)
                        if (missing != null) {
                            diagnostics.addStep("${className.substringAfterLast('.')}: MISSING $missing")
                        }
                    } catch (e: Exception) {
                        val cause = e.cause ?: e
                        if (cause is Error) {
                            val missing = extractMissingClassName(cause as Error)
                            if (missing != null) {
                                diagnostics.addStep("${className.substringAfterLast('.')}: MISSING $missing")
                            }
                        }
                    }
                }

                if (fallbackApis.isNotEmpty()) {
                    diagnostics.addStep("Fallback found ${fallbackApis.size} APIs")
                    apis = fallbackApis
                }
                if (fallbackExtractors.isNotEmpty()) {
                    diagnostics.addStep("Fallback found ${fallbackExtractors.size} extractors")
                    extractorRegistry.registerAll(scraperId, fallbackExtractors)
                }
            }

            declaredProviders = apis
            apis.forEach { api ->
                apiCache["$scraperId:${api.name}"] = api
            }
            if (apis.isNotEmpty()) {
                apiCache[scraperId] = apis.first()
            }

            retainGlobalRegistrations = apis.isNotEmpty()
            apis
        } catch (e: Exception) {
            diagnostics.addStep("FAILED: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            emptyList()
        } catch (e: Error) {
            val missing = extractMissingClassName(e)
            diagnostics.addStep("FAILED: ${missing ?: e.message?.take(200)}")
            emptyList()
        } finally {
            finishRegistryCapture(
                scraperId,
                registrySnapshot,
                retainGlobalRegistrations,
                declaredProviders,
            )
        }
    }

    /**
     * Eagerly load all ExtractorApi subclasses from the given .cs3 files.
     */
    @Synchronized
    fun ensureExtractorsLoaded(scraperIds: List<String>, diagnostics: TestDiagnostics? = null) {
        com.foxtv.app.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()
        val idsToLoad = scraperIds.distinct().filter {
            isOwnerLoadAllowed(it) && it !in extractorPreloadedIds
        }
        if (idsToLoad.isEmpty()) {
            diagnostics?.addStep("Extractors: all ${scraperIds.size} already preloaded")
            return
        }

        AcraApplication.context = context
        extractorRegistry.installGlobal()

        var totalExtractors = 0
        var totalScanned = 0

        for (scraperId in idsToLoad) {
            val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
            if (!dexFile.exists()) continue
            ensureDexReadOnly(dexFile)

            val registrySnapshot = snapshotGlobalRegistries()
            var scanSucceeded = false
            var declaredProviders = emptyList<MainAPI>()
            totalScanned++
            try {
                val classLoader = classLoaderCache.getOrPut(scraperId) {
                    DexClassLoader(
                        dexFile.absolutePath,
                        codeCacheDir.absolutePath,
                        null,
                        context.classLoader,
                    )
                }

                val plugin = findAndLoadPlugin(classLoader, dexFile)
                if (plugin != null) {
                    val activity = AcraApplication.getActivity()
                    try {
                        plugin.load((activity as Context?) ?: context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Extractor preload load() failed for $scraperId: ${e.message}")
                    } catch (e: LinkageError) {
                        Log.w(TAG, "Extractor preload linkage failed for $scraperId: ${e.message}")
                    }
                    declaredProviders = plugin.registeredMainAPIs
                    if (plugin.registeredExtractorAPIs.isNotEmpty()) {
                        extractorRegistry.registerAll(scraperId, plugin.registeredExtractorAPIs)
                        totalExtractors += plugin.registeredExtractorAPIs.size
                        scanSucceeded = true
                    }
                }

                if (!scanSucceeded) {
                    @Suppress("DEPRECATION")
                    val inspectDex = dalvik.system.DexFile(dexFile)
                    val allClasses = try {
                        inspectDex.entries().toList()
                    } finally {
                        inspectDex.close()
                    }

                    for (className in allClasses) {
                        if (className.contains('$')) continue
                        try {
                            val clazz = classLoader.loadClass(className)
                            if (ExtractorApi::class.java.isAssignableFrom(clazz) &&
                                !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)
                            ) {
                                val instance = clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                                extractorRegistry.registerExtractor(scraperId, instance)
                                totalExtractors++
                            }
                        } catch (_: Exception) {
                        } catch (_: LinkageError) {
                        }
                    }
                    scanSucceeded = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensureExtractorsLoaded: failed for $scraperId: ${e.message}")
            } catch (e: LinkageError) {
                Log.w(TAG, "ensureExtractorsLoaded: linkage error for $scraperId: ${e.message}")
            } finally {
                if (scanSucceeded) {
                    claimRegistrationsAddedAfter(scraperId, registrySnapshot, declaredProviders)
                    extractorPreloadedIds.add(scraperId)
                } else {
                    removeRegistrationsAddedAfter(registrySnapshot)
                    classLoaderCache.remove(scraperId)
                    extractorPreloadedIds.remove(scraperId)
                }
            }
        }

        Log.d(TAG, "ensureExtractorsLoaded: scanned $totalScanned .cs3 files, registered $totalExtractors extractors")
        diagnostics?.addStep("Preloaded $totalExtractors extractors from $totalScanned .cs3 files")
    }

    /** Applies persisted per-owner enable state and immediately releases disabled globals. */
    @Synchronized
    fun setOwnersEnabled(ownerIds: Collection<String>, enabled: Boolean) {
        ownerIds.distinct().forEach { ownerId ->
            if (enabled) {
                disabledOwnerIds.remove(ownerId)
            } else {
                disabledOwnerIds.add(ownerId)
                evictCache(ownerId)
            }
        }
    }

    /** Applies the persisted global switch. Re-enabling remains lazy. */
    @Synchronized
    fun setGloballyEnabled(enabled: Boolean, installedOwnerIds: Collection<String>) {
        globallyEnabled = enabled
        if (!enabled) {
            installedOwnerIds.distinct().forEach(::evictCache)
        }
    }

    @Synchronized
    private fun isOwnerLoadAllowed(scraperId: String): Boolean =
        globallyEnabled && scraperId !in disabledOwnerIds

    @Synchronized
    fun deleteExtension(scraperId: String) {
        evictCache(scraperId)
        disabledOwnerIds.remove(scraperId)
        val file = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
        if (file.exists()) {
            file.setWritable(true)
            if (!file.delete()) {
                Log.w(TAG, "Could not delete extension file for $scraperId")
            }
        }
        Log.d(TAG, "Deleted extension $scraperId")
    }

    @Synchronized
    fun evictCache(scraperId: String) {
        apiCache.keys.filter { it == scraperId || it.startsWith("$scraperId:") }
            .forEach { apiCache.remove(it) }
        classLoaderCache.remove(scraperId)
        extractorPreloadedIds.remove(scraperId)
        unregisterOwnedRegistrations(scraperId)
    }

    private fun snapshotGlobalRegistries(): GlobalRegistrySnapshot = GlobalRegistrySnapshot(
        providers = providerRegistry.snapshot(),
        extractors = synchronized(extractorApis) { extractorApis.toList() },
    )

    private fun finishRegistryCapture(
        scraperId: String,
        before: GlobalRegistrySnapshot,
        retain: Boolean,
        declaredProviders: List<MainAPI> = emptyList(),
    ) {
        if (retain) {
            claimRegistrationsAddedAfter(scraperId, before, declaredProviders)
        } else {
            removeRegistrationsAddedAfter(before)
            apiCache.keys.filter { it == scraperId || it.startsWith("$scraperId:") }
                .forEach { apiCache.remove(it) }
            classLoaderCache.remove(scraperId)
            extractorPreloadedIds.remove(scraperId)
        }
    }

    private fun claimRegistrationsAddedAfter(
        scraperId: String,
        before: GlobalRegistrySnapshot,
        declaredProviders: List<MainAPI> = emptyList(),
    ) {
        providerRegistry.claimNewRegistrations(scraperId, before.providers, declaredProviders)
        val addedExtractors = synchronized(extractorApis) {
            extractorApis.filter { extractor -> before.extractors.none { it === extractor } }
        }
        extractorRegistry.claimNewRegistrations(scraperId, addedExtractors)
    }

    private fun removeRegistrationsAddedAfter(before: GlobalRegistrySnapshot) {
        providerRegistry.removeRegistrationsAddedAfter(before.providers)
        val addedExtractors = synchronized(extractorApis) {
            extractorApis.filter { extractor -> before.extractors.none { it === extractor } }
        }
        extractorRegistry.removeExact(addedExtractors)
    }

    private fun unregisterOwnedRegistrations(scraperId: String) {
        providerRegistry.unregisterOwner(scraperId)
        extractorRegistry.unregisterOwner(scraperId)
    }

    /**
     * Ensure a DEX file is read-only. Required for Android API 28+ which blocks
     * writable DEX file loading.
     */
    private fun ensureDexReadOnly(dexFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dexFile.canWrite()) {
            dexFile.setReadOnly()
            Log.d(TAG, "Fixed DEX permissions (set read-only): ${dexFile.name}")
        }
    }

    private fun extractMissingClassName(e: Error): String? {
        val msg = e.message ?: return null
        val match = Regex("""(?:L?)([\w/.]+)(?:;)?""").find(msg)
        return match?.groupValues?.get(1)?.replace('/', '.')
    }

    private fun findAndLoadPlugin(classLoader: DexClassLoader, cs3File: File): Plugin? {
        val pluginClassName = readPluginClassNameFromZip(cs3File)
        if (pluginClassName != null) {
            try {
                Log.d(TAG, "Loading plugin class from manifest: $pluginClassName")
                val clazz = classLoader.loadClass(pluginClassName)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is Plugin) {
                    return instance
                }
                if (looksLikePlugin(instance)) {
                    Log.d(TAG, "Using reflective wrapper for $pluginClassName (non-standard base class)")
                    return ReflectivePluginWrapper(instance)
                }
                Log.w(TAG, "Class $pluginClassName is not a Plugin and has no plugin methods")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest class $pluginClassName: ${e.message}", e)
            } catch (e: Error) {
                Log.e(TAG, "Linkage error loading manifest class $pluginClassName: ${e.message}", e)
            }
        }

        return scanForPluginClass(classLoader, cs3File)
    }

    private fun readPluginClassNameFromZip(cs3File: File): String? {
        return try {
            ZipFile(cs3File).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return null
                val json = zip.getInputStream(manifestEntry).bufferedReader().readText()
                val obj = JSONObject(json)
                obj.optString("pluginClassName").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read manifest.json from ZIP: ${e.message}")
            null
        }
    }

    private fun scanForPluginClass(classLoader: DexClassLoader, cs3File: File): Plugin? {
        try {
            @Suppress("DEPRECATION")
            val dex = dalvik.system.DexFile(cs3File)
            val entries = dex.entries()

            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                try {
                    val clazz = classLoader.loadClass(className)
                    if (clazz.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                        Log.d(TAG, "Found plugin class via scan: $className")
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is Plugin) {
                            dex.close()
                            return instance
                        }
                        if (looksLikePlugin(instance)) {
                            Log.d(TAG, "Using reflective wrapper for $className (non-standard base class)")
                            dex.close()
                            return ReflectivePluginWrapper(instance)
                        }
                        Log.w(TAG, "Annotated class $className has no plugin methods")
                    }
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting class $className: ${e.message}")
                }
            }

            dex.close()
        } catch (e: Exception) {
            Log.d(TAG, "DexFile scan fallback failed: ${e.message}")
        }

        return null
    }
}
