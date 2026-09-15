# FOX.TV — Replacement Plan (ETAP 3A / 9A.5, 9A.8)

> **REWIZJA (ETAP 3A.1, 2026-09-14): kolumny Decision PROPOSED „QUARANTINE→REMOVE" dla
> 44 scraperów i anime UCHYLONE przez zasadę NO-LOSS (ETAP 3A.1).** Aktualne statusy per
> provider: `docs/sources/source-expansion-audit.md` (KEEP/KEEP_AND_REPAIR/PARTIAL/UNKNOWN —
> runtime NOT VERIFIED). Usunięcia wyłącznie po osobnej, jawnej decyzji właściciela (§42).
> Poniższa macierz pozostaje jako historyczny stan decyzji z etapu 3A.

- DATA: 2026-09-14 · UWAGA: decyzje PROPOSED wymagają zatwierdzenia właściciela; implementacja dopiero po zatwierdzeniu (zadania 13–27).
- Ścieżka docelowa: **Metadata (TMDB) → Discovery → Availability (TMDB watch/providers region=PL + IA/Wikimedia) → Source Governance → Evidence → Capability → Playback authorization → istniejący player (Media3 fork; bez drugiego playera).**

## 1. Modele architektury (do implementacji w zadaniach 10–13, 27)

| Model | Minimalny kontrakt |
|---|---|
| `SourceCapability` | DISCOVERY, AVAILABILITY, EXTERNAL_DEEP_LINK, VERIFIED_IN_APP_MEDIA, PARTNER_API_PLAYBACK, USER_SUPPLIED — enum zamknięty; URL strony NIGDY nie awansuje do PLAYBACK |
| `SourceLanguage` | audioLanguage, subtitleLanguages[], dubAvailable, subAvailable, originalLanguage, sourceLanguage, releaseLanguage (osobno!) |
| `AudioTrack` / `SubtitleTrack` | język, etykieta, dowód źródłowy |
| `SourceOrigin` | OFFICIAL_API, OFFICIAL_DEEP_LINK, OPEN_LICENSED, USER_SUPPLIED, PARTNER_API, PUBLIC_INDEX |
| `SourceEvidence` | per element: licencja/URL/logo/dostępność + hash + timestamp (zero inference) |
| `ProviderStatus` | KEEP, REPLACE, REFACTOR, QUARANTINE, REMOVE, EXTERNAL_DEEP_LINK, DISCOVERY_ONLY, USER_SUPPLIED_ONLY, OPEN_MEDIA_ONLY, UNKNOWN |
| `ProviderType` | HTTP_SCRAPER, EMBED_RESOLVER, API_PROVIDER, P2P_INDEX, DEBRID, IPTV, MANGA, PLUGIN_HOST, META |
| `ProviderGovernanceRecord` | właściciel, oficjalny endpoint, podstawa prawna + referencja, atrybucja, język, ograniczenia, dozwolone capability, daty przeglądu, integralność/wersja, takedown |
| `SourceResult`/`DiscoveryResult`/`AvailabilityResult`/`PlaybackResult` | PlaybackResult NIGDY z samego URL — wymaga capability+evidence+authorization |

## 2. Macierz decyzji CURRENT → …

