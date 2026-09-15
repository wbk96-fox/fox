package com.foxtv.app.core.source

/** Closed capabilities: discovery must never silently become playback authority. */
enum class SourceCapability {
    DISCOVERY,
    AVAILABILITY,
    EXTERNAL_DEEP_LINK,
    VERIFIED_IN_APP_MEDIA,
    PARTNER_API_PLAYBACK,
    USER_SUPPLIED,
}

enum class SourceOrigin {
    OFFICIAL_API,
    OFFICIAL_DEEP_LINK,
    OPEN_LICENSED,
    USER_SUPPLIED,
    PARTNER_API,
    PUBLIC_INDEX,
}

enum class ProviderStatus {
    KEEP,
    REPLACE,
    REFACTOR,
    QUARANTINE,
    REMOVE,
    EXTERNAL_DEEP_LINK,
    DISCOVERY_ONLY,
    USER_SUPPLIED_ONLY,
    OPEN_MEDIA_ONLY,
    UNKNOWN,
}

enum class ProviderType {
    HTTP_SCRAPER,
    EMBED_RESOLVER,
    API_PROVIDER,
    P2P_INDEX,
    DEBRID,
    IPTV,
    MANGA,
    PLUGIN_HOST,
    META,
}

data class SourceLanguage(
    val audioLanguage: String? = null,
    val subtitleLanguages: List<String> = emptyList(),
    val dubAvailable: Boolean? = null,
    val subAvailable: Boolean? = null,
    val originalLanguage: String? = null,
    val sourceLanguage: String? = null,
    val releaseLanguage: String? = null,
)

data class SourceEvidence(
    val sourceUrl: String,
    val checkedAtEpochMs: Long,
    val contentHash: String? = null,
    val attribution: String? = null,
    val note: String? = null,
)

data class ProviderGovernanceRecord(
    val id: String,
    val displayName: String,
    val type: ProviderType,
    val origin: SourceOrigin,
    val status: ProviderStatus,
    val capabilities: Set<SourceCapability>,
    val language: SourceLanguage = SourceLanguage(),
    val officialEndpoint: String? = null,
    val owner: String? = null,
    val evidence: SourceEvidence? = null,
    val lastReviewedEpochMs: Long = 0L,
)

/**
 * Central, immutable policy table. Unknown providers are never promoted by accident.
 * Existing providers may remain available; this registry controls capability semantics.
 */
