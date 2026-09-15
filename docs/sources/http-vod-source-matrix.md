# FOX.TV — HTTP/VOD Source Matrix (ETAP 3A / 9A.2–9A.3)

- DATA: 2026-09-14 · WYKONANIE: skan `core/scraper/*` + `data/remote` + `data/trailer` + `core/subtitles` + `core/server` + `core/deeplink` (read-only)
- METODA: najpierw stan faktyczny (ankieta A–M), potem decyzja; UNKNOWN != KEEP; decyzje PROPOSED wymagają potwierdzenia właściciela przed zadaniem 14.
- KLUCZOWE FAKTY: **46 klas scraperów w kodzie; 44 zarejestrowane w `FoxTvHttpScraperManager.scrapers`; 2 NIEZAREJESTROWANE (martwy kod)**. **79 unikalnych hostów** z kodu scraperów. **ZERO legalnych polskich źródeł VOD w rejestrze.**

## 1. Analiza rodzin (wzorce z kodu)

| Rodzina | Przykłady (ID) | Mechanizm (z kodu) | Co zwraca | Ryzyka (A–M) | Decision PROPOSED |
|---|---|---|---|---|---|
| Embed-agregatory z ID TMDB/IMDb | vidSrc, vixSrc, vidFast, vidLink, vidGod, vidRock, vidZee, vidVault, vidUp, vidApi, vidCore, 2embed/1embed, multiEmbed, videasy, hexa, nova, movieNight, vuflix, cinejoy, bcine | `https://host/embed/movie/{tmdb}` → HTML → iframe → HLS | stream (HLS/MP4) | E: nielegalna redystrybucja; H: TAK (HTML scraping); I: omija hosting licencjonowany; M: wykonywalne z registry | **QUARANTINE → REMOVE po replacementach** (zatwierdzenie właściciela) |
| Direkt-file / hostery plików | vadapav, pixeldrain, 4khdhub, downloadEverything, fshareTv, xDownloader, xPass, zxcStream, megaSource | API/HTML → direct MP4/MKV (vadapav = manifest w stylu Stremio) | stream (direct file) | E: nielegalne; K/L: cookies/tokeny bywają przenoszone | **QUARANTINE → REMOVE po replacementach** |
| RU/CIS/Asia TV/VOD | meowTv, vaplayer, wecollege, shegu.st, kisskh.do, fshareTv, FSonic, FSOnline, hindmoviez, bcine, cine.su, cinesrc.st, flaxmovies, lookmovie, mapple | API JSON / HTML → HLS | stream | E: nielegalne; region-locked | **QUARANTINE → REMOVE po replacementach** |
| Dulo (multi-domena) | dulo.gd, dulo.cx, d.dulo.gd | HTML → HLS | stream | jw. | **QUARANTINE → REMOVE po replacementach** |
| TMDB (dane) | api.themoviedb.org | oficjalne API v3/v4 | metadata / watch-providers / images | E: LEGALNE (atrybucja TMDB; watch-providers = dane JustWatch — wymóg atrybucji) | **KEEP (DISCOVERY/AVAILABILITY)** |
| Martwy kod | CineSrcScraper (cinesrc.st), CineSuScraper (cine.su) | pliki obecne, **0 referencji** | — | brak wykonania | **REMOVE** (po testach referencji 14.3) |

## 2. Tabela 46 scraperów HTTP

Kolumny skrócone: `# | ID | Host(y) | Metoda | Zwraca | Decision PROPOSED`. Wspólne wnioski (auth/DRM/region/PL) w §3; JSON niesie pełne kolumny.

