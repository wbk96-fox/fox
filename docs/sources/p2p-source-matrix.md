# FOX.TV — P2P Source Matrix (ETAP 3A / 9A.7–9A.8)

- DATA: 2026-09-14 · ŹRÓDŁA FAKTÓW: `core/scraper/p2p/*`, `core/torrent/*`, `core/debrid/*`, `data/repository/*` (read-only)
- ZASADA (9A.7): P2P ≠ zwykły HTTP provider. Kategorie: USER_SUPPLIED_MAGNET, USER_SUPPLIED_TORRENT, USER_OWNED, OPEN_LICENSED, CURATED_AUTHORIZED, PUBLIC_INDEX_DISCOVERY. **Seed count / popularność / PL badge / nazwa / tracker NIE są dowodem praw.**

## 1. Silnik P2P (transport)

| Element | Plik | Stan | Decision |
|---|---|---|---|
| TorrServer (wbudowany) | `core/torrent/TorrServerApi.kt`, `TorrServerBinary.kt`, `TorrentService.kt`, `TorrentSettings.kt`, `TorrentState.kt` | lokalny silnik torrent (MatriX.141, 4 ABI, statyczny Go) | **KEEP** jako transport dla autoryzowanych wejść (transport sam nie wnosi praw) |

## 2. Indeksy P2P (odkrywanie)

| Provider | Plik | Mechanizm | Klasyfikacja | Decision PROPOSED |
|---|---|---|---|---|
| Knaben | `p2p/KnabenScraper.kt` | publiczny indeks torrentów (API/HTML) | PUBLIC_INDEX_DISCOVERY / UNKNOWN prawne | **REMOVE z domyślnego planu** (indeksuje treść chronioną; brak podstawy praw; zgodne z zadaniem 15.1) |
| TorrentGalaxy | `p2p/TorrentGalaxyScraper.kt` | publiczny indeks torrentów | PUBLIC_INDEX_DISCOVERY / UNKNOWN prawne | **REMOVE z domyślnego planu** (jak wyżej) |

## 3. Debrid (prywatne, płatne konta użytkownika — NIE P2P-odkrywanie, ale oparte o torrenty/magnety)

| Provider | Plik | Mechanizm | Legalność | Decision |
|---|---|---|---|---|
| RealDebrid | RealDebridDirectDebridResolver.kt (+FileSelector) | API użytkownika + konto | usługa użytkownika; odpowiedzialność za użytek po stronie użytkownika | **KEEP** (USER_OWNED/USER_SUPPLIED; autoryzacja własna) |
| AllDebrid | AllDebridDirectDebridResolver.kt | jw. | jw. | **KEEP** |
| DebridLink | DebridLinkDirectDebridResolver.kt | jw. | jw. | **KEEP** |
| Premiumize | PremiumizeDirectDebridResolver.kt (+DirectDownloadFileSelector) | jw. | jw. | **KEEP** |
| Torbox | TorboxDirectDebridResolver.kt (+FileSelector) | jw. | jw. | **KEEP** (uwaga: file ID vs index — naprawione w PhM) |

## 4. Ścieżki dozwolone (model docelowy)

| Wejście | Kategoria | Wymagania | Capability |
|---|---|---|---|
| magnet/torrent wklejony przez użytkownika | USER_SUPPLIED_MAGNET/TORRENT | jawna zgoda UI + redakcja sekretów | PLAYBACK (po governance) |
| plik użytkownika / własny materiał | USER_OWNED | dowód posiadania (deklaracja; bez inferencji z magneta!) | PLAYBACK (po governance) |
| legalny/open-licensed torrent (np. Public Domain, CC) | OPEN_LICENSED | weryfikowalna licencja per element | PLAYBACK (po governance) |
| katalog kuratorowany | CURATED_AUTHORIZED | umowa/zezwolenie w `ProviderGovernanceRecord` | PLAYBACK (po governance) |
| odkrywanie w publicznych indeksach | PUBLIC_INDEX_DISCOVERY | — | **NIGDY PLAYBACK** (najwyżej DISCOVERY z wyraźnym oznaczeniem — decyzja ownera; default: OFF) |

## 5. Braki / ryzyka (stan obecny)

1. `FoxTvP2PScraperManager` wykonuje Knaben+TorrentGalaxy przy scrapeStreams — **brak bramki autoryzacji wejścia** (zadanie 15.1–15.2: usunięcie z planu + `P2PAuthorizationGate`).
2. Brak modelu praw per-element (`DeclaredRightsBasis`) — do dodania w 15.2.
3. Unicode/odznaki: `FoxTvP2PFilter` — wymaga NFC + niezależnych odznak PL (zadanie 15.3); odznaka PL nie może awansować praw.
4. Debrid: file-ID vs index naprawione; wymaga testów regresyjnych przy zmianach (regresja w zadaniu 27).

## 6. Liczniki P2P

- INDICES: 2 (Knaben, TorrentGalaxy) → decyzja: REMOVE z planu domyślnego
- ENGINE: 1 (TorrServer) KEEP
- DEBRID: 5 KEEP
- USER_SUPPLIED paths: magnet/torrent/file (do implementacji w 15.2)
- UNKNOWN: jakość metadanych językowych w indeksach (niebadane — niezależne od decyzji o usunięciu)
