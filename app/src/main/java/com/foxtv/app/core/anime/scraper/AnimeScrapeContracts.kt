package com.foxtv.app.core.anime.scraper

import com.foxtv.app.core.anime.model.AnimeMedia
import com.foxtv.app.core.anime.model.AnimeStreamResult
import kotlinx.coroutines.CancellationException

/**
 * Immutable request descriptor for anime stream scraping.
 * Provides deterministic input for typed provider plans.
 */
data class AnimeScrapeRequest(
    val anime: AnimeMedia,
    val episodeNumber: Int,
    val categoryFilter: String? = null, // "sub", "dub", or null for both
    val titleCandidates: List<String>,
    val isAdultContent: Boolean
)

/**
 * Stable provider identifier for diagnostic tracking and governance.
 */
@JvmInline
value class AnimeProviderId(val id: String) {
    companion object {
        val WATCH_HENTAI = AnimeProviderId("watch_hentai")
        val HENTAINI = AnimeProviderId("hentaini")
        val MEGAPLAY = AnimeProviderId("megaplay")
        val RECLOUD = AnimeProviderId("recloud")
        val TRY_EMBED = AnimeProviderId("try_embed")
        val LUNA = AnimeProviderId("luna")
        val ANIDB = AnimeProviderId("anidb")
        val ANINEKO = AnimeProviderId("anineko")
        val ANIHQ = AnimeProviderId("anihq")
        val ANIPM = AnimeProviderId("anipm")
        val VIDNEST = AnimeProviderId("vidnest")
        val DULO = AnimeProviderId("dulo")
        val ONE_TWO_THREE_ANIME = AnimeProviderId("123anime")
    }
}

/**
 * Typed provider call descriptor for deterministic provider plans.
 * Each call encapsulates provider, arguments, and execution strategy.
 */
sealed interface AnimeProviderCall {
    val providerId: AnimeProviderId
    
    /**
     * Execute this provider call and return outcome with streams and diagnostics.
     * CancellationException must be propagated, not converted to outcome.
     */
    suspend fun execute(): AnimeProviderOutcome

    data class AdultProvider(
        override val providerId: AnimeProviderId,
        val titleCandidates: List<String>,
        val episodeNumber: Int,
        val extractor: suspend (List<String>, Int) -> AnimeStreamResult?
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val result = extractor(titleCandidates, episodeNumber)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = listOfNotNull(result),
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e // Propagate cancellation
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }

    data class StandardProvider(
        override val providerId: AnimeProviderId,
        val animeId: Int,
        val episodeNumber: Int,
        val category: String,
        val extractor: suspend (Int, Int, String) -> AnimeStreamResult?
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val result = extractor(animeId, episodeNumber, category)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = listOfNotNull(result),
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }

    data class MultiStreamProvider(
        override val providerId: AnimeProviderId,
        val animeId: Int,
        val episodeNumber: Int,
        val category: String,
        val extractor: suspend (Int, Int, String) -> List<AnimeStreamResult>
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val results = extractor(animeId, episodeNumber, category)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = results,
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }

    data class TitleBasedProvider(
        override val providerId: AnimeProviderId,
        val titleCandidates: List<String>,
        val episodeNumber: Int,
        val category: String,
        val extractor: suspend (List<String>, Int, String) -> AnimeStreamResult?
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val result = extractor(titleCandidates, episodeNumber, category)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = listOfNotNull(result),
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }

    data class TitleBasedMultiStreamProvider(
        override val providerId: AnimeProviderId,
        val titleCandidates: List<String>,
        val episodeNumber: Int,
        val category: String,
        val extractor: suspend (List<String>, Int, String) -> List<AnimeStreamResult>
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val results = extractor(titleCandidates, episodeNumber, category)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = results,
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }

    data class AniPMProvider(
        override val providerId: AnimeProviderId,
        val animeId: Int,
        val episodeNumber: Int,
        val category: String,
        val displayTitle: String,
        val extractor: suspend (Int, Int, String, String) -> List<AnimeStreamResult>
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val results = extractor(animeId, episodeNumber, category, displayTitle)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = results,
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }

    data class NoArgumentProvider(
        override val providerId: AnimeProviderId,
        val animeId: Int,
        val episodeNumber: Int,
        val extractor: suspend (Int, Int) -> List<AnimeStreamResult>
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val results = extractor(animeId, episodeNumber)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = results,
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }

    data class TitleOnlyProvider(
        override val providerId: AnimeProviderId,
        val titleCandidates: List<String>,
        val episodeNumber: Int,
        val extractor: suspend (List<String>, Int) -> AnimeStreamResult?
    ) : AnimeProviderCall {
        override suspend fun execute(): AnimeProviderOutcome {
            return try {
                val result = extractor(titleCandidates, episodeNumber)
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = listOfNotNull(result),
                    failures = emptyList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimeProviderOutcome(
                    providerId = providerId,
                    streams = emptyList(),
                    failures = listOf(
                        AnimeProviderFailure.ExtractionError(
                            providerId = providerId,
                            stage = "extract",
                            causeType = e.javaClass.simpleName,
                            redactedMessage = e.message?.take(100)
                        )
                    )
                )
            }
        }
    }
}