object SourceGovernanceRegistry {
    val records: List<ProviderGovernanceRecord> = listOf(
        record("cda", "CDA", ProviderType.EMBED_RESOLVER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.KEEP, setOf(SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        record("tvpvod", "TVP VOD", ProviderType.API_PROVIDER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.EXTERNAL_DEEP_LINK, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        record("35mm", "35mm.online", ProviderType.API_PROVIDER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.EXTERNAL_DEEP_LINK, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        record("ninateka", "Ninateka", ProviderType.API_PROVIDER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.EXTERNAL_DEEP_LINK, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        record("anime-odcinki", "Anime-Odcinki", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
        record("filman", "Filman", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY), language = SourceLanguage(audioLanguage = "pl", dubAvailable = true, subAvailable = true)),
        record("frixysubs", "FrixySubs", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
        record("shinden", "Shinden", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.UNKNOWN, setOf(SourceCapability.DISCOVERY), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
    )

    /**
     * Canonical source catalog from the 2026-09-15 project delivery specification.
     * Entries that do not have an in-repository extractor are explicitly marked
     * discovery-only/unknown; their presence never creates a fake playback path.
     */
    val projectCatalog: List<ProviderGovernanceRecord> = listOf(
        // Implemented FanFilm PL sources
        record("animezone", "AnimeZone", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
        record("docchi", "Docchi", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
        record("frixysubs", "FrixySubs", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
        record("shinden", "Shinden", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
        record("bajeczki24", "Bajeczki24", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA), language = SourceLanguage(audioLanguage = "pl", dubAvailable = true)),
        record("cdahd", "CDA-HD", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA)),
        record("ekinotv", "Ekino-TV", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA)),
        record("filman", "Filman", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA), language = SourceLanguage(audioLanguage = "pl", dubAvailable = true, subAvailable = true)),
        record("maxvod", "MaxVod", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA)),
        record("obejrzyj", "Obejrzyj.to", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA)),
        record("serialevip", "Seriale VIP", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA)),
        record("vestroiakr", "Vestroiakr", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA), language = SourceLanguage(audioLanguage = "pl", subAvailable = true)),
        record("zaluknij", "Zaluknij", ProviderType.PLUGIN_HOST, SourceOrigin.PUBLIC_INDEX, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.VERIFIED_IN_APP_MEDIA)),
        record("anime-odcinki", "Anime-Odcinki", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY), language = SourceLanguage(subtitleLanguages = listOf("pl"), subAvailable = true)),
        record("elmoreflix", "ElmoreFlix", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        // Verified/known official/deep-link paths
        record("tvpvod", "TVP VOD", ProviderType.API_PROVIDER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.EXTERNAL_DEEP_LINK, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        record("35mm", "35mm.online", ProviderType.API_PROVIDER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.EXTERNAL_DEEP_LINK, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        record("ninateka", "Ninateka", ProviderType.API_PROVIDER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.EXTERNAL_DEEP_LINK, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        record("cda", "CDA", ProviderType.EMBED_RESOLVER, SourceOrigin.OFFICIAL_DEEP_LINK, ProviderStatus.KEEP, setOf(SourceCapability.DISCOVERY, SourceCapability.AVAILABILITY, SourceCapability.EXTERNAL_DEEP_LINK)),
        // Screenshot/catalog candidates without a safe in-repository extractor yet
        record("animeon", "AnimeOn", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("desu-online", "Desu Online", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("grupa-mirai", "Grupa Mirai", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("ogladajanime", "OgladajAnime", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("skanlacje-feniksy", "Skanlacje Feniksy", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("strefadb", "StrefaDB", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("yorigami-subs", "Yorigami Subs", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("bajeczki-tv", "Bajeczki TV", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("bajki-dla-dzieci", "Bajki dla dzieci", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("forum-bajki-tv", "Forum Bajki-TV", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("heffalump", "Heffalump", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("kreskowka-subs", "Kreskówka Subs", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("kreskowki-tv", "Kreskówki TV", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("filmowo", "Filmowo", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("filmy-polskie-999", "Filmy Polskie 999", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("flowflix", "FlowFlix", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("freedisc", "FreeDisc", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("iitv", "IITV", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("openclip", "OpenClip", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("premiumsmart", "PremiumSmart", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("telekino", "Telekino", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("vider", "Vider", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("virpe", "Virpe", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("dopebox", "DopeBox", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("fmovies", "FMovies", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("movies2watch", "Movies2Watch", ProviderType.HTTP_SCRAPER, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("btdig", "BTDig", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("cinemamovies", "CinemaMovies", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("devil-torrents", "Devil-Torrents", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("electro-torrent", "Electro-Torrent", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("glodls", "GloDLS", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("helltorrents", "HellTorrents", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("limetorrents", "LimeTorrents", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("rstorrent", "RSTorrent", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("torlock", "Torlock", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("torrentdownload", "TorrentDownload", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        record("torrentleech", "TorrentLeech", ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY)),
        // Public torrent indexes are catalogued but never granted playback authority.
        *listOf("BTDig","CinemaMovies","Devil-Torrents","Electro-Torrent","GloDLS","HellTorrents","LimeTorrents","RSTorrent","Torlock","TorrentDownload","TorrentLeech").map { id ->
            record(id.lowercase().replace(' ', '-'), id, ProviderType.P2P_INDEX, SourceOrigin.PUBLIC_INDEX, ProviderStatus.DISCOVERY_ONLY, setOf(SourceCapability.DISCOVERY))
        }.toTypedArray(),
    )

    fun catalogRecord(idOrName: String): ProviderGovernanceRecord? =
        projectCatalog.firstOrNull { it.id.equals(idOrName, true) || it.displayName.equals(idOrName, true) }

    private fun record(
        id: String,
        name: String,
        type: ProviderType,
        origin: SourceOrigin,
        status: ProviderStatus,
        capabilities: Set<SourceCapability>,
        language: SourceLanguage = SourceLanguage(),
    ) = ProviderGovernanceRecord(id, name, type, origin, status, capabilities, language)

    fun capabilityFor(id: String): SourceCapability? = (records + projectCatalog)
        .firstOrNull { it.id.equals(id, ignoreCase = true) || it.displayName.equals(id, ignoreCase = true) }
        ?.capabilities
        ?.maxWithOrNull(compareBy<SourceCapability> { when (it) { SourceCapability.VERIFIED_IN_APP_MEDIA -> 6; SourceCapability.PARTNER_API_PLAYBACK -> 5; SourceCapability.EXTERNAL_DEEP_LINK -> 4; SourceCapability.AVAILABILITY -> 3; SourceCapability.DISCOVERY -> 2; SourceCapability.USER_SUPPLIED -> 1 } })

    fun allowsPlayback(id: String): Boolean = (records + projectCatalog)
        .firstOrNull { it.id.equals(id, ignoreCase = true) || it.displayName.equals(id, ignoreCase = true) }
        ?.capabilities
        ?.let { SourceCapability.VERIFIED_IN_APP_MEDIA in it || SourceCapability.PARTNER_API_PLAYBACK in it } == true

    fun allowsDiscovery(id: String): Boolean = (records + projectCatalog)
        .firstOrNull { it.id.equals(id, ignoreCase = true) || it.displayName.equals(id, ignoreCase = true) }
        ?.capabilities
        ?.contains(SourceCapability.DISCOVERY) == true
}
