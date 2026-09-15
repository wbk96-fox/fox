# FOX.TV — Web Research (ETAP 3A / 9A.4)

- DATA: 2026-09-14 · METODA: wyłącznie oficjalne źródła (dokumentacje/API/terms-of-use, strony instytucji); NIE SEO. Wyszukiwarka www niedostępna w tej sesji (brak kredytów) — użyto bezpośrednich pobrań oficjalnych URL-i; pozycje niezweryfikowane oznaczono jawnie.
- STATUS: **WEB RESEARCH = PARTIAL/INCOMPLETE** (klasy kluczowe potwierdzone; pozostałe do dokończenia).

## Potwierdzone (oficjalne pobrania 2026-09-14)

| Kandydat | Oficjalny URL (pobrany) | Co potwierdzono | Klasyfikacja | Techniczny dopasowanie | Security fit | Last verified |
|---|---|---|---|---|---|---|
| TVP VOD | https://vod.tvp.pl/ | Oficjalny serwis VOD Telewizja Polska S.A. — „filmy, seriale, programy i tv na żywo”; legalne, darmowe i płatne treści PL | **EXTERNAL_DEEP_LINK / AVAILABILITY (PL_NATIVE)** | strona publiczna; brak publicznego API → deep-link per tytuł (URL scheme do potwierdzenia w implementacji) | WYSOKI (oficjalny podmiot) | 2026-09-14 |
| 35mm.online | https://35mm.online/ | Oficjalna platforma streamingowa WFDiF — „polskie filmy fabularne, dokumentalne, kroniki filmowe, filmy animowane oraz materiały dla dzieci”; SPA (bez JS nie renderuje) | **EXTERNAL_DEEP_LINK / AVAILABILITY (PL_NATIVE)** | SPA — deep-link wymaga sprawdzenia routingu; brak publicznego API | WYSOKI | 2026-09-14 |
| Internet Archive | https://archive.org/advancedsearch.php | Oficjalny silnik wyszukiwania (Lucene-like), pola metadanych, JSON (`output=json`), pola licencji (licenseurl) i `mediatype=movies`; itemy public domain / CC; torrenty per item | **VERIFIED_IN_APP_MEDIA (OPEN_LICENSED / PUBLIC_DOMAIN)** | pełne API wyszukiwania + metadanych per item (license/creator/date); formaty wideo do odtwarzacza | WYSOKI | 2026-09-14 |
| Wikimedia Commons | https://commons.wikimedia.org/w/api.php | Oficjalne MediaWiki Action API (GET/POST, format=json, limity 50/500, CORS `origin=*`); metadane licencji per plik | **VERIFIED_IN_APP_MEDIA (OPEN_LICENSED)** | API dojrzałe; wideo rzadkie, ale możliwe (WebM); licenseurl w metadanych | WYSOKI | 2026-09-14 |
| TMDB | https://www.themoviedb.org/documentation/api/terms-of-use | **API Terms of Use (wersja 2023-10-20)**: atrybucja TMDB OBOWIĄZKOWA dla TMDB Content (§2.B, §3 Attribution); zakaz cache >6 miesięcy; zakaz maskowania aplikacji; zakaz użycia ML/jej treningu | **KEEP — DISCOVERY/AVAILABILITY** | juże zintegrowane (TmdbApi); watch-providers = warstwa dostępności | WYSOKI | 2026-09-14 |

## Kandydaci potwierdzeni pośrednio (istniejące integracje; bez świeżego pobrania)

| Kandydat | Źródło w projekcie | Status | Nota |
|---|---|---|---|
| JustWatch (dane watch-provider) | przez TMDB watch/providers | **ATRYBUCJA WYMAGANA** — TMDB dostarcza dane dostępności od JustWatch; atrybucja TMDB+JustWatch obowiązkowa w UI | zgodnie z ToU TMDB |
| OpenSubtitles | `core/subtitles/OpenSubtitlesSubtitleProvider.kt` | KEEP; **docs URL do odświeżenia** (stoplight 404 przy poprzednim adresie); klucz API wymagany | licencjonowane napisy |
| Ninateka (FINA) | — | kandydat PL_NATIVE; **fetch nieudany (2×)** — strona blokuje boty; wymagane potwierdzenie w przeglądarce przed implementacją | EXTENTIAL_DEEP_LINK (potencjalny) |
| YouTube (trailery) | `data/trailer/TrailerService.kt` | obecna integracja InnerTube BEZ klucza — niezgodna z oficjalnym API; do przeprojektowania (YouTube Data API v3 lub deep-link) | DISCOVERY_ONLY |
| Anime legal (PARTNER_API_PLAYBACK) | — | **BRAK publicznych API** platform anime z PL sub bez umowy partnerskiej; wymaga decyzji właściciela (umowy) — nie implementować bez zgody | OPEN |
| Legalne torrenty (open licensed) | — | Internet Archive udostępnia torrenty per item (open licensed) — ścieżka OPEN_LICENSED dla P2P | potwierdzone przez IA (torrenty per item) |

## Klasy do dokończenia (WEB RESEARCH INCOMPLETE)

1. **PL VOD dostępność per tytuł** — TMDB watch/providers region=PL → mapowanie provider_id → oficjalny deep-link (TVP VOD/Netflix/HBO/SkyShowtime/CDA/Canal+/Player…): wymaga tabeli mapowania (kolejny krok researchu; TMDB zwraca tylko nazwy providerów).
2. **FanFilm/ResolveURL resolvery (230)** — klasyfikacja per resolver: domeny, legalność, status utrzymania (duży osobny przebieg).
3. **Aniyomi extensions** — repo `aniyomiorg/aniyomi-extensions` (pin istnieje w `.kiro/research/aniyomi`); per-extension status UNKNOWN.
4. **Cloudstream pluginy** — repo Lagradost/CloudStream (host MUST-KEEP); pluginy = USER_SUPPLIED.
5. **Otwarte archiwa dodatkowe** (np. Kanopy/PublicDomainVault) — do badania; nie-SEO.
6. **Rate limits** — TMDB (≈50 req/s dokumentowane), IA (beAuth 要求 per API), OpenSubtitles (kredyty) — szczegóły do potwierdzenia w implementacji.

## Wniosek dla replacement-plan

- Legalny rdzeń zastępujący 44 scrapery: **TMDB (discovery+availability, PL) → TVP VOD / 35mm.online / Ninateka (EXTERNAL_DEEP_LINK) → Internet Archive / Wikimedia (VERIFIED_IN_APP_MEDIA, OPEN) → user-supplied (Stremio/M3U/magnet/torrent) → debrid (własne konta) → istniejący player**.
- **PL ścieżka**: preferencja PL provider → PL audio → PL sub → original+PL sub → original (model pól w replacement-plan).