/**
 * Result of a single provider call including streams and typed diagnostics.
 * Cancellation is NOT an outcome and must be propagated as CancellationException.
 */
data class AnimeProviderOutcome(
    val providerId: AnimeProviderId,
    val streams: List<AnimeStreamResult>,
    val failures: List<AnimeProviderFailure>
)

/**
 * Typed failure descriptor for provider diagnostics.
 * Does NOT include query parameters, cookies, tokens, or other secrets.
 */
sealed interface AnimeProviderFailure {
    val providerId: AnimeProviderId
    val stage: String // "extract", "probe", "validate", etc.
    
    data class ExtractionError(
        override val providerId: AnimeProviderId,
        override val stage: String,
        val causeType: String, // Exception class name, safe to log
        val redactedMessage: String? = null // First 100 chars, without secrets
    ) : AnimeProviderFailure

    data class NetworkError(
        override val providerId: AnimeProviderId,
        override val stage: String,
        val causeType: String,
        val redactedMessage: String? = null
    ) : AnimeProviderFailure

    data class ValidationError(
        override val providerId: AnimeProviderId,
        override val stage: String,
        val reason: String
    ) : AnimeProviderFailure
}

/**
 * Factory for building deterministic, finite provider call plans.
 * Tests can provide controlled factories without network dependencies.
 */
fun interface AnimeProviderCallFactory {
    /**
     * Build complete, finite provider plan for the given request.
     * Plan must be deterministic for same request and terminate naturally.
     */
    fun createPlan(request: AnimeScrapeRequest): List<AnimeProviderCall>
}

/**
 * Injectable contract for stream reachability control.
 * Production delegates to HttpStreamLivenessValidator.
 * Tests provide controlled probe behavior without network.
 */
fun interface StreamLivenessProbe {
    /**
     * Check if stream URL is reachable with given headers.
     * Returns true if stream is alive, false otherwise.
     * Must not throw for network errors (return false instead).
     * Must propagate CancellationException.
     */
    suspend fun isStreamAlive(url: String, headers: Map<String, String>): Boolean
}

/**
 * Non-blocking diagnostic sink for production logging or test recording.
 * Does not affect public Flow emission behavior.
 */
fun interface AnimeDiagnosticSink {
    /**
     * Record diagnostic information without blocking.
     * Must not throw exceptions.
     */
    fun record(providerId: AnimeProviderId, outcome: AnimeProviderOutcome)
}


/**
 * Adapter from HttpStreamLivenessValidator to StreamLivenessProbe contract.
 * Preserves existing validator implementation and TLS policy without modification.
 */
class HttpStreamLivenessProbeAdapter(
    private val validator: com.foxtv.app.core.scraper.HttpStreamLivenessValidator
) : StreamLivenessProbe {
    override suspend fun isStreamAlive(url: String, headers: Map<String, String>): Boolean {
        return try {
            validator.isStreamAlive(url, headers)
        } catch (e: CancellationException) {
            throw e // Propagate cancellation
        } catch (e: Exception) {
            // Network errors return false, not throw
            false
        }
    }
}

/**
 * Logging diagnostic sink for production use.
 */
class LoggingDiagnosticSink : AnimeDiagnosticSink {
    override fun record(providerId: AnimeProviderId, outcome: AnimeProviderOutcome) {
        if (outcome.failures.isNotEmpty()) {
            android.util.Log.w(
                "AnimeScraperDiagnostics",
                "${providerId.id} failures: ${outcome.failures.size}"
            )
        }
    }
}

/**
 * Production provider call factory that creates the exact provider matrix
 * from the existing AnimeScraperService implementation.
 * 
 * This factory encodes the deterministic, finite plan based on:
 * - Adult content: WatchHentai, Hentaini
 * - Per category: MegaPlay, ReCloud, TryEmbed, Luna, AniDB, AniNeko, AniHQ, AniPM, VidNest
 * - No category: Dulo, 123Anime
 */
