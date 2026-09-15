# FOX.TV — Source Expansion Plan (ETAP 3A.1 / 9B.7)

- DATA: 2026-09-14 · ZASADA NO-LOSS: KEEP→VERIFY→FIX→HARDEN→EXTEND→ADD→TEST→RUNTIME VERIFY

## 1. SourceResult — rozszerzenie minimalne (nie przepisywać)

Rozszerz `AnimeStreamResult`/`Stream` **opcjonalnymi** polami (domyślnie neutralne, zero łamania istniejących konstruktów):

```
audioLanguage: String? = null        // "pl","jp",…
subtitleLanguages: List<String> = emptyList()
dubAvailable: Boolean? = null
originalLanguage: String? = null
sourceLanguage: String? = null
capability: String? = null            // DISCOVERY/EXTERNAL_DEEP_LINK/… (docelowo enum SourceCapability)
```

Ranking (rozszerzenie istniejącej logiki rankingu w StreamRepository — nie zastąpienie):

- Filmy/seriale: PL audio → original+PL sub → original → quality → stability → latency → player-compat
- Anime: JP/original+PL sub → PL dub → original → quality → stability

## 2. Kolejność implementacji (REUSE-FIRST)

| Krok | Co | Reuse | Test |
|---|---|---|---|
| 1 | `FilmanProvider` (Cloudstream) jako plugin .cs3 ADD ALONGSIDE | istniejący loader (59/59) | lifecycle gate + runtime |
| 2 | `CdaResolver` reguła CDN + test cda.py (ResolveURL) | `CdnHeaderResolver` + `AniyomiCookieJar` wzorzec | kontrakt MockWebServer |
| 3 | Cookie sesja dla Dulo/HindMoviez/Mapple | wzorzec AniyomiCookieJar | unit + property |
| 4 | `CloudflareKiller` integracja dla CF scraperów (Mapple/HindMoviez/Hexa/FlaxMovies) | vendored interceptor | kontrakt + runtime |
| 5 | `AnimeOdcinkiExtractor` (wzorzec AniNeko: transport+polityka+diagnostyki) | AniNekoTransport + AnimeScrapeContracts | 23-style kontrakt |
| 6 | Runtime verify Checkpoint L na urządzeniu (192.168.43.206) | `scripts/device_runtime_check.sh` | logcat+screenshoty |

## 3. Checkpointy A–M — statusy

| CP | Zakres | Status |
|---|---|---|
| A | HTTP/VOD audyt 46 | **PARTIAL** (fakty z kodu 46/46; runtime verify NOT RUN) |
| B | Extractors/Resolvers | **PARTIAL** (mapa rodzin; per-resolver runtime NOT RUN) |
| C | Anime PL | **PARTIAL** (research gotowy; implementacja ADD do zrobienia) |
| D | Bajki | **PARTIAL** (kandydaci zweryfikowani częściowo; implementacja do zrobienia) |
| E | FanFilm | **PARTIAL** (inwentarz 230; per-resolver runtime NOT RUN; cda priorytet) |
| F | Cloudstream | **PARTIAL** (upstream VERIFIED; FilmanProvider reuse zaplanowany) |
| G | Aniyomi | **PARTIAL** (upstream VERIFIED; extensiony poza pinem UNKNOWN) |
| H | P2P | **PARTIAL** (indeksy kandydaci; transport/debrid KEEP; per-index runtime NOT RUN) |
| I | PL expansion | **PARTIAL** (kandydaci: 2 VERIFIED deep, 3 VERIFIED, reszta UNKNOWN) |
| J | Ranking | **DESIGN READY** (pola modelu + algorytm) |
| K | Player integration | **NOT RUN** (po krokach 1–5) |
| L | Runtime verification | **NOT RUN** |
| M | Full regression | **NOT RUN** (po zmianach implementacyjnych) |

## 4. Odpowiedzi na 24 pytania sukcesu (§47) — aktualnie

1. Istniejące źródła: **kod bez znaku awarii; runtime NOT VERIFIED** (regresja: 1344/0/0/1 zielona).
2. Naprawione w 3A.1: — (audit phase); konkretne naprawy zaplanowane (kroki 1–5).
3. Rozszerzone: FanFilm audyt per resolver (cda priorytet); Cloudstream reuse path (FilmanProvider).
4. Dodano: 0 źródeł (etap audytu/researchu) — implementacja w krokach 1–5.
5–7. Nowe PL/anime/bajki: kandydaci zweryfikowani (CDA, Filman, Anime-Odcinki, FrixySubs…) — ADD planowany.
8–10. PL audio/sub/original: model zaprojektowany (pola opcjonalne); rozpoznanie per provider w implementacji.
11–15. Player/FanFilm/Cloudstream/Aniyomi/P2P: **bez regresji** (bramki zielone); runtime NOT VERIFIED.
16–19. Extractory/resolvery/cookies/WebView: istnieją; CF/cookie hardening zaplanowany (kroki 3–4).
20. Runtime: **NOT RUN** (Checkpoint L).
21–23. Build/test regresja: **brak** (1344/0/0/1; compile PASS ×n; whitespace-only po bramkach).
24. Source stack rozszerzony: **dokumentacyjnie** (kandydaci + plan); implementacja po krokach 1–5.

## 5. POST-AUDIT EXECUTION UPDATE — 2026-09-15

The original 2026-09-14 plan was an audit/research baseline. The current working tree now contains the first execution layer:

| Area | Current implementation status |
|---|---|
| SourceResult language/capability/evidence fields | IMPLEMENTED |
| Polish ranking | IMPLEMENTED at HTTP + final presented-stream group |
| Anime-Odcinki | IMPLEMENTED as bounded public-catalog discovery adapter; not promoted to direct playback |
| Filman | IMPLEMENTED as bounded public-catalog discovery adapter; existing FanFilm source remains first-class playback path |
| FanFilm navigation generation | IMPLEMENTED |
| FanFilm stale result protection | IMPLEMENTED by generation + runId + URL |
| FanFilm SystemExit semantics | IMPLEMENTED (`SYSTEM_EXIT_NO_OUTPUT`) |
| Empty directory semantics | IMPLEMENTED (`EMPTY_DIRECTORY`) |
| Per-run menu/directory reset | IMPLEMENTED |
| Embedded periodic Kodi exit policy | DISABLED inside FOX.TV long-lived interpreter |
| P2P authorization gate | IMPLEMENTED; public-index discovery still requires explicit P2P enablement |
| Source governance catalog | IMPLEMENTED; 52 screenshot source names are represented, with implemented sources separated from discovery-only candidates |
| Release credential hardening | IMPLEMENTED |
| Cleartext/network hardening | IMPLEMENTED |

### Remaining runtime gates

Full Gradle regression, release APK verification and real Google TV Streamer execution remain environment/device gates and therefore are not marked PASS from static/host-only checks.
