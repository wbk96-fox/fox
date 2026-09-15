package com.foxtv.app.core.plugin.cloudstream

import android.util.Log
import com.foxtv.app.core.network.IPv4FirstDns
import com.foxtv.app.domain.model.ExternalPluginEntry
import com.foxtv.app.domain.model.ExternalRepoManifest
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExternalRepoParser"

data class ExternalRepoParseResult(
    val name: String,
    val description: String?,
    val plugins: List<ExternalPluginEntry>,
    val isComplete: Boolean = true,
    val rejectedPluginCount: Int = 0,
    val explicitlyInactiveInternalNames: Set<String> = emptySet(),
    val hasActiveInvalidEntries: Boolean = false,
    val hasDuplicateAmbiguity: Boolean = false,
) {
    val isAuthoritativeForRemoval: Boolean
        get() = isComplete && !hasActiveInvalidEntries && !hasDuplicateAmbiguity
}

private data class FetchedDocument(
    val body: String,
    val effectiveUrl: HttpUrl,
)

private data class PluginListResult(
    val preparation: ExternalPluginPreparation?,
    val success: Boolean,
) {
    companion object {
        fun failure() = PluginListResult(preparation = null, success = false)
    }
}

/** Parses Cloudstream repo.json -> plugins.json -> .cs3 and direct plugins.json URLs. */
@Singleton
class ExternalRepoParser(
    private val moshi: Moshi,
    httpClient: OkHttpClient,
) {
    @Inject
    constructor(moshi: Moshi) : this(moshi, createHttpClient())

    private val httpClient = httpClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    private val repoManifestAdapter = moshi.adapter(ExternalRepoManifest::class.java)
    private val pluginListType = Types.newParameterizedType(List::class.java, ExternalPluginEntry::class.java)
    private val pluginListAdapter = moshi.adapter<List<ExternalPluginEntry>>(pluginListType)

    suspend fun tryParse(url: String): ExternalRepoParseResult? = withContext(Dispatchers.IO) {
        val repositoryDocument = fetchDocument(url) ?: return@withContext null
        val trimmed = repositoryDocument.body.trim()
        val effectiveRepositoryUrl = repositoryDocument.effectiveUrl.toString()

        if (trimmed.contains("\"pluginLists\"")) {
            try {
                val manifest = repoManifestAdapter.fromJson(trimmed)
                if (manifest != null && manifest.pluginLists.isNotEmpty()) {
                    val listResults = coroutineScope {
                        manifest.pluginLists.map { reference ->
                            async {
                                val resolved = ExternalRepositoryContract.resolveHttpsUrl(
                                    effectiveRepositoryUrl,
                                    reference,
                                )
                                if (resolved == null) {
                                    Log.w(TAG, "Rejected non-HTTPS or invalid pluginLists URL: $reference")
                                    PluginListResult.failure()
                                } else {
                                    fetchPluginList(resolved)
                                }
                            }
                        }.awaitAll()
                    }
                    return@withContext aggregateManifestResult(manifest, listResults)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Not a repo manifest: ${e.message}")
            }
        }

        if (trimmed.startsWith("[")) {
            try {
                val rawPlugins = pluginListAdapter.fromJson(trimmed)
                if (rawPlugins != null) {
                    val prepared = ExternalRepositoryContract.preparePlugins(
                        rawPlugins,
                        effectiveRepositoryUrl,
                    )
                    logRejections(effectiveRepositoryUrl, prepared.rejected)
                    return@withContext ExternalRepoParseResult(
                        name = inferRepoName(effectiveRepositoryUrl),
                        description = null,
                        plugins = prepared.accepted,
                        rejectedPluginCount = prepared.rejected.size,
                        explicitlyInactiveInternalNames = prepared.explicitlyInactiveInternalNames,
                        hasActiveInvalidEntries = prepared.hasActiveInvalidEntries,
                        hasDuplicateAmbiguity = prepared.hasDuplicateAmbiguity,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Not a direct plugins list: ${e.message}")
            }
        }

        null
    }

    private fun aggregateManifestResult(
        manifest: ExternalRepoManifest,
        listResults: List<PluginListResult>,
    ): ExternalRepoParseResult {
        val preparations = listResults.mapNotNull(PluginListResult::preparation)
        val declaredNameCounts = preparations
            .flatMap(ExternalPluginPreparation::declaredInternalNames)
            .groupingBy(String::toString)
            .eachCount()
        val duplicateInternalNames = declaredNameCounts
            .filterValues { count -> count > 1 }
            .keys

        val localDuplicateRejectionCount = preparations.sumOf { preparation ->
            preparation.rejected.count {
                it.classification == ExternalPluginRejectionClassification.DUPLICATE_AMBIGUITY
            }
        }
        val globalDuplicateRejectionCount = declaredNameCounts.values.sumOf { count ->
            (count - 1).coerceAtLeast(0)
        }
        val crossListDuplicateCount =
            (globalDuplicateRejectionCount - localDuplicateRejectionCount).coerceAtLeast(0)

        duplicateInternalNames.forEach { internalName ->
            Log.w(TAG, "Duplicate external plugin internalName across repository lists: $internalName")
        }

        val plugins = preparations
            .flatMap(ExternalPluginPreparation::accepted)
            .distinctBy(ExternalPluginEntry::internalName)
        val explicitlyInactiveInternalNames = preparations
            .asSequence()
            .flatMap { it.rejected.asSequence() }
            .filter {
                it.classification == ExternalPluginRejectionClassification.EXPLICITLY_INACTIVE
            }
            .map(ExternalPluginRejection::internalName)
            .filterNot(duplicateInternalNames::contains)
            .toSet()

        return ExternalRepoParseResult(
            name = manifest.name,
            description = manifest.description,
            plugins = plugins,
            isComplete = listResults.all(PluginListResult::success),
            rejectedPluginCount = preparations.sumOf { it.rejected.size } + crossListDuplicateCount,
            explicitlyInactiveInternalNames = explicitlyInactiveInternalNames,
            hasActiveInvalidEntries = preparations.any(ExternalPluginPreparation::hasActiveInvalidEntries),
            hasDuplicateAmbiguity = duplicateInternalNames.isNotEmpty(),
        )
    }

    private suspend fun fetchPluginList(url: String): PluginListResult = withContext(Dispatchers.IO) {
        val document = fetchDocument(url) ?: return@withContext PluginListResult.failure()
        try {
            val rawPlugins = pluginListAdapter.fromJson(document.body.trim())
                ?: return@withContext PluginListResult.failure()
            val effectiveListUrl = document.effectiveUrl.toString()
            val prepared = ExternalRepositoryContract.preparePlugins(rawPlugins, effectiveListUrl)
            logRejections(effectiveListUrl, prepared.rejected)
            PluginListResult(preparation = prepared, success = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse plugin list from ${document.effectiveUrl}: ${e.message}")
            PluginListResult.failure()
        }
    }

    private suspend fun fetchDocument(url: String): FetchedDocument? {
        val requestUrl = url.trim().toHttpUrlOrNull()?.takeIf(HttpUrl::isHttps)
        if (requestUrl == null) {
            Log.w(TAG, "Rejected non-HTTPS or invalid external repository URL")
            return null
        }

        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", "FOX.TV/1.0")
            .build()

        return try {
            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (!continuation.isCancelled) {
                                    Log.e(TAG, "Failed to fetch $requestUrl: ${e.message}")
                                    continuation.resumeIfPending(null)
                                }
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val document = response.use {
                                    val effectiveUrl = response.request.url
                                    when {
                                        !effectiveUrl.isHttps -> {
                                            Log.e(TAG, "Rejected non-HTTPS final response URL: $effectiveUrl")
                                            null
                                        }
                                        !response.isSuccessful -> {
                                            Log.e(TAG, "HTTP ${response.code} for $effectiveUrl")
                                            null
                                        }
                                        else -> try {
                                            FetchedDocument(
                                                body = response.body.string(),
                                                effectiveUrl = effectiveUrl,
                                            )
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to read $effectiveUrl: ${e.message}")
                                            null
                                        }
                                    }
                                }
                                continuation.resumeIfPending(document)
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enqueue $requestUrl: ${e.message}")
                    continuation.resumeIfPending(null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun logRejections(sourceUrl: String, rejected: List<ExternalPluginRejection>) {
        rejected.forEach { rejection ->
            Log.w(
                TAG,
                "Rejected ${rejection.internalName} from $sourceUrl " +
                    "[${rejection.classification}]: ${rejection.reason}",
            )
        }
    }

    private fun inferRepoName(url: String): String {
        val path = url.substringAfter("://").substringBefore("?")
        val segments = path.split("/").filter { it.isNotBlank() }
        return segments.lastOrNull()?.removeSuffix(".json") ?: "External Repository"
    }

    private companion object {
        fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }
}

private fun CancellableContinuation<FetchedDocument?>.resumeIfPending(value: FetchedDocument?) {
    if (!isCompleted) {
        resume(value) { _, _, _ -> }
    }
}