| # | ID | Host(y) | Metoda | Zwraca | Decision PROPOSED |
|---|---|---|---|---|---|
| 1 | a111477 | a.111477.xyz, st.111477.xyz | API JSON → HLS | stream | QUARANTINE→REMOVE |
| 2 | bcine | bcine.ru | HTML → iframe/HLS | stream | QUARANTINE→REMOVE |
| 3 | cinejoy | cinejoy.to | HTML → iframe/HLS | stream | QUARANTINE→REMOVE |
| 4 | downloadEverything | downloadeverythingfromeverywhere.com, slave.…, cloudorchestranova.com, nextgencloudfabric.com, glendale-plumbing.com, freakyniki.elaxo.lol | API → direct | stream | QUARANTINE→REMOVE |
| 5 | dulo | dulo.gd, dulo.cx, d.dulo.gd | HTML → HLS | stream | QUARANTINE→REMOVE |
| 6 | fsonic | player.fsonline.app, api.speedracelight.com | API → HLS | stream | QUARANTINE→REMOVE |
| 7 | fsonline | (rodzina fsonline) | HTML → HLS | stream | QUARANTINE→REMOVE |
| 8 | flaxMovies | flaxmovies.xyz | HTML → HLS | stream | QUARANTINE→REMOVE |
| 9 | flyStream | flystream.net | API/HTML → HLS | stream | QUARANTINE→REMOVE |
| 10 | fourKHDHub | 4khdhub.one | API → direct | stream/file | QUARANTINE→REMOVE |
| 11 | frame | (rodzina frame) | HTML → HLS | stream | QUARANTINE→REMOVE |
| 12 | fshareTv | fsharetv.cc, fsharetv.co | API → HLS | stream | QUARANTINE→REMOVE |
| 13 | hexa | (rodzina hexa) | HTML → embed → HLS | stream | QUARANTINE→REMOVE |
| 14 | hindMoviez | hindmovie.icu | HTML → HLS | stream | QUARANTINE→REMOVE |
| 15 | kissKh | kisskh.do, api.shegu.st | API JSON → HLS | stream | QUARANTINE→REMOVE |
| 16 | lmScript | lmscript.xyz | HTML → HLS | stream | QUARANTINE→REMOVE |
| 17 | lookMovie | lookmovie2.to, lookmovie.foundation | HTML/API → HLS | stream | QUARANTINE→REMOVE |
| 18 | mapple | mapple.club | HTML → HLS | stream | QUARANTINE→REMOVE |
| 19 | megaSource | megasource.wasmer.app | API → HLS | stream | QUARANTINE→REMOVE |
| 20 | meowTv | meowtv.ru, api.meowtv.ru | API JSON → HLS | stream | QUARANTINE→REMOVE |

| 21 | movieNight | (movienig.ht) | HTML → HLS | stream | QUARANTINE→REMOVE |
| 22 | movy | (movy) | HTML → HLS | stream | QUARANTINE→REMOVE |
| 23 | multiEmbed | multiembed / 2embed.cc / 1embed.cc, enc-dec.app, hshare.ink, mvlink.blog | embed → HLS | stream | QUARANTINE→REMOVE |
| 24 | nova | nova-streamz.vercel.app | API → HLS | stream | QUARANTINE→REMOVE |
| 25 | peeStream | peestream.in, api./providers. | API → HLS | stream | QUARANTINE→REMOVE |
| 26 | purstream | purstream.club, api. | API → HLS | stream | QUARANTINE→REMOVE |
| 27 | riveStream | scrapper.rivestream.app | API → HLS | stream | QUARANTINE→REMOVE |
| 28 | vadapav | vadapav, stremio.vadapav.mov, pixeldrain.com | manifest/direct | stream | QUARANTINE→REMOVE |
| 29 | vidApi | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 30 | vidCore | vidcore.org | embed → HLS | stream | QUARANTINE→REMOVE |
| 31 | vidFast | vidfast.vc | embed → HLS | stream | QUARANTINE→REMOVE |
| 32 | vidGod | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 33 | vidLink | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 34 | vidRock | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 35 | vidSrc | vidsrcme.ru, data.vidsrcme.ru | embed → HLS | stream | QUARANTINE→REMOVE |
| 36 | vidUp | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 37 | vidVault | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 38 | vidZee | vidzee.wtf, core., player. | embed → HLS | stream | QUARANTINE→REMOVE |
| 39 | videasy | player.videasy.to | embed → HLS | stream | QUARANTINE→REMOVE |
| 40 | vixSrc | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 41 | vuflix | (rodzina) | embed → HLS | stream | QUARANTINE→REMOVE |
| 42 | xDownloader | (rodzina) | direct | stream | QUARANTINE→REMOVE |
| 43 | xPass | play.xpass.top | API → direct | stream | QUARANTINE→REMOVE |
| 44 | zxcStream | player.zxcstream.xyz | embed → HLS | stream | QUARANTINE→REMOVE |
| 45 | cineSrc **MARTWY** | cinesrc.st | HTML → HLS | — | **REMOVE** (0 referencji) |
| 46 | cineSu **MARTWY** | cine.su | HTML → HLS | — | **REMOVE** (0 referencji) |

