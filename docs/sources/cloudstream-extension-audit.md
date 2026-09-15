# FOX.TV — Cloudstream Extension Audit (ETAP 3A.1 / Checkpoint F)

- DATA: 2026-09-14 · ZASADA: Cloudstream pozostaje pełną infrastrukturą (NIE „user supplied only” — rewizja 3A)

## 1. Stan lokalny (FOX.TV)

- Vendored: `com/lagradost/cloudstream3/**` (v4.7.0 — MUST-KEEP nazwy/klasy), `core/plugin/*` (loader .cs3, ExternalExtensionInstaller/Loader, 59/59 lifecycle gate, M1 CLOSED).
- Infrastruktura: `CloudflareKiller` (CF interceptor), `CloudStreamApp`/`AcraApplication` (WebView usage), network utils — **REUSE dla FOX scraperów**.

## 2. Upstream (VERIFIED fetch 2026-09-14)

- Repo: `github.com/Blatzar/cloudstream-extensions` (Public Domain, „under development”, 3 commits, ~70 providerów w strukturze repo).
- **Kluczowe znalezisko: `FilmanProvider` istnieje upstream** — filman.cc już ma gotowy provider Cloudstream (REUSE zamiast pisać od zera!).
- Inne overlap-y z kandydatami/kompetencjami FOX: `DopeboxProvider`, `FmoviesToProvider`, `TwoEmbedProvider`, `VidSrcProvider`, `KisskhProvider`, `HDTodayProvider`, `SflixProvider`… — pokrywają większość rodzin naszych 44 scraperów.
- README: „not all extractors are included, use loadExtractor” — architektura loadExtractor do weryfikacji w vendored kodzie.

## 3. Implikacje dla FOX.TV

| Opcja | Ocena |
|---|---|
| A. Przenieść `FilmanProvider` jako plugin .cs3 przez istniejący loader | REUSE maksymalny; test przez 59/59 infrastrukturę; zero nowego kodu scraperów |
| B. Port `FilmanProvider` do natywnego `FilmanScraper` (Kotlin, wzorzec istniejących 46) | kontrola pełna; koszt umiarkowany |
| C. Czekać na upstream | NIE — brak gwarancji tempa |

**Rekomendacja: A (ADD ALONGSIDE), ewentualnie B jeżeli plugin-ABI wymaga zmian** — decyzja w Checkpoint L po runtime verify na urządzeniu.

## 4. Extension ecosystem

- Wiele repo pluginów w społeczności (poza Blatzar); ocena pochodzenia/aktualności/kompatybilności per repo PRZED włączeniem; pluginy instalowane przez użytkownika = pełny audyt statyczny jak .cs3 (M1 boundary).
- Statusy: upstream **VERIFIED**; per-provider runtime **NOT VERIFIED**.
