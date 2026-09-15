# FOX.TV — Source Boundary Report (ETAP 3A / 9A.1)

## Werdykt

**ARCHITECTURE FREEZE = COMPLETE (z 2 blokującymi UNKNOWN do decyzji właściciela)**

- Rzeczywista granica źródeł została ustalona: APK powstaje wyłącznie z
  `app/src/main/java` + `app/src/main/python` (Chaquopy) + `app/src/main/cpp` (CMake/Dovi)
  + `app/libs/*.aar` + `app/src/main/jniLibs` + `app/src/main/res` + `app/src/main/assets`
  + warunkowo `ffmpeg-decoder-downmix` (source-mode).
- Żadna ścieżka `build/`, `.gradle/`, `generated/`, `intermediates/`, `CMakeFiles/`, `*.o`,
  `*.class` nie jest traktowana jako implementacja produkcyjna.
- Moduły: `:app`, `:baselineprofile`, warunkowy `:ffmpeg-decoder-downmix`. Brak included builds.

## UNKNOWN (blokujące) — wymagana decyzja

| ID | Path | Symbol | Reason | Blocking evidence | Rekomendacja |
|---|---|---|---|---|---|
| UNKNOWN-1 | `org/libtorrent4j/swig/byte_vector.class` | `org.libtorrent4j.swig.byte_vector` | osierocony artefakt kompilacji poza source root | 0 referencji (`grep libtorrent4j` = 0 w app sources + Gradle); plik śledzony w git | REMOVE |
| UNKNOWN-2 | `assets/brand/app_logo_mark.png`, `assets/brand/app_logo_wordmark.png` | 2×PNG brandu | brak konsumentów w repo | `grep assets/brand` = 0 w scripts/tools/app | TOOLING_INPUT (udokumentować) albo REMOVE |

Obie pozycje NIE są produkcyjne (nie trafiają do APK), ale pozostają jawnie otwarte do decyzji,
zgodnie z zasadą „każdy UNKNOWN jest blokujący”.

## Granice specjalnych obszarów (powiązanie z checkpointem)

- **Media3 fork**: `app/libs/lib-*.aar` (10 AAR) — namespace upstream `NuvioEngineConfig` MUST-KEEP.
- **FFmpeg**: `ffmpeg-decoder-downmix` + pin `n7.1.5` / commit `3a0867c2…` (source-mode).
- **IAMF**: `app/libs/lib-decoder-iamf-release.aar`.
- **Dovi**: `app/src/main/cpp/dovi_bridge.cpp` + `DV7/libdovi/*.a` (JNI `Java_com_foxtv_app...`).
- **QuickJS**: `app/libs/quickjs-kt-android-1.0.5-playtorrio.aar` (nazwa pliku = legalna atrybucja upstream).
- **Python/Chaquopy**: `app/src/main/python` (29 plików) + Chaquopy 17.0.0 + Python 3.11.
- **Cloudstream**: `com/lagradost/cloudstream3/**` — nazwy/klasy/plugin IDs MUST-KEEP.
- **Aniyomi host**: `eu/kanade/tachiyomi/**` + `uy/kohesive/injekt/**` (piny i licencje).
- **FanFilm**: `app/src/main/assets/fanfilm` (5 addonów, MANIFEST.json sha256).
- **TorrServer**: `app/src/main/jniLibs/**/libtorrserver.so` (MatriX.141, static).
- **IPTV/Manga**: patrz macierze `docs/sources/*` (etap 9A.2–9A.7).

## Liczby

- Kotlin main 957 · JVM test 219 · androidTest 9 · Python 29 · C/C++ 2
- Local AAR 12 · JNI `.so` 4 · `libdovi.a` 4 · Assets 1330 · FFmpeg module 281
- Tooling 33 · Dokumentacja/procesy: AGENTS/PROJECT_ARCHITECTURE/README/CONTRIBUTING/LICENSE + .kiro + semantic-review (11)