## 3. Wnioski wspólne dla 44 aktywnych scraperów (odpowiedź A–M)

- **A. Co robi**: z ID TMDB/IMDb (lub tytułu) pobiera HTML/API i ekstrahuje HLS/direct do odtwarzania.
- **B/C. Metadata/availability**: NIE dostarczają metadata ani licencjonowanej dostępności (metadata = TMDB/AniList/Simkl/Trakt/MDBList).
- **D. Zwraca**: wyłącznie `stream` (HLS lub direct file); zero deep link, zero legal metadata.
- **E. Legalność**: NONE — wszystkie redystrybuują treść bez licencji; brak podstawy prawnej w kodzie.
- **F. Autoryzacja**: brak (anonimowe endpointy).
- **G. Obce credentials**: brak poświadczeń kontowych; przy direct-hostingach bywają URL-owe tokeny.
- **H. Ekstrakcja HTML**: TAK dla większości; kilka czystych API JSON (kissKh, meowTv, peeStream…).
- **I. DRM/paywall bypass**: de facto TAK — udostępniają treść chronioną bez praw.
- **J. Proxy**: brak dedykowanych proxy (nagłówki CDN z `CdnHeaderResolver` — spoofing UA/Referer/Origin).
- **K. Cookies/tokeny**: przenoszone jako nagłówki odtwarzania; brak trwałych sesji.
- **L. SSRF**: URL-e budowane z ID TMDB/IMDb; ryzyko średnie — wymaga `SafeMediaFetcher` przy implementacji.
- **M. Registry**: TAK — wszystkie 44 w `FoxTvHttpScraperManager.scrapers` (wykonywane w `StreamRepositoryImpl`).
- **PL audio/subtitles**: brak gwarancji; żaden host nie jest polskim VOD. **ZERO źródeł PL w rejestrze.**
- **Testy**: brak testów jednostkowych per-scraper; `Last verified` = nieoznaczane.

## 4. Pozostałe systemy HTTP/VOD-adjacent (poza 44)

