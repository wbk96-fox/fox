# FOX.TV — Implementation Delivery Status

Data: 2026-09-15
Snapshot: current modified working tree

## What this file means

This is the implementation delta against the Final Master Audit. It deliberately distinguishes implemented code from source research or screenshot-only catalog entries. No item is marked runtime verified unless a device/build gate actually ran.

## Changes applied
- FanFilm lifecycle upgraded with navigation generation + runId + pluginUrl context propagation.
- Stale event acceptance is now generation-aware in FanFilmViewModel.
- FanFilm SystemExit/no-output semantics now produce explicit typed failures instead of false success.
- Per-run context registry added to FanFilmRuntime.
- Stream model expanded with optional language/capability/evidence fields.
- Polish stream ranking is now applied to built-in HTTP results and final presented groups.
- P2P discovery is behind an explicit authorization gate.
- Public-catalog adapters added for Anime-Odcinki and Filman discovery; discovery URLs cannot be silently treated as direct playback streams.
- Release signing no longer contains hard-coded key/store passwords and fails closed only when a release task is actually requested.
- Global cleartext traffic and extractNativeLibs flags were removed from the manifest; network security base policy is fail-closed.
- Hard-coded TMDB/Wyzie/Audionest/YouTube fallback credentials were moved behind BuildConfig/local configuration.
- Broken vendored xbmcdrm.py was repaired and Python source was revalidated with AST parsing.

## Screenshot source coverage (code snapshot)

| Source | Current implementation | Type |
|---|---|---|
| Anime-Odcinki | NEW Kotlin discovery adapter: `AnimeOdcinkiScraper.kt` | implemented (discovery/availability) |
| AnimeOn | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| AnimeZone | Vendored FanFilm: `sources/pl/animezone.py` | implemented |
| Desu Online | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Docchi | Vendored FanFilm: `sources/pl/docchi.py` | implemented |
| FrixySubs | Vendored FanFilm: `sources/pl/frixysubs.py` | implemented |
| Grupa Mirai | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| OgladajAnime | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Shinden | Vendored FanFilm: `sources/pl/shinden.py` | implemented |
| Skanlacje Feniksy | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| StrefaDB | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Yorigami Subs | CDA path / existing `cda.py` | partial/through CDA |
| Bajeczki TV | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Bajki dla dzieci | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Forum Bajki-TV | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Hefalump | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Kreskówka Subs | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Kreskówki TV | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Vestroiakr | Vendored FanFilm: `sources/pl/vestroiakr.py` | implemented |
| Bajeczki24 | Vendored FanFilm: `sources/pl/bajeczki24.py` | implemented |
| CDA-HD | Vendored FanFilm: `sources/pl/cdahd.py` | implemented |
| Ekino-TV | Vendored FanFilm: `sources/pl/ekinotv.py` | implemented |
| ElmoreFlix | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Filman | Vendored FanFilm: `sources/pl/filman.py` | implemented |
| Filmowo | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Filmy Polskie 999 | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| FlowFlix | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| FreeDisc | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| IITV | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| MaxVOD | Vendored FanFilm: `sources/pl/maxvod.py` | implemented |
| Obejrzyj.to | Vendored FanFilm: `sources/pl/obejrzyj_filmy.py` | implemented |
| OpenClip | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| PremiumSmart | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Seriale VIP | Vendored FanFilm: `sources/pl/serialevip.py` | implemented |
| Telekino | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Vider | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Virpe | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Zaluknij | Vendored FanFilm: `sources/pl/zaluknijcc.py` | implemented |
| DopeBox | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| FMovies | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Movies2Watch | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| BTDig | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| CinemaMovies | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Devil-Torrents | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Electro-Torrent | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| GloDLS | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| HellTorrents | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| LimeTorrents | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| RSTorrent | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| Torlock | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| TorrentDownload | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |
| TorrentLeech | No one-to-one extractor/provider found in snapshot; remains candidate/deep-link target. | not implemented |

## P2P / Debrid

- Existing user-owned Debrid services remain: RealDebrid, AllDebrid, DebridLink, Premiumize, Torbox.
- P2P index discovery remains separated from authorization; the new `P2PAuthorizationGate` requires explicit P2P enablement for discovery.
- The eleven screenshot torrent names are still not one-to-one scraper implementations in this snapshot; they remain documented candidates rather than fabricated implementations.

## Verification

- Python AST validation: 951 Python files parsed successfully after the `xbmcdrm.py` repair.
- Isolated Kotlin compilation passed for the new pure-Kotlin governance and FanFilm generation classes.
- Full Gradle test/build could not run because the wrapper attempted to download Gradle 8.13 from `services.gradle.org`, which is unreachable in this execution environment.
- Real Google TV runtime and final APK remain NOT VERIFIED in this environment.

## Honest release status

**NOT production-ready yet.** The working tree contains substantially more of the audit implementation, but the remaining source-by-source integrations and device gates still require live build/device verification.


## POST-AUDIT IMPLEMENTATION UPDATE — 2026-09-15

### Implemented in this execution
- FanFilm lifecycle is generation-aware (`navigationGeneration` + `runId` + `pluginUrl`) at the Kotlin event/state gate.
- FanFilm directory events distinguish `SUCCESS_DIRECTORY` from `EMPTY_DIRECTORY`.
- Embedded lifecycle disables Kodi's periodic forced `SystemExit` policy inside the long-lived Chaquopy host.
- Per-run directory/menu state is explicitly reset before each plugin execution; `KodiDirectory.INFO` is repaired to an empty request context before category rendering when necessary.
- `SYSTEM_EXIT_NO_OUTPUT` and `NO_DIRECTORY_PUBLISHED` remain typed outcomes, while an actual empty directory is represented separately.
- Polish ranking is applied to presented stream groups in `StreamRepositoryImpl`, not only to the native HTTP scraper.
- The governance catalog now contains the screenshot/source inventory and explicitly distinguishes implemented FanFilm sources from discovery-only candidates and public P2P indexes.
- Release credentials remain externalized through `BuildConfig`; the sample properties file documents the new optional keys.

### Still requiring environment/device verification
- Full Gradle compile/test/regression (the original execution environment could not download Gradle 8.13).
- Google TV Streamer runtime verification and repeated navigation/focus stress tests.
- Final release APK signing/build verification.
- Per-site runtime verification for discovery-only candidates that have no repository extractor.
