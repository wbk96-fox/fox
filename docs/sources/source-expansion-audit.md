# FOX.TV — Source Expansion Audit (ETAP 3A.1 / Checkpoint A)

- DATA: 2026-09-14 · ZASADA: **NO-LOSS** (zero automatycznego REMOVE/QUARANTINE) · METODA: ekstrakcja faktów z kodu (grep per plik) + inwentarz infrastruktury
- **REWIZJA 3A**: zbiorcze „44→REMOVE” UCHYLONE. Każdy provider osobno.

## 1. Infrastruktura istniejąca (REUSE-FIRST — §37)

| Warstwa | Plik | Stan | Reuse dla |
|---|---|---|---|
| Nagłówki CDN/UA/Referer/Origin/Cookie | `core/network/CdnHeaderResolver.kt` (198 linii, **54 reguł hostów**) | AKTYWNY, centralny | wszystkie scrapery + player handoff |
| CookieJar (RFC-aware, bounded) | `core/plugin/aniyomi/host/AniyomiCookieJar.kt` | AKTYWNY (host Aniyomi) | rozszerzenie na scrapery wymagające sesji (Dulo=15 trafień cookie, HindMoviez=15, Mapple=12) |
| Cloudflare interceptor | `com/lagradost/cloudstream3/network/CloudflareKiller.kt` (vendored) | ISTNIEJE | scrapery z CF (Mapple=9 trafień, HindMoviez=4, Hexa=2, FlaxMovies=1) — integracja przed usuwaniem |
| WebView | tylko w vendored Cloudstream (`CloudStreamApp`, `AcraApplication`, `CloudflareKiller`) | ZASADNICZO NIEWYKORZYSTANY w FOX runtime | ewentualny challenge-flow (ocena kosztu pamięci przed użyciem — §29) |
| HTTP layer | per-scraper `OkHttpClient` + `CdnHeaderResolver` | AKTYWNY | nie tworzyć HTTP layer 2 |
| Liveness | `HttpStreamLivenessValidator` | AKTYWNY | pre-flight dla nowych źródeł |

## 2. Audyt 46 scraperów (fakty z kodu; status NO-LOSS)

Legenda: CF/CK/WV = liczba trafień wzorców Cloudflare/Cookie/WebView w pliku. Status per NO-LOSS: **KEEP** (działa wg kodu, bez znaku awarii) / **KEEP_AND_REPAIR** (widoczny punkt awarii w kodzie) / **PARTIAL** (niepełny kontrakt) / **UNKNOWN** (wymaga runtime verify) — runtime: **NOT VERIFIED** dla wszystkich (brak bramki live w tym etapie).

| Scraper | Host(y) | CF | Cookie | WV | Status NO-LOSS | Naprawa/rozszerzenie |
|---|---|---|---|---|---|---|
| A111477 | a.111477.xyz, st.111477.xyz | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Bcine | 1embed.cc (+api/token) | 0 | 0 | 0 | KEEP_AND_REPAIR | endpoint `api/token` — potwierdzić życie; token handling |
| Cinejoy | cinejoy.to, api.shegu.st | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify; reguła CDN istnieje |
| CineSrc (MARTWY) | cinesrc.st | 0 | 0 | 0 | PARTIAL (niezarejestrowany) | decyzja 14.3 po referencjach; nie usuwać przed NO-LOSS review |
| CineSu (MARTWY) | cine.su | 0 | 0 | 0 | PARTIAL (niezarejestrowany) | jw. |
| DownloadEverything | downloadeverything…, pixeldrain | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Dulo | dulo.gd/cx, d.dulo.gd | 0 | **15** | 0 | KEEP_AND_REPAIR | cookies w kodzie — przenieść na `AniyomiCookieJar`-style sesję |
| FlaxMovies | flaxmovies.xyz, workers.dev | **1** | 0 | 0 | KEEP_AND_REPAIR | CF — rozważyć `CloudflareKiller` reuse |
| FlyStream | flystream.net | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| FourKHDHub | 4khdhub.one | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Frame | api.peestream.in | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| FshareTv | fsharetv.cc/co, api.themoviedb | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| FSonic | fsharetv.co, fsonic.net | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| FSOnline | player.fsonline.app | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Hexa | enc-dec.app/api | **2** | 0 | 0 | KEEP_AND_REPAIR | CF — CloudflareKiller reuse |
| HindMoviez | hindmovie.icu, hshare.ink, mvlink.blog (admin-ajax) | **4** | **15** | 0 | KEEP_AND_REPAIR | CF+cookies — pełny hardening |
| KissKh | kisskh.do, enc-dec.app | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| LMScript | lmscript.xyz/v1 | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| LookMovie | lookmovie2.to | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Mapple | mapple.club | **9** | **12** | 0 | KEEP_AND_REPAIR | CF+cookies — najcięższy kandydat na CloudflareKiller |
| MegaPlay→(anime) | — | — | — | — | (poza tym plikiem) | — |
| MovieNight | (movienig.ht) | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Movy | movy.bz (CDN reguły) | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| MultiEmbed | 2embed/1embed, enc-dec.app | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Nova | nova-streamz.vercel.app | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| PeeStream | peestream.in | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Purstream | purstream.club | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| RiveStream | scrapper.rivestream.app | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Vadapav | stremio.vadapav.mov, pixeldrain | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| VidApi/VidCore/VidFast/VidGod/VidLink/VidRock/VidSrc/VidUp/VidVault/VidZee/Videasy/VixSrc/Vuflix | embed-agregatory | 0–2 | 0 | 0 | KEEP / UNKNOWN | runtime verify; wspólna rodzina — naprawy centralnie (§27 planu) |
| XDownloader/XPass/ZxcStream | direct | 0 | 0 | 0 | KEEP / UNKNOWN | runtime verify |
| Cinejoy dup. | — | — | — | — | DUPLICATE-check | porównanie z Cinejoy (ten sam shegu.st) |

## 3. Wnioski Checkpoint A

- **Żaden scraper nie jest oznaczony NOT_TECHNICALLY_USABLE** — brak dowodu; runtime verify wymagane dla wszystkich (Checkpoint L).
- Punkty awarii widoczne w kodzie: **Dulo/HindMoviez/Mapple (cookies), FlaxMovies/Hexa/HindMoviez/Mapple (CF)** → konkretne naprawy REUSE (`AniyomiCookieJar` wzorzec + `CloudflareKiller`).
- 2 martwe klasy (CineSrc/CineSu): **NIE usuwać w tym etapie** — dopiero decyzja właściciela (§42 deprecation), brak wpływu na runtime (nie są rejestrowane).
- Statusy runtime: **NOT VERIFIED** (wymagana bramka live — Checkpoint L).
