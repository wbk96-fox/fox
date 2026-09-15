# FOX.TV — Anime Polish Source Research (ETAP 3A.1 / Checkpoint C)

- DATA: 2026-09-14 · ZASADA: „PL = YES" tylko per title/season/episode z dowodem; NIGDY per provider.

## Kandydaci PL anime (z weryfikacją)

| Provider | Weryfikacja | PL sub | PL dub | Uwagi językowe | Mechanizm | Status |
|---|---|---|---|---|---|---|
| Anime-Odcinki | **VERIFIED** | TAK (fansub — per tytuł) | częściowo (per tytuł) | katalog z PEGI/ocenami; sekcja FILMY CHIŃSKIE; fansub tag | HTML → hostery/embedy | kandydat ADD (extractor) |
| Shinden | EXISTS-BLOCKED (467) | TAK (per tytuł) | TAK (per tytuł) | największa PL baza anime | HTML + anti-bot | badanie anti-bot (CloudflareKiller/WebView) |
| FrixySubs | VERIFIED (SPA) | TAK (grupa fansub) | nie | własne tłumaczenia | SPA/API | badanie API |
| OgladajAnime | UNKNOWN | TAK (per tytuł) | TAK | agregator | HTML/API | weryfikacja |
| AnimeOn / AnimeZone / Desu / Docchi / Mirai | UNKNOWN | per tytuł | per tytuł | — | HTML | weryfikacja |
| CDA (Yorigami Subs i inni) | VERIFIED | per upload | per upload | PL fansub uploady na CDA | ResolveURL cda.py | ADD przez cda resolver |
| Oficjalne platformy anime (PL) | UNKNOWN | TAK (licencjonowane) | TAK | Netflix/Crunchyroll/Netflix-PL itp. — brak publicznych API | OFFICIAL_DEEP_LINK / AVAILABILITY | nie tworzyć niepotwierdzonych API; deep-link only |

## Model języka (9A.6 / §13 promptu)

- Per tytuł: `audioLanguage` (jp/pl), `subtitleLanguages[]` (pl), `dubAvailable`, `subAvailable`, `originalLanguage=jp`, `releaseLanguage`.
- Preferencja odtwarzania anime: **JP_ORIGINAL+PL_SUB → PL_DUB → PL_AUDIO → ORIGINAL**.
- Źródła PL fansub dostarczają NAPISY (tracki) — player obsługuje niezależne napisy; nie wiązać provider==subtitles.

## Extraktor — plan implementacji (ADD ALONGSIDE istniejącym 13)

1. `AnimeOdcinkiExtractor` (wzorzec: `AniNekoExtractor` + `AniNekoTransport` — transport z anulowaniem, polityka HLS z dowodem, typowane błędy, zredagowane diagnostyki).
2. Dodanie do planu `ProductionAnimeProviderCallFactory` jako kolejnego providera (bez usuwania istniejących).
3. Kontrakt-testy: MockWebServer (catalog/episodes/player), redirect, wrong MIME, duplicate, cancellation.
4. Runtime verify na bramce live (osobno, `NOT RUN` do czasu).

## Statusy

- VERIFIED: anime-odcinki.pl, frixysubs.pl, cda.pl
- EXISTS-BLOCKED: shinden.pl (467)
- UNKNOWN: ogladajanime, animeon, animezone, desu, docchi, mirai, strefadb
- Runtime: **NOT RUN** (brak implementacji w tym etapie — kandydat gotowy do ADD)
