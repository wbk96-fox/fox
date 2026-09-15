package com.foxtv.app.core.scraper

import com.foxtv.app.core.source.PolishSourcePriority
import com.foxtv.app.domain.model.Stream

/**
 * Polish-preference ranking for streams (ETAP 3A.1 / §10).
 *
 * EXTENDS the existing per-addon presentation; it never removes streams.
 * Order:
 *  1. Polish audio (audioLanguage == "pl")
 *  2. Original audio + Polish subtitles (subtitleLanguages contains "pl")
 *  3. Everything else (original audio, unknown language)
 *  4. Higher quality first within the same language tier.
 *
 * Unknown language information keeps the stream ranked with "everything else":
 * absence of evidence is never treated as a downgrade of the source itself.
 */
object PolishStreamRanking {

    fun languageTier(stream: Stream): Int = when {
        stream.audioLanguage?.equals("pl", ignoreCase = true) == true -> 0
        stream.subtitleLanguages.any { it.equals("pl", ignoreCase = true) } -> 1
        else -> 2
    }

    fun comparator(): Comparator<Stream> =
        compareBy<Stream> { PolishSourcePriority.priority(it.name ?: it.addonName) }
            .thenBy { languageTier(it) }
            .thenByDescending { it.qualityValue }
}