| System | Plik | Host(y) | Co robi | Decision PROPOSED |
|---|---|---|---|---|
| TMDB | `data/remote/api/TmdbApi.kt`, `core/tmdb/*` | api.themoviedb.org | metadata, search, watch-providers (region), images, collections | **KEEP — DISCOVERY/AVAILABILITY (atrybucja TMDB + JustWatch obowiązkowa)** |
| Trailers | `data/trailer/TrailerService.kt` | www.youtube.com, youtubei/v1/player (InnerTube) | zwiastuny YouTube | **KEEP — DISCOVERY_ONLY/EXTERNAL** (zwiastun ≠ film; brak klucza API InnerTube do rozważenia w 9A.4) |
| Subtitles | `core/subtitles/OpenSubtitlesSubtitleProvider.kt` | OpenSubtitles API | napisy | **KEEP (legalne; wymaga klucza/rejestracji)** |
| BestSimilar | `core/metadata/BestSimilarScraper.kt` | bestsimilar | rekomendacje podobnych | QUARANTINE/REFACTOR (HTML scraping, niefunkcjonalny dla playback) |
| Stremio addons | `data/repository/AddonRepositoryImpl.kt`, `AddonMapper`, `AddonManifestDto` | manifesty użytkownika (dowolne hosty) | instalacja addonów Stremio | **USER_SUPPLIED_ONLY** (katalog publiczny NIE awansuje capability) |
| Serwer lokalny | `core/server/*` (AddonWebPage, RepositoryWebPage) | localhost | eksport/instalacja addonów | KEEP (tooling) |
| DeepLink | `core/deeplink/*` | — | parsowanie linków wejściowych | KEEP (routing) |
| FanFilm | `core/fanfilm/*` + assets (plugin.video.fanfilm + ResolveURL 5.1.208, 230 resolverów) | adresy resolverów | katalog + źródła przez Python/Kodi bridge | **QUARANTINE→ decyzja per resolver** (ten sam problem prawny; infrastruktura MOSTU KEEP) |
| Cloudstream | `com/lagradost/cloudstream3/**` + `core/plugin` | zależne od pluginów .cs3 | external plugins | **USER_SUPPLIED_ONLY** (host MUST-KEEP) |
| Aniyomi | `core/plugin/aniyomi/**` | pin Jellyfin 14.17 | statyczna inspekcja APK (bez ładowania) | KEEP (host) + UNKNOWN per extension |
| IPTV | `core/iptv/*` | patrz `p2p-source-matrix.md` §IPTV | M3U/XMLTV + harvesting credentiali (IptvScraper, PasteShDecryptor, IptvClient auto-login) | **REMOVE harvesting; KEEP user-supplied M3U** (plan zadań 17–22) |
| Trakt/Simkl | `core/trakt`, `data/simkl`, `core/tracking` | api.trakt.tv, simkl | sync (nie źródła streamów) | KEEP (własna autoryzacja użytkownika) |
| MDBList/IntroDB/ParentalGuide | BuildConfig URLs | konfigurowalne | metadata pomocnicze | KEEP (DISCOVERY) |
| ServerDiscovery | `core/server` (`/.well-known/playtorrio`) | sieć lokalna | parowanie z własnym serwerem | KEEP (allowlista nazwy wire-protocol) |
| Torrent | `core/torrent/*` (TorrServerApi/Binary/Service) | TorrServer lokalny | silnik P2P dla user-supplied | patrz `p2p-source-matrix.md` |

## 5. Liczniki (stan po inwentarzu 9A.2)

- CURRENT PROVIDERS COUNT (wszystkie technologie): **~78 jednostek** (44 HTTP aktywne + 2 martwe + 13 anime + 2 P2P-indeksy + 5 debrid + IPTV stack (6) + manga (1) + FanFilm (1 katalog, 230 resolverów wewnętrznie) + Cloudstream (host+pluginy) + Aniyomi (host) + TMDB + trailers + subtitles + BestSimilar + stremio-addons + torrent-engine + Trakt/Simkl/MDBList/IntroDB/ParentalGuide jako DISCOVERY)
- HTTP/VOD: **46 klas (44 aktywne, 2 martwe)**
- DISCOVERY ONLY (proponowane): TMDB, Trailers, MDBList, IntroDB, ParentalGuide, BestSimilar(→REFACTOR), Trakt/Simkl (sync)
- EXTERNAL DEEP LINK (docelowo): TVP VOD / Ninateka / 35mm.online / MANGA MILL (do dodania — patrz replacement-plan)
- KEEP: TMDB, subtitles, deeplink, server-local, torrent-engine, Trakt/Simkl, hosty FanFilm/Cloudstream/Aniyomi
- REFACTOR: BestSimilar, IPTV (bez harvesting)
- REPLACE: 44 HTTP scrapery (wymagają legalnych replacementów) + anime 12/13 (patrz anime matrix)
- QUARANTINE: FanFilm resolver-y (per resolver), Brak stałego QUARANTINE dla HTTP (rekomendacja:_QUARANTINE przed usunięciem)
- REMOVE: CineSrc, CineSu (martwy kod), IPTV harvesting (IptvScraper/PasteShDecryptor/IptvClient-auto-login)
- USER_SUPPLIED_ONLY: Stremio addons, Cloudstream pluginy, IPTV M3U, magnety/torrenty
- OPEN_MEDIA_ONLY: do skonfigurowania (Internet Archive / Wikimedia — patrz web-research)
- UNKNOWN: Aniyomi extension (poza pinem), per-resolver FanFilm (230 — wymaga osobnej analizy), per-scraper deep-dive (szczegóły A–M per plik)
