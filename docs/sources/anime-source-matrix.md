# FOX.TV — Anime Source Matrix (ETAP 3A / 9A.6)

- DATA: 2026-09-14 · ŹRÓDŁO FAKTÓW: `app/src/main/java/com/foxtv/app/core/anime/**` (read-only) + `AnimeScrapeContracts.kt` (macierz planów)
- POLITYKA JĘZYKOWA (9A.6): preferencja **JP_ORIGINAL+PL_SUB → PL_DUB → PL_AUDIO → ORIGINAL**. `lang=pl` w nazwie/NIE jest dowodem audio. Model osobno: `audioLanguage, subtitleLanguages, dubAvailable, subAvailable, originalLanguage, sourceLanguage, releaseLanguage, quality`.
- OBECNY MODEL: `AnimeStreamResult` ma wyłącznie `category: SUB/DUB` + `tracks` (napisy) — **brak pól audioLanguage/subtitleLanguages** → wymaga rozszerzenia modelu w zadaniu 12 (LocalizedField) i 27.

## Macierz 13 dostawców anime (plan produkcyjny z `ProductionAnimeProviderCallFactory`)

| Provider ID | Plik | Host(y) | Metoda | sub/dub | PL audio | PL sub | Original | Legalność | Decision PROPOSED |
|---|---|---|---|---|---|---|---|---|---|
| watch_hentai | WatchHentaiExtractor.kt | hentaini.com | HTML → HLS | TAK | NIE | brak dowodu | TAK (JP/oryg.) | NONE | QUARANTINE→REMOVE (adult, nielegalne) |
| hentaini | HentainiExtractor.kt | admin.hentaini.com/api | API → HLS | TAK | NIE | brak dowodu | TAK | NONE | QUARANTINE→REMOVE |
| megaPlay | MegaPlayExtractor.kt | megaplay.buzz (+api) | HTML/API → HLS | TAK | NIE (sub EN/przypadkowe) | brak dowodu | TAK | NONE | QUARANTINE→REPLACE |
| reCloud | ReCloudExtractor.kt | (recloud family) | HTML → HLS | TAK | NIE | brak dowodu | TAK | NONE | QUARANTINE→REPLACE |
| try_embed | TryEmbedExtractor.kt | vidnest/multiembed family (new.vidnest.fun, 404-vidnest…) | embed → HLS | TAK | NIE | brak dowodu | TAK | NONE | QUARANTINE→REPLACE |
| luna | LunaExtractor.kt | api.luna-stream.me, play2.echovideo.ru | API → HLS | TAK | NIE | brak dowodu | TAK | NONE | QUARANTINE→REPLACE |
| aniDB | AniDbExtractor.kt | anidb.app | HTML → HLS | TAK | NIE | brak dowodu | TAK | NONE (podrobiła nazwę AniDB — baza danych ani legalnego API) | QUARANTINE→REMOVE |
| aniNeko | AniNekoExtractor.kt | anineko.to | HTML/embed → HLS (tylko HLS z dowodem; GET-probe) | TAK (tab/klasy) | NIE | ślady `track` (PL niepewne) | TAK | NONE | QUARANTINE→REPLACE; kontrakt zabezpieczony testami (23/23) — infrastruktura testowa KEEP jako wzorzec |
| aniHQ | AniHQExtractor.kt | anihq.cc, aniHQ, cdn.4animo.xyz | HTML → HLS | TAK | NIE | brak dowodu | TAK | NONE | QUARANTINE→REPLACE |
| aniPM | AniPMExtractor.kt | ani.pm | API JSON → HLS | TAK | NIE | tracki (EN w fixturze) | TAK | NONE | QUARANTINE→REPLACE |
| vidNest | VidNestExtractor.kt | new.vidnest.fun, 404-vidnest.lofiserver.workers.dev, megacloud.animanga.fun (proxy/ts-proxy) | embed → HLS | TAK | NIE | brak dowodu | TAK | NONE | QUARANTINE→REPLACE |
| duloAnime | DuloExtractor.kt | dulo.gd, dulo.cx, d.dulo.gd | HTML → HLS | brak kategorii | NIE | brak | TAK | NONE | QUARANTINE→REMOVE |
| 123anime | OneTwoThreeAnimeExtractor.kt | 123animehub.cc | HTML → HLS | TAK | NIE | brak dowodu | TAK | NONE | QUARANTINE→REPLACE |

## Dodatkowe anime (poza planem — arabski + scrapery ogólne)

| Provider | Plik | Host(y) | Uwagi | Decision PROPOSED |
|---|---|---|---|---|
| animeArabic | anime/arabic/AnimeArabicExtractor.kt, AnimeArabicService.kt, MegaProxy.kt | animeslayer.to, patrimoines-en-mouvement.org (flare bypass), g.api.mega.co.nz, google.com (auth zaporowe?) | arabskie audio/sub; proxy i bypass flare — wysokie ryzyko | QUARANTINE→REMOVE (region nie-PL; bypass) |

## Wnioski anime

1. **Zero gwarancji PL** (audio ani napisów) w 13/13; `category=SUB/DUB` dotyczy EN, nie PL.
2. **Episode identity**: wspólny model `AnimeMedia(id=AniList)` + `episodeNumber` — brak mapowania per-provider na sezon/OVA/special; brak `absoluteNumber` — do rozszerzenia w replacement plan.
3. **HLS evidence + cancellation**: wzorzec wdrożony w AniNeko (transport, GET-probe, polityka) — obowiązkowy dla każdej przyszłej implementacji (testy 23/23 jako oryginał kontraktu).
4. **Wymienniki (badanie 9A.4)**: legalne/open — brak pełnoprawnego legalnego anime PL VOD z publicznym API; kandydaci do badania: oficjalne API serwisów z licencjonowanym anime z PL sub (płatne konto użytkownika = PARTNER_API_PLAYBACK — np. platformy z oficjalnymi API publika), Internet Archive (public domain anime), Wikimedia. Rozstrzygnięcie w replacement-plan.
5. **UNKNOWN**: dokładne zachowanie sub/dub per provider (wymaga deep-dive per plik — zadanie badawcze po decyzji ownera).

## Liczniki anime

- PROVIDERS: 14 (13 plan + 1 arabski) · KEEP: 0 · REPLACE-candidates: 9 (megaPlay, reCloud, try_embed, luna, aniNeko, aniHQ, aniPM, 123anime + future) · REMOVE-candidates: 5 (watch_hentai, hentaini, aniDB, duloAnime, animeArabic) · UNKNOWN: sub/dub deep-dive per provider.
