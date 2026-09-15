# FOX.TV — Aniyomi Extension Audit (ETAP 3A.1 / Checkpoint G)

- DATA: 2026-09-14 · ZASADA: infrastruktura Aniyomi = KEEP; ecosystem szerszy niż zarchiwizowane repo

## 1. Stan lokalny (FOX.TV)

- M2/M3 CLOSED: statyczna inspekcja APK (pin Jellyfin 14.17, hash+cert), host API/ABI (20 klas/90 obiektów), staged lifecycle (immutable generations), classloader ownership — **114/114 testów zielonych**; brak ładowania klas (zamierzone).
- Infrastruktura: `AniyomiCookieJar` (bounded RFC), `AniyomiNetworkClientFactory` (TLS), `AniyomiHostRuntime`, `PluginRuntimeHooks`.

## 2. Upstream (VERIFIED fetch 2026-09-14)

- `aniyomiorg/aniyomi`: **AKTYWNY** (7.7k★, 8133 commits, Apache-2.0, baza Mihon, player mpv-android, trackery: MAL/AniList/Kitsu/MangaUpdates/Shikimori/**Simkl**/Bangumi; wymaga Android 8+).
- `source-api` moduł w repo — kontrakt źródeł do porównania z naszym hostem ( difference check w kolejnym kroku ).
- Extension ecosystem: pierwotne `aniyomiorg/aniyomi-extensions` zarchiwizowane (pin w `.kiro/research/aniyomi`); dystrybucja extensionów obecnie przez repo-apki społeczności/forki — **ocena pochodzenia per repo wymagana** (provenance, podpisy, aktualność) przed użyciem.

## 3. Implikacje

1. Host FOX.TV utrzymuje kompatybilność z `source-api` z pinu (14.17); nowsze extensiony mogą wymagać nowszego `source-api` → badanie różnic (checkpoint kolejny).
2. Języki: extensiony dostarczają własne metadane sub/dub per tytuł — mapowanie na nasz model `SourceLanguage` w adapterze (bez zmiany hosta).
3. AniDB-fixtyura (pin) pozostaje immutabilna; nowe APK przechodzą pełny inspector (M2) przed jakimkolwiek ładowaniem.
4. Statusy: upstream **VERIFIED**; extensiony poza pinem **UNKNOWN (do audytu per repo)**; runtime **NOT VERIFIED**.
