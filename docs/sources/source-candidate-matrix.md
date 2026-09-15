# FOX.TV — Source Candidate Matrix (ETAP 3A.1 / Checkpoint I)

- DATA: 2026-09-14 · ŹRÓDŁO: kandydaci ze screenshotów + wcześniejsze badania · Status weryfikacji: **VERIFIED (fetch)** / **EXISTS-BLOCKED (anty-bot)** / **UNKNOWN (do sprawdzenia)**. Weryfikacja istnienia ≠ zgodność z prawem autorskim — decyzje capability per-element.
- ZASADA: ADD_FIRST / FIX_FIRST; kandydat ≠ integracja bez audytu technicznego.

## ANIME (PL)

| name | host | Weryfikacja | language | technicalMethod | nextAction |
|---|---|---|---|---|---|
| Anime-Odcinki | anime-odcinki.pl | **VERIFIED** (fetch: katalog anime+chińskie, fansub, PEGI, oceny, PL community) | PL sub (fansub), dub częściowo | HTML→player (embedy/hostery) | extractor HTML + mapowanie odcinków; ADD ALONGSIDE planu anime |
| Shinden | shinden.pl | EXISTS-BLOCKED (HTTP 467 anti-bot) | PL sub/dub | HTML+API? (WebView/CF research) | badanie anti-bot; reuse `CloudflareKiller` |
| OgladajAnime | ogladajanime.pl | UNKNOWN | PL sub | HTML/API | weryfikacja fetch |
| AnimeOn | animeon.pl | UNKNOWN | PL sub/dub | HTML → hostery | weryfikacja |
| AnimeZone | animezone.pl | UNKNOWN | PL sub | HTML | weryfikacja |
| Desu Online | desu-online.pl | UNKNOWN | anime+manga PL sub | HTML | weryfikacja |
| Docchi | docchi.pl | UNKNOWN | PL sub | HTML/SPA | weryfikacja |
| FrixySubs | frixysubs.pl | **VERIFIED** (SPA, fansub PL) | PL sub | SPA/API do zbadania | badanie API/JS |
| Grupa Mirai | grupa-mirai.pl | UNKNOWN | PL sub | HTML | weryfikacja |
| StrefaDB | strefadb.pl | UNKNOWN | metadata anime | API? | metadata kandydat |
| Yorigami Subs | cda.pl (kanał) | cda.pl **VERIFIED** | PL sub | CDA public embeds | ResolveURL **cda.py istnieje** → ADD przez cda resolver |

## BAJKI / KRESKÓWKI (PL)

| name | host | Weryfikacja | contentType | language | nextAction |
|---|---|---|---|---|---|
| Bajeczki TV | bajeczki.tv | EXISTS-BLOCKED (403) | bajki | PL audio | badanie anti-bot |
| Bajki dla dzieci | bajkidladzieci.co.pl | UNKNOWN | bajki | PL audio | weryfikacja |
| Forum Bajki-TV | forum.bajki-tv.com | UNKNOWN | bajki (forum+zbiory) | PL audio | weryfikacja |
| Hefalump | hefalump.pl | UNKNOWN | bajki | PL audio | weryfikacja |
| Kreskówka Subs | kreskowkasubs.blogspot.com | UNKNOWN | kreskówki | PL sub | Blogger parsing |
| Kreskówki TV | kreskowki.tv | UNKNOWN | kreskówki | PL audio | weryfikacja |
| Vestroiakr | vestroiakr.blogspot.com | UNKNOWN | kreskówki | PL audio | jw. |

## FILMY / SERIALE (PL)

