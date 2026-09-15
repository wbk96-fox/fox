package com.foxtv.app.core.plugin.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtractorRegistry"

/** Owner-aware bridge to Cloudstream's process-wide extractor registry. */
@Singleton
class ExternalExtractorRegistry @Inject constructor() {
    private val lock = Any()
    private val ownersByExtractor = IdentityHashMap<ExtractorApi, MutableSet<String>>()
    private val missingExtractorDomains = mutableSetOf<String>()
    @Volatile private var installed = false

    fun registerExtractor(ownerId: String, extractor: ExtractorApi) = synchronized(lock) {
        ownersByExtractor[extractor]?.let { owners ->
            owners += ownerId
            return@synchronized
        }

        // Only the exact pre-existing instance is protected. A distinct external extractor may
        // legitimately serve the same mainUrl and must have an independent owner lifecycle.
        val alreadyRegistered = synchronized(extractorApis) {
            if (extractorApis.any { it === extractor }) {
                true
            } else {
                extractorApis.add(extractor)
                false
            }
        }
        if (alreadyRegistered) {
            Log.d(
                TAG,
                "Preserving unmanaged extractor identity: ${extractor.name} (${extractor.mainUrl})",
            )
            return@synchronized
        }

        ownersByExtractor[extractor] = mutableSetOf(ownerId)
        Log.d(TAG, "Registered extractor for $ownerId: ${extractor.name} (${extractor.mainUrl})")
    }

    fun registerAll(ownerId: String, extractorList: List<ExtractorApi>) {
        extractorList.forEach { registerExtractor(ownerId, it) }
    }

    /** Claims the exact extractor instances which a foreign plugin inserted globally. */
    fun claimNewRegistrations(ownerId: String, extractors: List<ExtractorApi>) = synchronized(lock) {
        extractors.forEach { extractor ->
            val owners = ownersByExtractor[extractor]
                ?: mutableSetOf<String>().also { ownersByExtractor[extractor] = it }
            owners += ownerId
        }
    }

    fun unregisterOwner(ownerId: String) = synchronized(lock) {
        val iterator = ownersByExtractor.entries.iterator()
        while (iterator.hasNext()) {
            val (extractor, owners) = iterator.next()
            owners.remove(ownerId)
            if (owners.isEmpty()) {
                synchronized(extractorApis) {
                    extractorApis.removeAll { it === extractor }
                }
                iterator.remove()
                Log.d(TAG, "Unregistered stale extractor: ${extractor.name} (${extractor.mainUrl})")
            }
        }
        missingExtractorDomains.clear()
    }

    fun removeExact(extractors: List<ExtractorApi>) = synchronized(lock) {
        if (extractors.isEmpty()) return@synchronized
        synchronized(extractorApis) {
            extractorApis.removeAll { current -> extractors.any { it === current } }
        }
        extractors.forEach { extractor -> ownersByExtractor.remove(extractor) }
    }

    fun clear() = synchronized(lock) {
        missingExtractorDomains.clear()
    }

    suspend fun resolveExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val result = loadExtractor(url, referer, subtitleCallback, callback)
            if (!result) {
                val domain = try {
                    java.net.URI(url).host ?: url
                } catch (_: Exception) {
                    url
                }
                val shouldLog = synchronized(lock) { missingExtractorDomains.add(domain) }
                if (shouldLog) {
                    Log.w(TAG, "No extractor registered for domain: $domain (url: $url)")
                }
            }
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadExtractor error for ${url.take(80)}: ${e.message}", e)
            false
        } catch (e: LinkageError) {
            Log.e(TAG, "loadExtractor linkage error for ${url.take(80)}: ${e.message}", e)
            false
        }
    }

    fun installGlobal() {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            installed = true
            Log.d(TAG, "installGlobal: library extractorApis has ${extractorApis.size} built-in extractors")
        }
    }

    fun getMissingExtractorDomains(): Set<String> = synchronized(lock) {
        missingExtractorDomains.toSet()
    }
}