| CURRENT | PROBLEM | RESEARCH | CANDIDATES | SELECTED | REASON | IMPLEMENTATION | TEST | STATUS |
|---|---|---|---|---|---|---|---|---|
| 44 HTTP scrapery | nielegalna redystrybucja; zero PL; brak testów | web-research §1 | TMDB availability (PL) + TVP VOD + 35mm + IA/Wikimedia + user-supplied | **załącznik A (per-capability)** | legalność+stabilność+PL | `SourceCapability` gate + registry + quarantine przed I/O | unit/property/MockWebServer; brak live jako dowód | **PROPOSED — decyzja ownera** |
| CineSrc, CineSu (martwe) | 0 referencji | — | — | REMOVE | martwy kod | `ProviderReferenceIntegrityTest` → usunięcie | test referencji + kompilacja | PROPOSED (14.3) |
| Anime 13 providerów | brak PL; nielegalne | brak legalnych publicznych API anime | brak pełnoprawnego replacementu bez umowy | **QUARANTINE → ścieżka user/paid** (jeśli owner zdecyduje o partnerstwach) | legalność | governance + capability gate | jw. | **OPEN — decyzja ownera** |
| AniNeko infrastruktura (transport/policy/tests) | — | — | — | **KEEP jako wzorzec kontraktu** (nie jako source) | 23/23 testy | — | gotowe | DONE |
| IPTV harvesting (IptvScraper/PasteShDecryptor/auto-login) | pozyskiwanie cudzych credentiali | — | — | REMOVE | legalność/§96 | zadanie 17 | integration no-request | PROPOSED (17–18) |
| IPTV user M3U | legalne (własne) | — | — | KEEP (SafeMediaFetcher + vault) | legalność | zadania 18–21 | parser property | PLANNED |
| Manga WeebCentral | HTML scraping | — | MANGA MILL (tylko deep-link) | **EXTERNAL_DEEP_LINK dla MANGA MILL; WeebCentral QUARANTINE/REMOVE** | legalność | zadanie 26 | resolver tests | PLANNED |
| MangaDex | legalne oficjalne API | pin schematu (OpenAPI 3.0.3 / API 5.13.1) | MangaDex | **KEEP/IMPLEMENT (PL first)** | oficjalne API + atrybucja | zadania 23–25 | schema gate + property | PLANNED |
| Stremio addons | user-supplied | — | — | USER_SUPPLIED_ONLY | — | capability gate | kontrakt manifestu | PLANNED |
| FanFilm (katalog + 230 resolverów) | nielegalne resolvery | INCOMPLETE per resolver | — | **QUARANTINE per resolver; MOST (Python/Kodi bridge) KEEP** | legalność | bramki per resolver | per-resolver tests | **OPEN** |
| Cloudstream pluginy | user-supplied | — | — | USER_SUPPLIED_ONLY | — | loader istnieje | 59/59 gate | DONE (M1) |
| Aniyomi | host OK; extension per pin | pin Jellyfin | — | KEEP host; extension = decyzja per pin | — | M2/M3 gotowe | 114/114 | DONE |
| TorrServer + debrid ×5 | transport/prywatne konta | — | — | KEEP (user-supplied authorization) | legalność | P2P gate (15.2) | property | PLANNED |
| Trakt/Simkl/MDBList/IntroDB/ParentalGuide | metadata/sync | — | — | KEEP (DISCOVERY) | legalne API | — | istniejące | DONE |
| Trailers (InnerTube bez klucza) | niezgodność z oficjalnym API | web-research | YouTube Data API v3 lub deep-link | **REFACTOR** | zgodność | deep-link/official API | unit | PROPOSED |
| BestSimilar | HTML scraping | — | TMDB similar (oficjalne) | **REFACTOR→TMDB similar** | oficjalne API | zapasowe | unit | PROPOSED |

## 3. PL ścieżka (raport 9A.5)

- Filmy/seriale: `PL_NATIVE_AUDIO | PL_DUB | PL_SUB | ORIGINAL | ORIGINAL+PL_SUB | UNKNOWN` — per oferta. TMDB watch/providers NIE dostarcza języka audio/sub — do czasu metadanych providera oznaczamy UNKNOWN (TVP VOD/35mm = PL_NATIVE_AUDIO dla polskich produkcji).
- Anime: `JP_ORIGINAL | ORIGINAL+PL_SUB | PL_DUB | PL_AUDIO | UNKNOWN`.

## 4. IMPLEMENTATION TARGET (po zatwierdzeniu)

1. `core/evidence/*` (10) bez aktywacji źródeł → 2. `core/governance/*` (13) → 3. adaptery: TmdbWatchProviderPlAdapter (16.1), OfficialDeepLinkResolver TVP/35mm/Ninateka (16.2), IA/Wikimedia (16.3), SafeMediaFetcher (20), MangaDex (23–25) → 4. PL policy (12) + anime language model (12.2) + P2P authorization (15.2) + player handoff (27.3).

## 5. TEST PLAN (per provider — 15/28)

unit · property (stałe ziarno) · contract (MockWebServer) · redirect · wrong MIME · wrong media · wrong language · wrong episode · duplicate · cancellation · timeout · security (SSRF/ZIP/headers) · governance (capability non-escalation) · **brak live network jako dowodu** (live/DEVICE osobne bramki, `foxtvLiveAnime`/30).

## 6. STATUS ETAPU 3A

- SOURCE INVENTORY: **COMPLETE** (2 UNKNOWN blokujące — jawnie wypisane)
- WEB RESEARCH: **INCOMPLETE (partial)** — klasy kluczowe potwierdzone; PL mapowanie per tytuł, FanFilm resolvery (230), anime partnerskie do dokończenia
- HTTP/VOD MATRIX: **COMPLETE** (46/46 + systemy towarzyszące)
- ANIME MATRIX: **COMPLETE** (14/14; sub/dub deep-dive per provider = jawny UNKNOWN)
- P2P MATRIX: **COMPLETE**
- REPLACEMENT PLAN: **COMPLETE (decyzje PROPOSED — wymagane zatwierdzenie ownera)**
- ARCHITECTURE FREEZE: **COMPLETE**

## 5. EXECUTION UPDATE — 2026-09-15

The previous `QUARANTINE → REMOVE` proposals remain historical and were not applied automatically. The current implementation preserves existing working infrastructure while adding explicit capability/governance semantics.

Implemented changes:
- PL-first stream ranking is now applied at final presentation, not only inside one scraper family.
- FanFilm is generation-aware and remains the primary existing resolver/source integration for its vendored Polish provider set.
- Public P2P indexes are catalogued as discovery-only and are not granted playback capability by governance.
- User-owned Direct Debrid providers remain enabled through explicit account credentials only.
- The screenshot inventory is represented centrally so source UI/catalog work can no longer silently diverge from the delivery matrix.
