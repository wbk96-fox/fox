# FOX.TV — Polish Source Research (ETAP 3A.1 / Checkpoint I)

- DATA: 2026-09-14 · Kategorie NIE MIESZANE: OFFICIAL / COMMERCIAL / OPEN / USER / DISCOVERY / DEEP_LINK / IN_APP
- Szczegóły techniczne per kandydat: `source-candidate-matrix.md`

## OFFICIAL (legalne, instytucjonalne)

| Źródło | Status | Zawartość PL | Metoda |
|---|---|---|---|
| TVP VOD (vod.tvp.pl) | **VERIFIED** (fetch 2026-09-14) | PL filmy/seriale/programy/TV live | OFFICIAL_DEEP_LINK + AVAILABILITY |
| 35mm.online (WFDiF) | **VERIFIED** (fetch) | PL filmy fabularne/dokumentalne/kroniki/animowane/dla dzieci | OFFICIAL_DEEP_LINK; SPA — routing do zbadania |
| Ninateka (FINA) | EXISTS-BLOCKED (fetch 2× failed) | PL filmy/animacja/dokument | do potwierdzenia w przeglądarce |
| FanFilm vendored (script.fanfilm.media) | w assets | PL katalog (aktywny) | przez most Python |

## COMMERCIAL (płatne, legalne)

| Źródło | Status | Nota |
|---|---|---|
| **CDA Premium** | **VERIFIED** | 23k+ tytułów na umowach licencyjnych (Monolith/Kino Świat/Lionsgate/BestFilm/Galapagos); TV live; Android TV app → **AUTHENTICATED_SERVICE / PARTNER_API_PLAYBACK kandydat #1** |
| NETFLIX/HBO Max/SkyShowtime/CDA/Canal+/Player (dostępność) | przez TMDB watch/providers region=PL | AVAILABILITY/DISCOVERY (deep link do zbadania per provider) |

## OPEN (open media)

| Źródło | Status | Nota |
|---|---|---|
| Internet Archive | VERIFIED (fetch) | public domain/CC; torrenty per item; **poza główną warstwą** wg §16 — tylko gdy wnosi wartość PL (np. polskie public domain w IA — do zbadania) |
| Wikimedia Commons | VERIFIED (fetch) | jw. |

## USER (user-supplied)

| Źródło | Status | Nota |
|---|---|---|
| CDA (darmowy hosting userów) | VERIFIED | ogromny PL zasób user-up; embedding publiczny; ResolveURL cda.py |
| Stremio addons | aktywny w projekcie | manifesty użytkownika |
| M3U (IPTV) | aktywny | własne listy użytkownika |
| magnet/torrent | aktywny | TorrServer transport |

## DISCOVERY (katalogowanie, nie odtwarzanie)

TMDB (+JustWatch atrybucja) · Trakt/Simkl (sync) · MDBList/IntroDB/ParentalGuide · BestSimilar (→REFACTOR)

## DEEP_LINK kandydaci PL (nieoficjalne katalogi — ADD-alongside, audyt per element)

Filman (**VERIFIED**) · Ekino-TV · Zaluknij · IITV · Obejrzyj.to · Seriale VIP · Telekino · Virpe · Vider · FreeDisc · MaxVod · Filmowo · Bajeczki24 (bajki) · blogspotowe zbiory (Filmy Polskie 999, Kreskówka Subs, Vestroiakr)

## NACISKI SPECJALNE

1. **PL AUDIO/DUBBING**: CDA (per element), filman-family (lektor/dub per element), TVP VOD (PL_NATIVE).
2. **PL NAPISY**: istniejący subtitle pipeline (OpenSubtitles + tracki z providerów) pozostaje; nowe źródła dostarczają media, napisy niezależnie.
3. **PL PRODUKCJE**: TVP VOD/35mm/Ninateka + CDA „Filmy polskie" + filman kategoria PL.
4. Język ustalany **per content item/season/episode** — nigdy „cały katalog = PL audio" bez dowodu.