internal class ProductionAnimeProviderCallFactory(
    private val watchHentaiExtractor: com.foxtv.app.core.anime.extractors.WatchHentaiExtractor,
    private val hentainiExtractor: com.foxtv.app.core.anime.extractors.HentainiExtractor,
    private val megaPlayExtractor: com.foxtv.app.core.anime.extractors.MegaPlayExtractor,
    private val reCloudExtractor: com.foxtv.app.core.anime.extractors.ReCloudExtractor,
    private val tryEmbedExtractor: com.foxtv.app.core.anime.extractors.TryEmbedExtractor,
    private val lunaExtractor: com.foxtv.app.core.anime.extractors.LunaExtractor,
    private val aniDbExtractor: com.foxtv.app.core.anime.extractors.AniDbExtractor,
    private val aniNekoExtractor: com.foxtv.app.core.anime.extractors.AniNekoExtractor,
    private val aniHQExtractor: com.foxtv.app.core.anime.extractors.AniHQExtractor,
    private val aniPMExtractor: com.foxtv.app.core.anime.extractors.AniPMExtractor,
    private val vidNestExtractor: com.foxtv.app.core.anime.extractors.VidNestExtractor,
    private val duloExtractor: com.foxtv.app.core.anime.extractors.DuloExtractor,
    private val oneTwoThreeExtractor: com.foxtv.app.core.anime.extractors.OneTwoThreeAnimeExtractor
) : AnimeProviderCallFactory {
    
    override fun createPlan(request: AnimeScrapeRequest): List<AnimeProviderCall> {
        val calls = mutableListOf<AnimeProviderCall>()
        
        // Adult providers (if adult content)
        if (request.isAdultContent) {
            calls += AnimeProviderCall.AdultProvider(
                providerId = AnimeProviderId.WATCH_HENTAI,
                titleCandidates = request.titleCandidates,
                episodeNumber = request.episodeNumber,
                extractor = watchHentaiExtractor::extract
            )
            
            calls += AnimeProviderCall.AdultProvider(
                providerId = AnimeProviderId.HENTAINI,
                titleCandidates = request.titleCandidates,
                episodeNumber = request.episodeNumber,
                extractor = hentainiExtractor::extract
            )
        }
        
        // Determine categories
        val categories = if (!request.categoryFilter.isNullOrBlank()) {
            listOf(request.categoryFilter.lowercase())
        } else {
            listOf("sub", "dub")
        }
        
        // Standard anime providers per category
        for (category in categories) {
            // MegaPlay
            calls += AnimeProviderCall.StandardProvider(
                providerId = AnimeProviderId.MEGAPLAY,
                animeId = request.anime.id,
                episodeNumber = request.episodeNumber,
                category = category,
                extractor = megaPlayExtractor::extract
            )
            
            // ReCloud
            calls += AnimeProviderCall.StandardProvider(
                providerId = AnimeProviderId.RECLOUD,
                animeId = request.anime.id,
                episodeNumber = request.episodeNumber,
                category = category,
                extractor = reCloudExtractor::extract
            )
            
            // TryEmbed (multi-stream)
            calls += AnimeProviderCall.MultiStreamProvider(
                providerId = AnimeProviderId.TRY_EMBED,
                animeId = request.anime.id,
                episodeNumber = request.episodeNumber,
                category = category,
                extractor = tryEmbedExtractor::extractAll
            )
            
            // Luna (multi-stream)
            calls += AnimeProviderCall.MultiStreamProvider(
                providerId = AnimeProviderId.LUNA,
                animeId = request.anime.id,
                episodeNumber = request.episodeNumber,
                category = category,
                extractor = lunaExtractor::extract
            )
            
            // AniDB (title-based)
            if (request.titleCandidates.isNotEmpty()) {
                calls += AnimeProviderCall.TitleBasedProvider(
                    providerId = AnimeProviderId.ANIDB,
                    titleCandidates = request.titleCandidates,
                    episodeNumber = request.episodeNumber,
                    category = category,
                    extractor = aniDbExtractor::extract
                )
            }
            
            // AniNeko (title-based, multi-stream)
            if (request.titleCandidates.isNotEmpty()) {
                calls += AnimeProviderCall.TitleBasedMultiStreamProvider(
                    providerId = AnimeProviderId.ANINEKO,
                    titleCandidates = request.titleCandidates,
                    episodeNumber = request.episodeNumber,
                    category = category,
                    extractor = aniNekoExtractor::extract
                )
            }
            
            // AniHQ (title-based, multi-stream)
            if (request.titleCandidates.isNotEmpty()) {
                calls += AnimeProviderCall.TitleBasedMultiStreamProvider(
                    providerId = AnimeProviderId.ANIHQ,
                    titleCandidates = request.titleCandidates,
                    episodeNumber = request.episodeNumber,
                    category = category,
                    extractor = aniHQExtractor::extract
                )
            }
            
            // AniPM (special signature with displayTitle)
            calls += AnimeProviderCall.AniPMProvider(
                providerId = AnimeProviderId.ANIPM,
                animeId = request.anime.id,
                episodeNumber = request.episodeNumber,
                category = category,
                displayTitle = request.anime.displayTitle,
                extractor = aniPMExtractor::extract
            )
            
            // VidNest (multi-stream)
            calls += AnimeProviderCall.MultiStreamProvider(
                providerId = AnimeProviderId.VIDNEST,
                animeId = request.anime.id,
                episodeNumber = request.episodeNumber,
                category = category,
                extractor = vidNestExtractor::extract
            )
        }
        
        // Dulo (no category)
        calls += AnimeProviderCall.NoArgumentProvider(
            providerId = AnimeProviderId.DULO,
            animeId = request.anime.id,
            episodeNumber = request.episodeNumber,
            extractor = duloExtractor::extract
        )
        
        // 123Anime (title-only)
        if (request.titleCandidates.isNotEmpty()) {
            calls += AnimeProviderCall.TitleOnlyProvider(
                providerId = AnimeProviderId.ONE_TWO_THREE_ANIME,
                titleCandidates = request.titleCandidates,
                episodeNumber = request.episodeNumber,
                extractor = oneTwoThreeExtractor::extract
            )
        }
        
        return calls
    }
}