| name | host | Weryfikacja | language | technicalMethod | nextAction |
|---|---|---|---|---|---|
| **CDA** | cda.pl | **VERIFIED** (fetch: hosting PL + CDA Premium LEGAL — umowy z Monolith/Kino Świat/Lionsgate/BestFilm/Galapagos; 23k+ tytułów; TV live 115 kanałów; aplikacje TV w tym Android TV) | PL audio/dub/napisy (per element) | ResolveURL **cda.py istnieje**; Premium = AUTHENTICATED_SERVICE; free = user-up | **PRIORYTET #1**: reguła CDN + test resolvera; opcja partnerstwa Premium |
| Filman | filman.cc | **VERIFIED** (fetch: katalog Filmy/Seriale/Dzieci/HD, PL opisy, oceny, kategorie) | PL dubbing/lektor/napisy (per element) | HTML → hostery/embedy | extractor HTML; ADD |
| Ekino-TV | ekino-tv.pl | UNKNOWN | PL lektor/napisy | HTML | weryfikacja + extractor |
| Zaluknij | zaluknij.cc | UNKNOWN | PL lektor | HTML | weryfikacja |
| IITV | iitv.info | UNKNOWN | PL lektor | HTML | weryfikacja |
| Bajeczki24 | bajeczki24.pl | UNKNOWN | PL audio | HTML | weryfikacja |
| CDA-HD | cda-hd.cc | UNKNOWN | PL | HTML | uwaga: nieoficjalna pochodna cda.pl — odróżnić od oficjalnego |
| Filmowo | filmowo.xyz | UNKNOWN | PL | HTML | weryfikacja |
| Filmy Polskie 999 | filmypolskie999.blogspot.com | UNKNOWN | PL audio | Blogger parsing | weryfikacja |
| FlowFlix | flowflix.vercel.app | UNKNOWN | ? | SPA/API | weryfikacja |
| FreeDisc | freedisc.pl | UNKNOWN | PL | API? | weryfikacja |
| MaxVod | maxvod.tv | UNKNOWN | ? | API? | weryfikacja |
| Obejrzyj.to | obejrzyj.to | UNKNOWN | PL lektor | HTML | weryfikacja |
| OpenClip | openclip.info | UNKNOWN | PL | HTML | weryfikacja |
| PremiumSmart | premiumsmart.eu | UNKNOWN | ? | ? | weryfikacja |
| Seriale VIP | seriale.vip | UNKNOWN | PL | HTML | weryfikacja |
| Telekino | telekino.top | UNKNOWN | PL | HTML | weryfikacja |
| Vider | vider.info | UNKNOWN | PL | API? | weryfikacja |
| Virpe | virpe.cc | UNKNOWN | PL | HTML | weryfikacja |
| ElmoreFlix | elmoreflix.netflix.app | UNKNOWN | ? | SPA? | weryfikacja |

## FILMY / SERIALE ZAGRANICZNE (nie-PL)

| name | host | Weryfikacja | Nota |
|---|---|---|---|
| DopeBox | dopebox.to | UNKNOWN | embed-agregator (klasa istniejących 44) — niska nowość |
| FMovies | www2.fmovies.do | UNKNOWN | jw. |
| Movies2Watch | movies2watch.cc | UNKNOWN | jw. |

## P2P / TORRENT (kandydaci indeksów)

| name | host | Klasa | Weryfikacja | nextAction |
|---|---|---|---|---|
| BTDig | btdig.com | DHT search | UNKNOWN | audyt; klasyfikacja PUBLIC_INDEX |
| Devil-Torrents | devil-torrents.pl | PL tracker | UNKNOWN | audyt (PL treści — legalność per element) |
| Electro-Torrent | electro-torrent.pl | PL tracker | UNKNOWN | jw. |
| RSTorrent | rstorrent.org.pl | PL tracker | UNKNOWN | jw. |
| TorrentLeech (pl) | torrentleech.pl | tracker | UNKNOWN | jw. |
| LimeTorrents | limetorrents.com | globalny | UNKNOWN | jw. |
| Torlock | torlock2.com | globalny (verified torrents) | UNKNOWN | jw. |
| TorrentDownload / GloDLS / HellTorrents / CinemaMovies | … | globalny | UNKNOWN | jw. |

**P2P UWAGA (§19 promptu)**: indeksy = DISCOVERY/UNKNOWN prawne; capability PLAYBACK nigdy automatycznie; TorrServer/transport/magnet/debrid pozostają (KEEP). „Konto w debrid" ≠ „prawa do materiału".
